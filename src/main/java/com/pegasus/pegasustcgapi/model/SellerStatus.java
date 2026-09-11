package com.pegasus.pegasustcgapi.model;

/**
 * Where a profile stands on the selling side. Stored as {@code varchar} with a
 * CHECK rather than a native enum, so adding a value later is an ordinary
 * migration instead of an irreversible {@code ALTER TYPE}.
 */
public enum SellerStatus {

    /** Buyer only — never applied. */
    NOT_APPLIED,
    /** Documents submitted, waiting on an admin. */
    PENDING,
    /** Holds the SELLER role and may publish listings [RQ-1]. */
    VERIFIED,
    REJECTED,
    /** Blocked by an admin; listings stay hidden until it is lifted. */
    SUSPENDED
}
