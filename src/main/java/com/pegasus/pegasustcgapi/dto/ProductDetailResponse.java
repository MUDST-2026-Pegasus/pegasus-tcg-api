package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
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
