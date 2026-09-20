package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Line item details within an order.
 */
public record OrderItemDetailsResponse(
        long id,
        Long listingId,
        long catalogVariantId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String productName,
        String variantLabel,
        String condition,
        String gameName,
        String imageKey,
        List<Long> unitIds
) {
}
