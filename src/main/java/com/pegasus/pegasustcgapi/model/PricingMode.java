package com.pegasus.pegasustcgapi.model;

/** How a listing's price is decided. */
public enum PricingMode {

    /** The seller types the price [RQ-6]. The nightly job never touches these. */
    MANUAL,
    /**
     * The nightly job sets it to the median of every active listing of the same
     * printing in the same condition, moved by the seller's offset and kept inside
     * their floor and ceiling [RQ-5].
     */
    AUTO_MEDIAN
}
