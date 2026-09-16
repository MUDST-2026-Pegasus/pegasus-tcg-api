package com.pegasus.pegasustcgapi.model;

/**
 * What kind of thing a catalogue entry is [RQ-4]. A marketplace for cards still
 * has to hold the box the cards came in and the sleeves they go into.
 */
public enum ProductType {

    /** One card. Everything about variants and median price is aimed at this. */
    SINGLE_CARD,
    BOOSTER_PACK,
    BOOSTER_BOX,
    ELITE_TRAINER_BOX,
    STARTER_DECK,
    /** A lot sold as one unit [RQ-4]. */
    BUNDLE,
    /** Sleeves, playmats, binders — the only type that belongs to no card set. */
    ACCESSORY,
    OTHER;

    /** Only a single card has a number within its set. */
    public boolean isSingleCard() {
        return this == SINGLE_CARD;
    }
}
