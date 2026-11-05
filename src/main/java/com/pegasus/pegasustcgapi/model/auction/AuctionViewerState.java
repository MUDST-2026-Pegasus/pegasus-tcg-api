package com.pegasus.pegasustcgapi.model.auction;

/**
 * How an auction looks to one buyer who has bid on it. Worked out on every read,
 * never stored [TCG-613].
 */
public enum AuctionViewerState {

    /** Holds the highest bid on a live auction. */
    LEADING,
    /** Bid, but someone has bid more and the auction is still live. */
    OUTBID,
    /** Won (by bid or buy-now) and has not paid yet. */
    WON_AWAITING_PAYMENT,
    /** Won and paid. */
    WON,
    /** The auction ended and this buyer did not get the card. */
    LOST
}
