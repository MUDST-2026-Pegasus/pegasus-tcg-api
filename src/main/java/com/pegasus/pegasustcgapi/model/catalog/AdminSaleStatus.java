package com.pegasus.pegasustcgapi.model.catalog;

/** An admin's filter on who sells a card, in the catalogue back office. */
public enum AdminSaleStatus {
    ALL,
    /** A buyer could take one now. */
    HAS_SELLER,
    /** Nobody a buyer could take one from now, whatever is listed. */
    NO_SELLER,
    /** An admin has suspended at least one listing of it. */
    HAS_BLOCKED
}
