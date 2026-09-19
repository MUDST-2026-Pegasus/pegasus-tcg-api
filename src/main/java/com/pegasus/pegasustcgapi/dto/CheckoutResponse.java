package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Order outcome after checkout processing.
 */
public record CheckoutResponse(
        long orderId,
        String orderNumber,
        long buyerId,
        String status,
        String currency,
        BigDecimal itemsSubtotal,
        BigDecimal shippingTotal,
        BigDecimal discountTotal,
        BigDecimal grandTotal,
        OffsetDateTime placedAt,
        List<SellerOrderResponse> sellerOrders) {

    public record SellerOrderResponse(
            long id,
            long sellerProfileId,
            String sellerOrderNumber,
            String status,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal grandTotal,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount,
            List<OrderItemResponse> items) {
    }

    public record OrderItemResponse(
            long id,
            Long listingId,
            long catalogVariantId,
            String productName,
            String variantLabel,
            String condition,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            List<Long> unitIds) {
    }
}
