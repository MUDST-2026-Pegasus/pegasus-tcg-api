package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * An item line inside a shopping cart.
 */
public record CartItem(
        long id,
        long cartId,
        long listingId,
        int quantity,
        BigDecimal unitPriceAtAdd,
        OffsetDateTime addedAt,
        OffsetDateTime updatedAt) {
}
