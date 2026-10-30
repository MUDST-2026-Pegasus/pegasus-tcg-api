package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import java.math.BigDecimal;

/**
 * A discount code on an order, as receipts and order pages show it [TCG-570].
 *
 * @param sellerProfileId the shop for a shop code; null for a Pegasus code
 * @param amount          what it took off; on one shop's part, that shop's share
 * @param status          RESERVED until the order is paid, then CONSUMED
 */
public record AppliedPromotion(String code, OwnerType ownerType, Long sellerProfileId, BigDecimal amount,
        String status) {
}
