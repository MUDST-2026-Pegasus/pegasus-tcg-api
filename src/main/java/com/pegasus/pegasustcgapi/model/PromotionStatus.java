package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * Where a discount code stands. Worked out from the row every time, never stored,
 * so it cannot drift from the dates and counters it is made of.
 */
public enum PromotionStatus {

    /** Before {@code starts_at}. */
    SCHEDULED,
    /** Can be applied. */
    ACTIVE,
    /** Stopped by its owner or an admin; resuming brings it back. */
    PAUSED,
    /** Every use in {@code usage_limit_total} is held or spent. */
    USED_UP,
    /** Past {@code ends_at}. Final: nothing else matters once a code has ended. */
    EXPIRED;

    /**
     * Expired wins over everything, then paused, so an admin who pauses a code
     * that has not started yet sees it as paused.
     */
    public static PromotionStatus of(Promotion p, OffsetDateTime now) {
        if (!now.isBefore(p.endsAt())) return EXPIRED;
        if (p.pausedAt() != null) return PAUSED;
        if (now.isBefore(p.startsAt())) return SCHEDULED;
        if (p.usageLimitTotal() != null && p.usedCount() >= p.usageLimitTotal()) return USED_UP;
        return ACTIVE;
    }
}
