package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Detailed sales order representation for buyers, including all sub-orders from individual sellers.
 */
public record OrderDetailsResponse(
        long id,
        String orderNumber,
        long buyerId,
        String status,
        String currency,
        BigDecimal itemsSubtotal,
        BigDecimal shippingTotal,
        BigDecimal discountTotal,
        BigDecimal grandTotal,
        Long shippingAddressId,
        String shippingAddressSnapshot,
        String buyerNote,
        OffsetDateTime placedAt,
        OffsetDateTime paidAt,
        OffsetDateTime completedAt,
        OffsetDateTime cancelledAt,
        List<SellerOrderDetailsResponse> sellerOrders,
        /** A bank-transfer slip is waiting for admin review; the buyer can neither pay again nor cancel. */
        boolean paymentUnderReview,
        /** Last moment the buyer may cancel a paid order; null until the order is paid. */
        OffsetDateTime cancellableUntil
) {
}
