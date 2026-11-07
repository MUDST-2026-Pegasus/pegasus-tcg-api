package com.pegasus.pegasustcgapi.dto.listing;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.pegasus.pegasustcgapi.dto.pricing.MarketStatResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A listing's own page.
 * @param listing the public listing and its total sale quota
 * @param photoUrls signed seller photos, primary first
 * @param market latest raw-card market; null for graded cards or when unavailable
 * @param certNumber the next sellable graded card's certificate, if unambiguous
 */
public record PublicListingDetailResponse(
        DetailListing listing,
        List<String> photoUrls,
        @Schema(nullable = true) MarketStatResponse market,
        @Schema(description = "Certificate of the next sellable graded card; null for raw cards, no sellable units or ambiguous certificates",
                nullable = true) String certNumber) {

    /** @param saleQuota total quota for the displayed sale; null without a quota */
    public record DetailListing(@JsonUnwrapped PublicListingResponse listing,
            @Schema(description = "Total quota of the displayed sale: listing override before campaign quota; null without a quota",
                    nullable = true) Integer saleQuota) { }
}
