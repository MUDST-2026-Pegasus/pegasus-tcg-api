package com.pegasus.pegasustcgapi.model;

/** Where a buyer collected a discount code from. */
public enum PromotionClaimSource {

    /** The Pegasus strip on the home page. */
    HOME,
    /** A shop's strip on its store page. */
    STORE,
    /** Typed into "My coupons". */
    WALLET_INPUT,
    /** Typed or picked in the cart. */
    CART
}
