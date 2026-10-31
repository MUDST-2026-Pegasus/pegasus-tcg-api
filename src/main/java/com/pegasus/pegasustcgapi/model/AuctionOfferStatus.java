package com.pegasus.pegasustcgapi.model;

/** The offer a seller sends the runner-up after the winner does not pay [TCG-613]. */
public enum AuctionOfferStatus {

    /** Sent; the buyer has 24 hours to answer. */
    PENDING,
    /** Taken. The buyer now pays through the auction checkout. */
    ACCEPTED,
    /** Turned down. The card goes back to stock. */
    DECLINED,
    /** Not answered in time. The card goes back to stock. */
    EXPIRED
}
