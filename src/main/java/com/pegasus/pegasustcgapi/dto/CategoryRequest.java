package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.CatalogCategoryRepository.CategoryFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A shelf in the catalogue: singles, sealed product, accessories [RQ-3,4].
 *
 * @param gameId   left out, the category applies to every game — which is what an
 *                 Accessory shelf wants. Fixed once the category exists.
 * @param parentId optional; categories nest one inside another
 * @param imageKey optional tile picture, from {@code POST /uploads/presign} with
 *                 purpose CATALOG_IMAGE
 */
public record CategoryRequest(

        Short gameId,

        Integer parentId,

        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "may only contain letters, digits and underscores")
        String code,

        @NotBlank @Size(max = 100)
        String name,

        @Size(max = 120)
        @Pattern(regexp = "^[a-z0-9-]*$", message = "may only contain lower case letters, digits and hyphens")
        String slug,

        Short displayOrder,

        Boolean active,

        @Size(max = 500)
        String imageKey) {

    public CategoryFields toFields() {
        return new CategoryFields(gameId, parentId, code, name, slug,
                displayOrder == null ? 0 : displayOrder,
                active == null || active,
                imageKey);
    }
}
