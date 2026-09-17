package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The market for one printing in one condition on one day [RQ-5], computed from
 * the listings that were active when the nightly job ran.
 *
 * @param sampleSize how many listings the median came from; below the
 *                   {@code pricing.median_min_sample} setting it is noise and no
 *                   price is moved by it
 */
public record MarketStat(
        long catalogVariantId,
        CardCondition condition,
        LocalDate statDate,
        BigDecimal medianPrice,
        BigDecimal avgPrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal p25Price,
        BigDecimal p75Price,
        int activeListingCount,
        int soldQuantity30d,
        int sampleSize,
        OffsetDateTime computedAt) {
}
