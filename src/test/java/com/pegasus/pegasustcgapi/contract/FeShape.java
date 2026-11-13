package com.pegasus.pegasustcgapi.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A web-app TypeScript type written down as field → JSON kind, so a response can be checked
 * against what the consumer actually reads — independent of what the OpenAPI document claims.
 *
 * <p>Kinds: {@code number}, {@code string}, {@code boolean}, {@code array}, {@code object}.
 * A trailing {@code |null} allows null; a leading {@code ?} (TS optional) also allows the field
 * to be absent. Unknown extra fields are fine: adding a field is backward compatible.
 */
final class FeShape {

    private final String type;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private FeShape(String type) {
        this.type = type;
    }

    static FeShape of(String type) {
        return new FeShape(type);
    }

    FeShape field(String name, String kind) {
        fields.put(name, kind);
        return this;
    }

    void check(JsonNode node) {
        assertThat(problems(node)).as("%s vs response %s", type, node).isEmpty();
    }

    List<String> problems(JsonNode node) {
        List<String> problems = new ArrayList<>();
        if (node == null || !node.isObject()) {
            problems.add(type + ": expected an object");
            return problems;
        }
        fields.forEach((name, spec) -> {
            boolean optional = spec.startsWith("?");
            String kinds = optional ? spec.substring(1) : spec;
            boolean nullable = kinds.endsWith("|null");
            String kind = nullable ? kinds.substring(0, kinds.length() - 5) : kinds;
            if (!node.has(name)) {
                if (!optional) {
                    problems.add(type + "." + name + " is missing (FE declares " + spec + ")");
                }
                return;
            }
            JsonNode value = node.get(name);
            if (value.isNull()) {
                if (!nullable && !optional) {
                    problems.add(type + "." + name + " is null (FE declares " + spec + ")");
                }
                return;
            }
            boolean ok = switch (kind) {
                case "number" -> value.isNumber();
                case "string" -> value.isString();
                case "boolean" -> value.isBoolean();
                case "array" -> value.isArray();
                case "object" -> value.isObject();
                default -> throw new IllegalArgumentException(kind);
            };
            if (!ok) {
                problems.add(type + "." + name + " is " + value.getNodeType() + " (FE declares " + spec + ")");
            }
        });
        return problems;
    }
}
