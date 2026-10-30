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
        String imageUrl,
        List<Long> unitIds,
        /** This line's share of the order part's item discounts (shop code + Pegasus code). */
        BigDecimal discountAllocated,
        BigDecimal sellerDiscountAllocated,
        BigDecimal platformDiscountAllocated,
        /** What returning one of these refunds: the unit price less its share of the discount. */
        BigDecimal refundEstimatePerUnit
) {
    public OrderItemDetailsResponse(
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
            String imageUrl,
            List<Long> unitIds) {
        this(id, listingId, catalogVariantId, quantity, unitPrice, lineTotal, productName, variantLabel, condition,
                gameName, imageKey, imageUrl, unitIds, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, unitPrice);
    }

    public OrderItemDetailsResponse(
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
            List<Long> unitIds) {
        this(id, listingId, catalogVariantId, quantity, unitPrice, lineTotal, productName, variantLabel, condition, gameName, imageKey, null, unitIds);
    }
}
