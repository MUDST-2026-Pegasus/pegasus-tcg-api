package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of a listing's price history [RQ-5, RQ-6].
 *
 * @param oldPrice  null on the row written when the listing was created
 * @param changedBy the person who changed it; null when the nightly job did
 * @param reason    e.g. {@code median 1358.00 × (1 − 5%)} for an automatic change
 */
public record ListingPriceChange(
        long id,
        long listingId,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        PricingMode pricingMode,
        Long changedBy,
        String reason,
        OffsetDateTime createdAt) {

    /** The question this table exists to answer when a seller says "I never lowered that price". */
    public boolean bySystem() {
        return changedBy == null;
    }
}
