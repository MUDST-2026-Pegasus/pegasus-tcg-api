package com.pegasus.pegasustcgapi.dto.auction;

/**
 * Bid eligibility states for a user on an auction [TCG-616].
 */
public enum BidEligibility {
    /** Caller is not logged in. */
    UNAUTHENTICATED,
    /** Caller has no default or registered shipping address. */
    NO_SHIPPING_ADDRESS,
    /** Caller email address has not been verified. */
    EMAIL_NOT_VERIFIED,
    /** Caller has an active bidding ban or 3+ unpaid strikes. */
    BIDDING_PENALIZED,
    /** Caller is the seller of this auction (cannot bid on own card). */
    OWN_AUCTION,
    /** Caller satisfies all requirements to place bids. */
    ELIGIBLE
}
