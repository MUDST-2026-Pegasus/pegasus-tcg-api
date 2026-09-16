package com.pegasus.pegasustcgapi.model;

/** How a card got into someone's collection. */
public enum CollectionSource {

    /** Added by the owner, picked from the shared catalogue. */
    MANUAL,

    /**
     * Added by the platform when an order completed. Nothing writes these yet: it
     * waits for the order module, which is what knows when an order is final.
     */
    PURCHASE
}
