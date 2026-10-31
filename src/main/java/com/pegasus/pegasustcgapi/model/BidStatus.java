package com.pegasus.pegasustcgapi.model;

/** One bid. A buyer can never withdraw a bid; only an admin suspending the auction cancels it [TCG-613]. */
public enum BidStatus {

    /** Counts towards the auction. */
    ACTIVE,
    /** Voided because the auction was suspended. */
    CANCELLED
}
