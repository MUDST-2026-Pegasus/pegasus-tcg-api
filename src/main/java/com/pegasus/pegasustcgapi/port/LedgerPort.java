package com.pegasus.pegasustcgapi.port;

import java.math.BigDecimal;

/**
 * Platform ledger and commission port stub for checkout and order completion.
 *
 * <p>The finance module owns the answer; the order module only asks, and freezes
 * what it is told into {@code seller_order}. The quote carries the rate as well as
 * the amount because {@code commission_rate_percent} is a snapshot: changing the
 * rule later must never rewrite an order that has already been placed [RQ-14].
 */
public interface LedgerPort {

    /**
     * @param itemsSubtotal the sub-order's item total, excluding shipping — a
     *                      seller is not charged commission on postage
     */
    CommissionQuote quoteCommission(long sellerProfileId, BigDecimal itemsSubtotal);

    /** Hands the seller's share out of escrow once their sub-order completes. */
    void release(long sellerOrderId);

    /**
     * @param ratePercent the rate applied, e.g. {@code 5.0} for five percent
     * @param amount      what that rate comes to on this sub-order
     */
    record CommissionQuote(BigDecimal ratePercent, BigDecimal amount) {

        public static final CommissionQuote NONE = new CommissionQuote(BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
