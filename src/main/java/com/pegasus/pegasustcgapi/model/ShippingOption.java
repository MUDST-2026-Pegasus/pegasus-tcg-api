package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;

/**
 * A delivery choice the seller offers and prices themselves [RQ-12].
 *
 * @param freeThreshold order value at or above which the fee is waived; null means never
 */
public record ShippingOption(
        long id,
        long sellerProfileId,
        String name,
        String carrierCode,
        BigDecimal baseFee,
        BigDecimal perItemFee,
        BigDecimal freeThreshold,
        Short estDaysMin,
        Short estDaysMax,
        boolean active,
        short displayOrder) {

    /** @param itemCount cards in this seller's part of the order, not the whole basket */
    public BigDecimal feeFor(int itemCount, BigDecimal itemsSubtotal) {
        if (freeThreshold != null && itemsSubtotal.compareTo(freeThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        // The first card is covered by the base fee; only the rest are charged per item.
        int extraItems = Math.max(0, itemCount - 1);
        return baseFee.add(perItemFee.multiply(BigDecimal.valueOf(extraItems)));
    }
}
