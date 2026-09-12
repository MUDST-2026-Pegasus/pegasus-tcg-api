package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogImage;

/**
 * An image as a client can use it.
 *
 * @param imageKey what the database holds, and what an admin screen sends back
 * @param url      a signed, short-lived link to the object. Not stored anywhere:
 *                 it expires, and the bucket is private, so it is minted per read.
 */
public record CatalogImageResponse(
        long id,
        Long catalogVariantId,
        String imageKey,
        String url,
        String altText,
        short sortOrder,
        boolean primary) {

    public static CatalogImageResponse of(CatalogImage image, String url) {
        return new CatalogImageResponse(image.id(), image.catalogVariantId(), image.imageKey(),
                url, image.altText(), image.sortOrder(), image.primary());
    }
}
