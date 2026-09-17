package com.pegasus.pegasustcgapi.dto;

import java.util.List;

/**
 * A listing's own page.
 *
 * @param photoUrls the seller's photos, signed, primary first. URLs only: an
 *                  object key is something a seller sends, never something a
 *                  buyer needs
 * @param market    the latest market for this card in this condition; null when
 *                  there has never been one
 */
public record PublicListingDetailResponse(
        PublicListingResponse listing,
        List<String> photoUrls,
        MarketStatResponse market) {
}
