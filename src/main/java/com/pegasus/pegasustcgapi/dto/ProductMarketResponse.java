package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Latest market snapshot for one printing and condition. */
public record ProductMarketResponse(
        long catalogVariantId,
        CardCondition condition,
        BigDecimal medianPrice,
        LocalDate statDate,
        BigDecimal change30dPercent,
        LastSold lastSold,
        boolean lowSample) {

    public record LastSold(BigDecimal price, OffsetDateTime soldAt) {
    }
}
