package com.pegasus.pegasustcgapi.dto.listing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A snapshot of one seller listing's 30-day sell-through forecast. */
public record StockForecastResponse(
        long listingId,
        String listingName,
        String listingStatus,
        String status,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        int availableStock,
        int completedSales30Days,
        BigDecimal averageSalesPerDay,
        Integer daysRemaining,
        LocalDate estimatedStockoutDate,
        OffsetDateTime updatedAt) {
}
