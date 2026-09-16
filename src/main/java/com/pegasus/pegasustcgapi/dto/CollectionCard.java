package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;

/**
 * Which card a collection row is, in enough detail to draw it without asking the
 * catalogue again.
 *
 * @param officialImageUrl the catalogue's primary art, signed; null when the card
 *                         has none yet
 * @param variantActive    false once an admin retires the printing. The row stays,
 *                         but a client may want to say it is no longer offered.
 */
public record CollectionCard(
        long variantId,
        String sku,
        String variantLabel,
        boolean variantActive,
        long productId,
        String productName,
        String productSlug,
        short gameId,
        String officialImageUrl) {

    public static CollectionCard of(CatalogVariant variant, CatalogProduct product, String officialImageUrl) {
        return new CollectionCard(variant.id(), variant.sku(), variant.label(), variant.active(),
                product.id(), product.name(), product.slug(), product.gameId(), officialImageUrl);
    }
}
