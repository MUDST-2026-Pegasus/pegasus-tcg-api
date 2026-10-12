package com.pegasus.pegasustcgapi.exception;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checkout refused because a price moved since the card went into the basket [CR-3].
 *
 * <p>It carries what changed so the buyer is told which line moved and to what,
 * rather than being sent back to the basket to find out for themselves. The
 * checkout transaction has already rolled back by the time this is handled, so
 * re-pinning {@code cart_item.unit_price_at_add} to the new figures happens
 * separately — see {@code CheckoutService.checkout}.
 */
public class CartPriceChangedException extends ConflictException {

    private final transient List<PriceChange> changes;

    public CartPriceChangedException(List<PriceChange> changes) {
        super(ErrorCode.CART_PRICE_CHANGED);
        this.changes = List.copyOf(changes);
    }

    public List<PriceChange> changes() {
        return changes;
    }

    /**
     * @param cartItemId the row to re-pin
     * @param oldPrice   what the buyer last agreed to
     * @param newPrice   what it sells for now
     */
    public record PriceChange(long cartItemId, long listingId, BigDecimal oldPrice, BigDecimal newPrice) {
    }
}
