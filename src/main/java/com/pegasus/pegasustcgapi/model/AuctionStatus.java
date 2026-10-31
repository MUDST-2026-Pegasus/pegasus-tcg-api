package com.pegasus.pegasustcgapi.model;

/**
 * Where an auction stands. Stored as varchar with a CHECK on these exact codes [TCG-613].
 *
 * <pre>
 * LIVE → AWAITING_PAYMENT → SOLD
 *   │          │
 *   │          └→ AWAITING_SELLER_DECISION → OFFERED_TO_SECOND → SOLD
 *   │                    │                          │
 *   │                    └→ UNSOLD (relist or        └→ UNSOLD (declined or
 *   │                       no choice in time)          no answer in time)
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
    /** The winner did not pay. The card stays locked while the seller picks what happens next. */
    AWAITING_SELLER_DECISION,
    /** Offered to the runner-up at their bid; they have 24 hours to answer. */
    OFFERED_TO_SECOND,
    /** Paid. From here it is an ordinary order. */
    SOLD,
    /** Ended without a sale. The card is back in the seller's stock. */
    UNSOLD,
    /** Cancelled by the seller before anyone bid. */
    CANCELLED,
    /** Stopped by an admin. Every bid is cancelled and the card goes back to stock. */
    SUSPENDED
}
