package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductAttributeValidatorTest {

    private final ProductAttributeValidator validator = new ProductAttributeValidator();

    private static GameAttribute attribute(
            String key, AttributeDataType type, boolean required, String... options) {

        return new GameAttribute(1, (short) 1, key, key.toUpperCase(), type,
                List.of(options), true, required, (short) 0);
    }

    private static final List<GameAttribute> POKEMON = List.of(
            attribute("hp", AttributeDataType.NUMBER, true),
            attribute("card_type", AttributeDataType.ENUM, false, "Lightning", "Fire", "Water"),
            attribute("illustrator", AttributeDataType.STRING, false),
            attribute("is_promo", AttributeDataType.BOOLEAN, false),
            attribute("printed_on", AttributeDataType.DATE, false));

    @Test
    @DisplayName("values matching the registry are kept as sent")
    void acceptsDeclaredValues() {
        Map<String, Object> accepted = validator.validate(POKEMON, Map.of(
                "hp", 200,
                "card_type", "Lightning",
                "illustrator", "Mitsuhiro Arita",
                "is_promo", false,
                "printed_on", "2024-10-18"));

        assertThat(accepted).containsEntry("hp", 200).containsEntry("card_type", "Lightning");
        assertThat(accepted).hasSize(5);
    }

    @Test
    @DisplayName("a key this game never declared is refused, not quietly stored")
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of("hpp", 200)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("hpp is not an attribute of this game");
    }

    @Test
    @DisplayName("a required attribute cannot be left out")
    void requiredAttributeIsEnforced() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of("illustrator", "Arita")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PRODUCT_ATTRIBUTES);
    }

    @Test
    @DisplayName("HP as text is a mistake worth catching before it reaches a filter")
    void numberMustBeANumber() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of("hp", "200")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("hp must be a number");
    }

    @Test
    @DisplayName("an ENUM value outside its options is refused, with the options listed")
    void enumValueMustBeAnOption() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of("hp", 90, "card_type", "Psychic")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("card_type must be one of [Lightning, Fire, Water]");
    }

    @Test
    @DisplayName("a date has to look like a date")
    void dateMustParse() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of("hp", 90, "printed_on", "18/10/2024")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("printed_on must be a date");
    }

    @Test
    @DisplayName("a boolean sent as text is refused")
    void booleanMustBeABoolean() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of("hp", 90, "is_promo", "yes")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("is_promo must be true or false");
    }

    @Test
    @DisplayName("every problem is reported at once, so the form is fixed in one pass")
    void reportsAllProblemsTogether() {
        assertThatThrownBy(() -> validator.validate(POKEMON, Map.of(
                "hpp", 200,
                "card_type", "Psychic")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("hpp is not an attribute")
                .hasMessageContaining("card_type must be one of")
                .hasMessageContaining("hp is required");
    }

    @Test
    @DisplayName("an empty field is a value left blank, not a bad value")
    void blankValuesAreDropped() {
        Map<String, Object> accepted = validator.validate(POKEMON, Map.of(
                "hp", 90,
                "illustrator", "   "));

        assertThat(accepted).containsOnlyKeys("hp");
    }

    @Test
    @DisplayName("a game with no registry accepts nothing but empty attributes")
    void gameWithoutAttributes() {
        assertThat(validator.validate(List.of(), Map.of())).isEmpty();
        assertThat(validator.validate(List.of(), null)).isEmpty();

        assertThatThrownBy(() -> validator.validate(List.of(), Map.of("hp", 200)))
                .isInstanceOf(ApiException.class);
    }
}
