package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.MarketStat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One day's market for a card in a condition — enough to draw a price band. */
public record MarketStatResponse(
        LocalDate statDate,
        BigDecimal medianPrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal p25Price,
        BigDecimal p75Price,
        int activeListingCount,
        int soldQuantity30d,
        int sampleSize) {

    public static MarketStatResponse of(MarketStat s) {
        return new MarketStatResponse(s.statDate(), s.medianPrice(), s.minPrice(), s.maxPrice(),
                s.p25Price(), s.p75Price(), s.activeListingCount(), s.soldQuantity30d(), s.sampleSize());
    }
}
