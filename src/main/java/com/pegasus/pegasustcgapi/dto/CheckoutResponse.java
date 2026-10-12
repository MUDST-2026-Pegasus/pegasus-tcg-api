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
        List<SellerOrderResponse> sellerOrders,
        boolean replayed) {

    public CheckoutResponse(
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
        this(orderId, orderNumber, buyerId, status, currency, itemsSubtotal, shippingTotal, discountTotal, grandTotal, placedAt, sellerOrders, false);
    }

    public CheckoutResponse withReplayed(boolean replayed) {
        return new CheckoutResponse(
                orderId,
                orderNumber,
                buyerId,
                status,
                currency,
                itemsSubtotal,
                shippingTotal,
                discountTotal,
                grandTotal,
                placedAt,
                sellerOrders,
                replayed);
    }

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
            List<OrderItemResponse> items,
            String sellerName) {

        public SellerOrderResponse(
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
            this(id, sellerProfileId, sellerOrderNumber, status, itemsSubtotal, shippingFee,
                    grandTotal, commissionAmount, sellerNetAmount, items, null);
        }
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
            List<Long> unitIds,
            String imageUrl) {

        public OrderItemResponse(
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
            this(id, listingId, catalogVariantId, productName, variantLabel, condition, quantity, unitPrice, lineTotal, unitIds, null);
        }
    }
}
