package com.pegasus.pegasustcgapi.model;

/**
 * The state a card is in. The same six codes are used by listings, stock, market
 * statistics and collections, and every one of those tables checks them with the
 * same CHECK constraint.
 */
public enum CardCondition {

    /** Near Mint. */
    NM,
    /** Lightly Played. */
    LP,
    /** Moderately Played. */
    MP,
    /** Heavily Played. */
    HP,
    /** Damaged. */
    DMG,
    /** Sealed product that has never been opened. */
    SEALED
}
