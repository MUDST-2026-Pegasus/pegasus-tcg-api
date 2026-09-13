package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;

/**
 * What a scanned barcode or a pasted SKU resolves to.
 *
 * <p>Carries the card's name and slug alongside the printing, because whoever
 * looked the code up needs to show a person what they just matched — "EN / Foil"
 * on its own tells nobody which card it is.
 */
public record VariantLookupResponse(
        CatalogVariant variant,
        long productId,
        String productName,
        String productSlug,
        String variantLabel) {

    public static VariantLookupResponse of(CatalogVariant variant, CatalogProduct product) {
        return new VariantLookupResponse(variant, product.id(), product.name(), product.slug(),
                variant.label());
    }
}
