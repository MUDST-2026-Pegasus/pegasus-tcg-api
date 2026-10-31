package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Preview response for reordering items from a completed sales order.
 */
public record ReorderPreviewResponse(
        long orderId,
        List<ReorderSellerOrderResponse> sellerOrders
) {
    public record ReorderSellerOrderResponse(
            long sellerProfileId,
            String sellerName,
            boolean sellerAvailable,
            boolean vacationMode,
            List<ReorderItemResponse> items
    ) {}

    public record ReorderItemResponse(
            long originalOrderItemId,
            long catalogVariantId,
            String productName,
            String variantLabel,
            String originalCondition,
            BigDecimal originalPrice,
            int originalQuantity,
            String imageUrl,
            ReorderCurrentListingResponse currentListing,
            List<ReorderAlternativeConditionResponse> alternativeConditions,
            ReorderItemStatus status
    ) {}

    public record ReorderCurrentListingResponse(
            long listingId,
            String condition,
            BigDecimal price,
            boolean priceChanged,
            int quantityAvailable,
            boolean isAvailable
    ) {}

    public record ReorderAlternativeConditionResponse(
            long listingId,
            String condition,
            BigDecimal price,
            int quantityAvailable
    ) {}

    public enum ReorderItemStatus {
        AVAILABLE,
        AVAILABLE_PRICE_CHANGED,
        ALTERNATIVE_AVAILABLE,
        OUT_OF_STOCK
    }
}
