package com.pegasus.pegasustcgapi.model.auction;

/**
 * Where an auction stands. Stored as varchar with a CHECK on these exact codes [TCG-613].
 *
 * <pre>
 * LIVE → AWAITING_PAYMENT → SOLD
 *   │          │
 *   │          └→ UNSOLD  (winner did not pay in time; the card goes back to stock)
 *   ├→ UNSOLD     (no bids when time ran out)
 *   ├→ CANCELLED  (seller, only before the first bid)
 *   └→ SUSPENDED  (admin, from any open state)
 * </pre>
 */
public enum AuctionStatus {

    /** Open for bids. Buy-now works only until the first bid. */
    LIVE,
    /** Ended with a winner, or bought with buy-now; the buyer has 24 hours to pay. */
    AWAITING_PAYMENT,
    /** Paid. From here it is an ordinary order. */
    SOLD,
    /** Ended without a sale: no bids, or the winner did not pay. The card is back in the seller's stock. */
    UNSOLD,
    /** Cancelled by the seller before anyone bid. */
    CANCELLED,
    /** Stopped by an admin. Every bid is cancelled and the card goes back to stock. */
    SUSPENDED
}
