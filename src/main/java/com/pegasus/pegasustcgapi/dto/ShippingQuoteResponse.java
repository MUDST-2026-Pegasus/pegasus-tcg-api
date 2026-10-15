package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Active delivery choices and calculated fees for one seller's selected cart items")
public record ShippingQuoteResponse(
        long sellerProfileId,
        String sellerName,
        @Schema(description = "Number of physical cards, including quantities, rather than cart rows") int itemCount,
        BigDecimal itemsSubtotal,
        @Schema(description = "First active option in display order; null when the seller cannot ship", nullable = true) Long defaultOptionId,
        List<ShippingQuoteOptionResponse> options) {
}
