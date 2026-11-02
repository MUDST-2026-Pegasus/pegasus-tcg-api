package com.pegasus.pegasustcgapi.dto.catalog;

import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
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
        List<CatalogImageResponse> images) {
}
