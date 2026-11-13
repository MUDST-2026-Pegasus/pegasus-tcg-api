package com.pegasus.pegasustcgapi.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.pegasus.pegasustcgapi.support.ApiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI document the running application publishes at {@code /v1/api-docs}, used two ways:
 * to answer "is METHOD path published?" for the consumer snapshot, and to validate a real
 * response body against the documented schema (swagger-request-validator, pinned in build.gradle).
 */
final class OpenApiContract {

    private static final String PREFIX = "/api/v1";

    private final Map<String, Set<String>> methodsByPath = new LinkedHashMap<>();
    private final Map<String, String> templateByShape = new LinkedHashMap<>();
    private final JsonNode document;
    private OpenApiInteractionValidator validator;

    private OpenApiContract(JsonNode document) {
        this.document = document;
        document.path("paths").properties().forEach(entry -> {
            String template = entry.getKey();
            String shape = shape(template.startsWith(PREFIX) ? template.substring(PREFIX.length()) : template);
            Set<String> methods = methodsByPath.computeIfAbsent(shape, k -> new TreeSet<>());
            entry.getValue().properties().forEach(op -> methods.add(op.getKey().toUpperCase(Locale.ROOT)));
            templateByShape.put(shape, template);
        });
    }

    /** Tag names declared more than once in the top-level {@code tags} list (invalid OpenAPI). */
    List<String> duplicateTags() {
        Set<String> seen = new TreeSet<>();
        Set<String> repeated = new TreeSet<>();
        document.path("tags").forEach(tag -> {
            if (!seen.add(tag.path("name").asString())) {
                repeated.add(tag.path("name").asString());
            }
        });
        return List.copyOf(repeated);
    }

    /** Validation messages the strict parser raises while loading the document as-is. */
    List<String> parseErrors() {
        try {
            OpenApiInteractionValidator.createForInlineApiSpecification(document.toString()).build();
            return List.of();
        } catch (OpenApiInteractionValidator.ApiLoadException e) {
            return e.getParseMessages();
        }
    }

    private OpenApiInteractionValidator validator() {
        if (validator != null) {
            return validator;
        }
        // Known producer defect (see duplicateTags / TCG-755 report): repeated top-level tags stop
        // the parser. Tags carry no schema, so the copy used for body validation keeps the first of each.
        tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) document.deepCopy();
        tools.jackson.databind.node.ArrayNode tags = copy.putArray("tags");
        Set<String> kept = new TreeSet<>();
        document.path("tags").forEach(tag -> {
            if (kept.add(tag.path("name").asString())) {
                tags.add(tag);
            }
        });
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(copy.toString())
                // Bodies are checked against their schemas; request-side rules are out of scope here.
                .withLevelResolver(com.atlassian.oai.validator.report.LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        .withLevel("validation.response.security", ValidationReport.Level.IGNORE)
                        .build())
                .build();
        return validator;
    }

    static OpenApiContract fetch(ApiClient api) {
        ApiClient.Response docs = api.get("/v1/api-docs", null);
        if (docs.status() != 200 || !docs.body().has("paths")) {
            throw new IllegalStateException("/v1/api-docs answered " + docs.status());
        }
        return new OpenApiContract(docs.body());
    }

    /** {@code path} is relative to /api/v1 and uses {p} (or any {name}) for parameters. */
    boolean publishes(String method, String path) {
        return methodsFor(path).contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * Methods published for {@code path}. A literal segment on the consumer side (for example
     * {@code commission.default_rate}) matches a template variable ({@code {key}}) on the producer side.
     */
    Set<String> methodsFor(String path) {
        String[] wanted = shape(path).split("/", -1);
        Set<String> methods = new TreeSet<>();
        methodsByPath.forEach((template, ops) -> {
            String[] have = template.split("/", -1);
            if (have.length != wanted.length) {
                return;
            }
            for (int i = 0; i < have.length; i++) {
                if (!have[i].equals("{p}") && !have[i].equals(wanted[i])) {
                    return;
                }
            }
            methods.addAll(ops);
        });
        return methods;
    }

    /** Schema problems of a real response, as readable lines; empty when it matches the document. */
    List<String> violations(String method, String fullPath, ApiClient.Response response) {
        String path = fullPath.split("\\?", 2)[0];
        SimpleResponse simple = SimpleResponse.Builder.status(response.status())
                .withContentType("application/json")
                .withBody(response.body().toString())
                .build();
        ValidationReport report = validator().validateResponse(path, Request.Method.valueOf(method), simple);
        return report.getMessages().stream()
                .filter(m -> m.getLevel() == ValidationReport.Level.ERROR)
                .map(m -> method + " " + path + " " + response.status() + ": " + m.getKey() + " — " + m.getMessage())
                .toList();
    }

    private static String shape(String path) {
        String noQuery = path.split("\\?", 2)[0];
        String trimmed = noQuery.length() > 1 && noQuery.endsWith("/") ? noQuery.substring(0, noQuery.length() - 1) : noQuery;
        return trimmed.replaceAll("\\{[^}/]*}", "{p}");
    }
}
