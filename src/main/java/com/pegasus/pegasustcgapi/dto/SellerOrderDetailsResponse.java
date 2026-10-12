package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Detailed seller order representation including line items, shipments, and status history.
 */
public record SellerOrderDetailsResponse(
        long id,
        long salesOrderId,
        long sellerProfileId,
        String sellerOrderNumber,
        String status,
        BigDecimal itemsSubtotal,
        BigDecimal shippingFee,
        BigDecimal discountAmount,
        BigDecimal grandTotal,
        BigDecimal commissionAmount,
        BigDecimal sellerNetAmount,
        OffsetDateTime acceptedAt,
        OffsetDateTime shippedAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime autoCompleteAt,
        OffsetDateTime completedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime createdAt,
        List<OrderItemDetailsResponse> items,
        List<ShipmentResponse> shipments,
        List<OrderStatusHistoryResponse> statusHistory,
        String sellerName
) {
    public SellerOrderDetailsResponse(
            long id,
            long salesOrderId,
            long sellerProfileId,
            String sellerOrderNumber,
            String status,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal discountAmount,
            BigDecimal grandTotal,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount,
            OffsetDateTime acceptedAt,
            OffsetDateTime shippedAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime autoCompleteAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt,
            String cancelReason,
            OffsetDateTime createdAt,
            List<OrderItemDetailsResponse> items,
            List<ShipmentResponse> shipments,
            List<OrderStatusHistoryResponse> statusHistory) {
        this(id, salesOrderId, sellerProfileId, sellerOrderNumber, status,
                itemsSubtotal, shippingFee, discountAmount, grandTotal, commissionAmount, sellerNetAmount,
                acceptedAt, shippedAt, deliveredAt, autoCompleteAt, completedAt, cancelledAt, cancelReason,
                createdAt, items, shipments, statusHistory, null);
    }
}
