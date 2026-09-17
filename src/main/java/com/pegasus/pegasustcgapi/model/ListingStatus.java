package com.pegasus.pegasustcgapi.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a listing stands. Stored as {@code varchar} with a CHECK, like every
 * status in this schema, so a new value is an ordinary migration.
 *
 * <p>Two of the moves are nobody's request. SOLD_OUT and back belongs to the
 * database: the quantity trigger flips it as the last card goes and as one comes
 * back. BLOCKED belongs to an admin.
 */
public enum ListingStatus {

    /** Being prepared; buyers never see it. */
    DRAFT,
    /** On sale. */
    ACTIVE,
    /** Taken off sale for a while by the seller. */
    PAUSED,
    /** Nothing left to sell. Goes back to ACTIVE by itself when a card comes back. */
    SOLD_OUT,
    /** Closed by the seller for good; its cards went back into their hands. */
    DELISTED,
    /** Suspended by an admin. */
    BLOCKED;

    /**
     * Where a seller may move a listing from here. SOLD_OUT and BLOCKED never
     * appear: nobody asks for those, they happen.
     */
    public Set<ListingStatus> sellerTargets() {
        return switch (this) {
            case DRAFT, PAUSED -> EnumSet.of(ACTIVE, DELISTED);
            case ACTIVE, SOLD_OUT -> EnumSet.of(PAUSED, DELISTED);
            case DELISTED, BLOCKED -> EnumSet.noneOf(ListingStatus.class);
        };
    }

    /** Whether the seller may still change its price or put cards on it. */
    public boolean open() {
        return this != DELISTED && this != BLOCKED;
    }

    /** Whether a buyer following a link gets the page. A sold-out listing still says so. */
    public boolean publiclyVisible() {
        return this == ACTIVE || this == SOLD_OUT;
    }
}
