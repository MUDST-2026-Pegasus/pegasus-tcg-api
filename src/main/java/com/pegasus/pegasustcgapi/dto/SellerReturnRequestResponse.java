package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "Seller return request response with sub-order, buyer summary, and item snapshots")
public record SellerReturnRequestResponse(
        @Schema(description = "Return request ID", example = "1042")
        long id,

        @Schema(description = "Seller order ID", example = "200")
        long sellerOrderId,

        @Schema(description = "Seller order number", example = "ORD-10198-S1")
        String sellerOrderNumber,

        @Schema(description = "Buyer public summary (strictly username and displayName only)")
        BuyerSummary buyer,

        @Schema(description = "Sub-order grand total", example = "3420.00")
        BigDecimal sellerOrderGrandTotal,

        @Schema(description = "When the sub-order was delivered")
        OffsetDateTime deliveredAt,

        @Schema(description = "When escrow auto-completes")
        OffsetDateTime autoCompleteAt,

        @Schema(description = "Reason code for the return", example = "NOT_AS_DESCRIBED")
        String reasonCode,

        @Schema(description = "Buyer's explanation text", example = "Card is damaged")
        String description,

        @Schema(description = "Current return status", example = "REQUESTED")
        String status,

        @Schema(description = "Resolution type (e.g. FULL_REFUND, PARTIAL_REFUND)", example = "FULL_REFUND")
        String resolution,

        @Schema(description = "Refund amount approved", example = "3420.00")
        BigDecimal refundAmount,

        @Schema(description = "Evidence image storage keys")
        List<String> evidenceKeys,

        @Schema(description = "Tracking number for return shipment", example = "KEX 7741 0298 3315 TH")
        String returnTrackingNumber,

        @Schema(description = "When return was requested")
        OffsetDateTime requestedAt,

        @Schema(description = "When seller reviewed return")
        OffsetDateTime reviewedAt,

        @Schema(description = "When seller received returned item")
        OffsetDateTime receivedAt,

        @Schema(description = "When return was closed")
        OffsetDateTime closedAt,

        @Schema(description = "Items requested for return")
        List<SellerReturnItem> items
) {
    @Schema(description = "Sanitized buyer profile containing only public identifiers")
    public record BuyerSummary(
            @Schema(description = "Buyer username", example = "kandypop")
            String username,

            @Schema(description = "Buyer display name", example = "Kandy Pop")
            String displayName
    ) {}

    @Schema(description = "Snapshot of order item requested for return")
    public record SellerReturnItem(
            @Schema(description = "Return item ID", example = "1")
            long returnItemId,

            @Schema(description = "Order item ID", example = "10")
            long orderItemId,

            @Schema(description = "Card product name snapshot", example = "Luffy Gear 5")
            String cardName,

            @Schema(description = "Card variant label snapshot", example = "Parallel")
            String variantLabel,

            @Schema(description = "Condition code snapshot", example = "NM")
            String conditionCode,

            @Schema(description = "Presigned image URL for item snapshot thumbnail")
            String imageUrl,

            @Schema(description = "Unit price of the item", example = "2900.00")
            BigDecimal unitPrice,

            @Schema(description = "Quantity requested to be returned", example = "1")
            int quantityReturned,

            @Schema(description = "Quantity originally ordered", example = "1")
            int quantityOrdered,

            @Schema(description = "Whether item is to be restocked into inventory", example = "true")
            boolean restock
    ) {}
}
