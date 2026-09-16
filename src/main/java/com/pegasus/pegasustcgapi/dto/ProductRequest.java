package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository.ProductFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * A card or product being catalogued [RQ-3,4].
 *
 * @param gameId     fixed once the product exists: its attributes were checked
 *                   against this game's registry. Ignored on update.
 * @param cardSetId  optional — an accessory belongs to no release
 * @param attributes values for this game's attribute keys, e.g.
 *                   {@code {"hp": 200, "card_type": "Lightning"}}. Unknown keys
 *                   are refused rather than quietly stored.
 * @param slug       optional; built from the name and card number when absent,
 *                   and read only when the product is created
 */
public record ProductRequest(

        @NotNull
        Short gameId,

        @NotNull @Positive
        Integer categoryId,

        Integer cardSetId,

        @NotNull
        ProductType productType,

        @NotBlank @Size(max = 255)
        String name,

        @Size(max = 255)
        String nameLocal,

        @Size(max = 120)
        @Pattern(regexp = "^[a-z0-9-]*$", message = "may only contain lower case letters, digits and hyphens")
        String slug,

        @Size(max = 32)
        String cardNumber,

        @Size(max = 32)
        String rarityCode,

        String description,

        Map<String, Object> attributes,

        Boolean active) {

    public ProductFields toFields() {
        return new ProductFields(gameId, categoryId, cardSetId, productType, name, nameLocal,
                slug, cardNumber, rarityCode, description,
                attributes == null ? Map.of() : attributes,
                active == null || active);
    }
}
