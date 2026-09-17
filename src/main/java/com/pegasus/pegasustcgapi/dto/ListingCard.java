package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;

/**
 * Which card a listing, a stock card or a ledger line is about, in enough detail
 * to draw it without asking the catalogue again.
 *
 * @param officialImageUrl the catalogue's primary art, signed; null when there is none yet
 * @param variantActive    false once an admin retires the printing: existing stock
 *                         stays, but nothing new can be stocked or published on it
 */
public record ListingCard(
        long variantId,
        String sku,
        String variantLabel,
        boolean variantActive,
        long productId,
        String productName,
        String productSlug,
        short gameId,
        String officialImageUrl) {

    public static ListingCard of(CatalogVariant variant, CatalogProduct product, String officialImageUrl) {
        return new ListingCard(variant.id(), variant.sku(), variant.label(), variant.active(),
                product.id(), product.name(), product.slug(), product.gameId(), officialImageUrl);
    }
}
