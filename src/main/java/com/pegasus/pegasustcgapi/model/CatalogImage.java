package com.pegasus.pegasustcgapi.model;

/**
 * Official art for a catalogue entry [RQ-8]. Photos of the actual card a seller
 * holds are a different table, {@code listing_image}.
 *
 * @param catalogVariantId null means the image is shared by every variant of the
 *                         product, which is the usual case
 * @param imageKey         the object key in storage, never a public URL: the URL
 *                         is signed when someone asks for it and expires
 */
public record CatalogImage(
        long id,
        long catalogProductId,
        Long catalogVariantId,
        String imageKey,
        String altText,
        short sortOrder,
        boolean primary) {
}
