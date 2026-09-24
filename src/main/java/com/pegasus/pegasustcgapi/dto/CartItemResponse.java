package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Cart item representation including current pricing status.
 */
public record CartItemResponse(
        long id,
        long cartId,
        long listingId,
        int quantity,
        BigDecimal unitPriceAtAdd,
        BigDecimal currentPrice,
        boolean priceChanged,
        boolean purchasable,
        Integer quantityAvailable,
        Long sellerProfileId,
        Long catalogVariantId,
        CardCondition condition,
        String currency,
        OffsetDateTime addedAt,
        OffsetDateTime updatedAt) {
}
