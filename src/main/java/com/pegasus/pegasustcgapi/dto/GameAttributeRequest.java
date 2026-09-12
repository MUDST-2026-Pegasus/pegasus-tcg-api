package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.repository.GameAttributeRepository.AttributeFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One field of a game's cards — HP, mana cost, colour [RQ-3].
 *
 * @param attrKey the key products store the value under, so it is lower case and
 *                shaped like an identifier rather than a label
 * @param options the choice list; required for ENUM, refused for every other type
 */
public record GameAttributeRequest(

        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[a-z][a-z0-9_]*$",
                message = "must start with a letter and contain only lower case letters, digits and underscores")
        String attrKey,

        @NotBlank @Size(max = 100)
        String label,

        @NotNull
        AttributeDataType dataType,

        List<@NotBlank @Size(max = 100) String> options,

        /** Absent means it appears in the browse sidebar. */
        Boolean filterable,

        /** Absent means a product may leave it out. */
        Boolean required,

        Short displayOrder) {

    public AttributeFields toFields() {
        return new AttributeFields(attrKey, label, dataType,
                options == null ? List.of() : options,
                filterable == null || filterable,
                required != null && required,
                displayOrder == null ? 0 : displayOrder);
    }
}
