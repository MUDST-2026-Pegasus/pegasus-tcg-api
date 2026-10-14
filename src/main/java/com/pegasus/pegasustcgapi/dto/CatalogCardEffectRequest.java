package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.CatalogCardEffectRepository.EffectFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One ability, attack or printed rule as an admin enters it. Its place in the
 * list it arrives in is its display order.
 *
 * @param energyCost the energy symbols in printed order, e.g. {@code ["Lightning", "Colorless"]};
 *                   left out means the effect costs nothing
 */
public record CatalogCardEffectRequest(

        @NotBlank
        @Pattern(regexp = "^(ABILITY|ATTACK|RULE)$", message = "must be ABILITY, ATTACK or RULE")
        String effectType,

        @NotBlank @Size(max = 120)
        String name,

        @Size(max = 2000)
        String effectText,

        @PositiveOrZero
        Integer damage,

        @Size(max = 10)
        List<@NotBlank @Size(max = 20) String> energyCost,

        @Size(max = 500)
        String sourceUrl) {

    public EffectFields toFields() {
        return new EffectFields(effectType, name.trim(), blankToNull(effectText), damage,
                energyCost == null ? List.of() : energyCost.stream().map(String::trim).toList(),
                blankToNull(sourceUrl));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
