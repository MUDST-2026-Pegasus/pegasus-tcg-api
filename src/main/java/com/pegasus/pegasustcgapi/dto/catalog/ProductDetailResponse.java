package com.pegasus.pegasustcgapi.dto.catalog;

import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A catalogue entry as a card page needs it: the concept, every printing of it,
 * and the art.
 *
 * <p>The variants come with it rather than behind another request, because a card
 * page cannot show a price without them — the price belongs to a listing, and a
 * listing points at a variant [RQ-5].
 */
public record ProductDetailResponse(
        CatalogProduct product,
        List<CatalogVariant> variants,
        List<CatalogImageResponse> images,
        Auctions auctions) {
    public record Auctions(int openCount, BigDecimal lowestCurrentPrice, OffsetDateTime nearestEndAt) {}

    public ProductDetailResponse(CatalogProduct product, List<CatalogVariant> variants,
            List<CatalogImageResponse> images) {
        this(product, variants, images, new Auctions(0, null, null));
    }
}
