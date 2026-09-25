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
        OffsetDateTime updatedAt,
        String productName,
        String variantLabel,
        String sellerName,
        String imageUrl) {

    public CartItemResponse(
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
        this(id, cartId, listingId, quantity, unitPriceAtAdd, currentPrice, priceChanged, purchasable,
                quantityAvailable, sellerProfileId, catalogVariantId, condition, currency, addedAt, updatedAt,
                null, null, null, null);
    }
}
