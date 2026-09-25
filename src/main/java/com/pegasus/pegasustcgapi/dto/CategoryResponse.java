package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogCategory;

/**
 * A category as the public catalogue shows it.
 *
 * @param imageUrl a short-lived read URL for the tile picture; null when there is none
 */
public record CategoryResponse(
        int id,
        Short gameId,
        Integer parentId,
        String code,
        String name,
        String slug,
        short displayOrder,
        String imageUrl) {

    public static CategoryResponse of(CatalogCategory category, String imageUrl) {
        return new CategoryResponse(category.id(), category.gameId(), category.parentId(),
                category.code(), category.name(), category.slug(), category.displayOrder(), imageUrl);
    }
}
