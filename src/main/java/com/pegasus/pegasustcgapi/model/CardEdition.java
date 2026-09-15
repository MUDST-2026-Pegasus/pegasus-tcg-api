package com.pegasus.pegasustcgapi.model;

/** Which printing run a variant comes from; a first edition is its own market. */
public enum CardEdition {

    FIRST_EDITION,
    UNLIMITED,
    PROMO,
    /** Sealed product and accessories have no edition. */
    NOT_APPLICABLE
}
