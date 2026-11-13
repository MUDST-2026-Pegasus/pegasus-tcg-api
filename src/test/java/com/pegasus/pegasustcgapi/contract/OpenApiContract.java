package com.pegasus.pegasustcgapi.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.pegasus.pegasustcgapi.support.ApiClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI document the running application publishes at {@code /v1/api-docs}, used two ways:
 * to answer "is METHOD path published?" for the consumer snapshot, and to validate a real
 * response body against the schema documented for that operation and status.
 *
 * <p>springdoc 3 emits OpenAPI 3.1, whose schemas are JSON Schema 2020-12, so bodies are checked
 * with networknt json-schema-validator in that dialect (pinned in build.gradle). OpenAPI 3.0-only
 * validators accept 3.1 documents but skip body validation, which would make this check vacuous;
 * {@code FrontendApiContractIntegrationTest#validatorRejectsABrokenBody} guards against that.
 */
final class OpenApiContract {

    private static final String PREFIX = "/api/v1";
    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    private final JsonNode document;
    /** shape (/api/v1 stripped, params as {p}) → method → operation */
    private final Map<String, Map<String, JsonNode>> operations = new TreeMap<>();
    private final com.fasterxml.jackson.databind.JsonNode components;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    private OpenApiContract(JsonNode document) {
        this.document = document;
        document.path("paths").properties().forEach(entry -> {
            String template = entry.getKey();
            String shape = shape(template.startsWith(PREFIX) ? template.substring(PREFIX.length()) : template);
            Map<String, JsonNode> byMethod = operations.computeIfAbsent(shape, k -> new TreeMap<>());
            entry.getValue().properties().forEach(op -> byMethod.put(op.getKey().toUpperCase(Locale.ROOT), op.getValue()));
        });
        try {
            this.components = JACKSON2.readTree(document.path("components").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OpenApiContract fetch(ApiClient api) {
        ApiClient.Response docs = api.get("/v1/api-docs", null);
        if (docs.status() != 200 || !docs.body().has("paths")) {
            throw new IllegalStateException("/v1/api-docs answered " + docs.status());
        }
        try {
            // Evidence for the TCG-755 report: the exact document the run was checked against.
            Path out = Path.of("build", "tcg755", "openapi.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, docs.body().toPrettyString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new OpenApiContract(docs.body());
    }

    String version() {
        return document.path("openapi").asString();
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
        Set<String> methods = new TreeSet<>();
        matching(path).forEach(byMethod -> methods.addAll(byMethod.keySet()));
        return methods;
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

    /** Media types the document uses for 2xx bodies, with how many operations use each. */
    Map<String, Integer> successMediaTypes() {
        Map<String, Integer> counts = new TreeMap<>();
        operations.values().forEach(byMethod -> byMethod.values().forEach(op -> op.path("responses").properties()
                .forEach(response -> {
                    if (response.getKey().startsWith("2")) {
                        response.getValue().path("content").properties()
                                .forEach(media -> counts.merge(media.getKey(), 1, Integer::sum));
                    }
                })));
        return counts;
    }

    /** True when the published operation documents a JSON-ish schema for a 2xx answer. */
    boolean hasJsonSuccessSchema(String method, String path) {
        for (Map<String, JsonNode> byMethod : matching(path)) {
            JsonNode op = byMethod.get(method.toUpperCase(Locale.ROOT));
            if (op == null) {
                continue;
            }
            for (var response : op.path("responses").properties()) {
                if (response.getKey().startsWith("2") && jsonSchema(response.getValue()).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Schema problems of a real response, as readable lines; empty when it matches the document. */
    List<String> violations(String method, String fullPath, ApiClient.Response response) {
        String path = fullPath.split("\\?", 2)[0];
        String label = method + " " + path + " " + response.status();
        String relative = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
        JsonNode op = null;
        for (Map<String, JsonNode> byMethod : matching(relative)) {
            if (op == null) {
                op = byMethod.get(method.toUpperCase(Locale.ROOT));
            }
        }
        if (op == null) {
            return List.of(label + ": operation is not in /v1/api-docs");
        }
        JsonNode declared = op.path("responses").path(Integer.toString(response.status()));
        if (declared.isMissingNode()) {
            declared = op.path("responses").path("default");
        }
        if (declared.isMissingNode()) {
            // Undocumented status codes are reported by the journey's own status assertion.
            return List.of();
        }
        Optional<JsonNode> schema = jsonSchema(declared);
        if (schema.isEmpty()) {
            return List.of();
        }
        JsonSchema validator = schemaCache.computeIfAbsent(schema.get().toString(), this::compile);
        try {
            List<String> problems = new ArrayList<>();
            validator.validate(JACKSON2.readTree(response.body().toString()))
                    .forEach(message -> problems.add(label + ": " + message.getMessage()));
            return problems;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * DEF-755-05: springdoc documents nullable properties as a plain type (no {@code "null"} in
     * {@code type}), so every legitimately null field reads as a violation. Those are tracked as one
     * defect; anything else (wrong type, missing required field) is a real contract break.
     */
    static boolean isUndocumentedNull(String violation) {
        return violation.contains(": null found, ");
    }

    private JsonSchema compile(String schemaJson) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = JACKSON2.createObjectNode();
            root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            root.set("allOf", JACKSON2.createArrayNode().add(JACKSON2.readTree(schemaJson)));
            // "#/components/schemas/X" refs resolve against this root document.
            root.set("components", components);
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<JsonNode> jsonSchema(JsonNode response) {
        for (var media : response.path("content").properties()) {
            String type = media.getKey();
            if ((type.contains("json") || type.equals("*/*")) && media.getValue().has("schema")) {
                return Optional.of(media.getValue().get("schema"));
            }
        }
        return Optional.empty();
    }

    private List<Map<String, JsonNode>> matching(String path) {
        String[] wanted = shape(path).split("/", -1);
        List<Map<String, JsonNode>> found = new ArrayList<>();
        // Exact shapes first, so /orders/counts wins over /orders/{p} when both exist.
        Map<String, JsonNode> exact = operations.get(shape(path));
        if (exact != null) {
            found.add(exact);
        }
        operations.forEach((template, byMethod) -> {
            if (byMethod == exact) {
                return;
            }
            String[] have = template.split("/", -1);
            if (have.length != wanted.length) {
                return;
            }
            for (int i = 0; i < have.length; i++) {
                if (!have[i].equals("{p}") && !have[i].equals(wanted[i])) {
                    return;
                }
            }
            found.add(byMethod);
        });
        return found;
    }

    private static String shape(String path) {
        String noQuery = path.split("\\?", 2)[0];
        String trimmed = noQuery.length() > 1 && noQuery.endsWith("/") ? noQuery.substring(0, noQuery.length() - 1) : noQuery;
        return trimmed.replaceAll("\\{[^}/]*}", "{p}");
    }
}
