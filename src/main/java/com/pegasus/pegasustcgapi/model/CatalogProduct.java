package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The card as a concept — "Pikachu ex from Terastal Festival, number 025/187".
 * True whether anybody is selling one or not, which is why it belongs to the
 * platform rather than to a seller [RQ-3].
 *
 * <p>It is not the thing that is traded: prices, stock and market statistics all
 * hang off {@link CatalogVariant}, because a Japanese foil and an English plain
 * print of this same card are different money.
 *
 * @param attributes the values for this game's {@link GameAttribute} keys, e.g.
 *                   {@code {"hp": 200, "card_type": "Lightning"}}
 */
public record CatalogProduct(
        long id,
        short gameId,
        int categoryId,
        Integer cardSetId,
        ProductType productType,
        String name,
        String nameLocal,
        String slug,
        String cardNumber,
        String rarityCode,
        String description,
        Map<String, Object> attributes,
        boolean active,
        Long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public CatalogProduct {
        attributes = attributes == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(attributes));
    }
}
