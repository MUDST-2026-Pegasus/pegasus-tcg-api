package com.pegasus.pegasustcgapi.model.sale;

import java.time.OffsetDateTime;

/**
 * Where a sale stands. Worked out from the row every time, never stored, so it
 * cannot drift from the dates and counters it is made of.
 */
public enum SaleStatus {

    /** Before {@code starts_at}. Its listings' prices are already locked. */
    SCHEDULED,
    /** Listings it covers sell at the sale price. */
    ACTIVE,
    /** Stopped by its owner or an admin; resuming brings it back. */
    PAUSED,
    /** Past its end, or ended early. Final. */
    ENDED,
    /** A flash sale whose every listing has used up its quota. */
    SOLD_OUT,
    /** A Pegasus campaign whose subsidy reached {@code budget_cap}. */
    BUDGET_EXHAUSTED;

    /**
     * Ended wins over everything, then paused, so an admin who pauses a sale that
     * has not started yet sees it as paused.
     */
    public static SaleStatus of(PriceSale s, OffsetDateTime now, boolean soldOut) {
        if (!now.isBefore(s.effectiveEnd())) return ENDED;
        if (s.pausedAt() != null) return PAUSED;
        if (now.isBefore(s.startsAt())) return SCHEDULED;
        if (s.budgetExhausted()) return BUDGET_EXHAUSTED;
        if (soldOut) return SOLD_OUT;
        return ACTIVE;
    }

    /** Whether a listing it covers may not have its price raised. */
    public boolean locksPrice() {
        return this == SCHEDULED || this == ACTIVE;
    }
}
