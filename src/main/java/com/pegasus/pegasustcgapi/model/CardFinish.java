package com.pegasus.pegasustcgapi.model;

/**
 * How a print is finished. Part of what makes two rows of the same card different
 * money, so it is part of a variant's identity [RQ-5].
 */
public enum CardFinish {

    NORMAL,
    HOLO,
    REVERSE_HOLO,
    FOIL,
    ETCHED,
    TEXTURED,
    /** Sealed product and accessories have no finish. */
    NOT_APPLICABLE
}
