package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Checks a product's {@code attributes} against the registry its game declares.
 *
 * <p>This is what keeps a jsonb column from becoming a junk drawer. Without it a
 * typo — {@code hpp} instead of {@code hp} — stores happily and then never
 * matches a filter, and the mistake only surfaces as "why does this card not
 * appear when I search by HP".
 *
 * <p>Validation runs on every write rather than once at creation, because the
 * registry itself can change: an option can be removed from an ENUM long after a
 * product was saved with it.
 */
@Component
public class ProductAttributeValidator {

    /**
     * @param submitted what the caller sent; null is treated as an empty map
     * @return the values to store, with blanks dropped and numbers left as sent
     * @throws ApiException listing every problem at once, so an admin fixes the
     *         form in one pass instead of one field per attempt
     */
    public Map<String, Object> validate(List<GameAttribute> registry, Map<String, Object> submitted) {
        Map<String, Object> values = submitted == null ? Map.of() : submitted;
        Map<String, GameAttribute> declared = new LinkedHashMap<>();
        registry.forEach(attribute -> declared.put(attribute.attrKey(), attribute));

        List<String> problems = new ArrayList<>();
        Map<String, Object> accepted = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            GameAttribute attribute = declared.get(entry.getKey());
            if (attribute == null) {
                problems.add(entry.getKey() + " is not an attribute of this game");
                continue;
            }
            if (isBlank(entry.getValue())) {
                continue;
            }
            String problem = checkValue(attribute, entry.getValue());
            if (problem != null) {
                problems.add(problem);
            } else {
                accepted.put(entry.getKey(), entry.getValue());
            }
        }

        for (GameAttribute attribute : registry) {
            if (attribute.required() && !accepted.containsKey(attribute.attrKey())) {
                problems.add(attribute.attrKey() + " is required for this game");
            }
        }

        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PRODUCT_ATTRIBUTES, String.join("; ", problems));
        }
        return accepted;
    }

    /** @return what is wrong with the value, or null when it fits the declared type. */
    private static String checkValue(GameAttribute attribute, Object value) {
        String key = attribute.attrKey();
        AttributeDataType type = attribute.dataType();

        return switch (type) {
            case NUMBER -> value instanceof Number ? null : key + " must be a number";
            case BOOLEAN -> value instanceof Boolean ? null : key + " must be true or false";
            case STRING -> value instanceof String ? null : key + " must be text";
            case DATE -> isDate(value) ? null : key + " must be a date as yyyy-MM-dd";
            case ENUM -> attribute.options().contains(String.valueOf(value))
                    ? null
                    : key + " must be one of " + attribute.options();
        };
    }

    private static boolean isDate(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException notADate) {
            return false;
        }
    }

    /** An empty string is how a form sends "not filled in", so it is a missing value, not a bad one. */
    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }
}
