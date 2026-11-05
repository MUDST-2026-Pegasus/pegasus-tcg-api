package com.pegasus.pegasustcgapi.dto.cart;

import com.pegasus.pegasustcgapi.dto.sale.SalePricing;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Cart item representation including current pricing status.
 *
 * @param currentPrice what one card sells for now, sale included [TCG-679]; a sale
 *                     starting, ending or running out moves it, and
 *                     {@code priceChanged} says so
 * @param pricing      the price as every page shows it; null when the listing is gone
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
        String imageUrl,
        Long productId,
        String sellerUsername,
        SalePricing pricing) {

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
            OffsetDateTime updatedAt,
            String productName,
            String variantLabel,
            String sellerName,
            String imageUrl,
            Long productId,
            String sellerUsername) {
        this(id, cartId, listingId, quantity, unitPriceAtAdd, currentPrice, priceChanged, purchasable,
                quantityAvailable, sellerProfileId, catalogVariantId, condition, currency, addedAt, updatedAt,
                productName, variantLabel, sellerName, imageUrl, productId, sellerUsername, null);
    }

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
                null, null, null, null, null, null);
    }
}
