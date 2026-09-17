package com.pegasus.pegasustcgapi.model;

/**
 * Where one physical card is in its life.
 *
 * <pre>
 * IN_STOCK ⇄ LISTED → RESERVED → SOLD → RETURNED → IN_STOCK / LISTED
 *               ↑         │                  │
 *               └─────────┘ (released)       └→ WRITTEN_OFF
 * </pre>
 *
 * IN_STOCK, LISTED and RETURNED may also be written off directly.
 */
public enum ListingUnitStatus {

    /** With the seller, on no listing. Its {@code listing_id} is null. */
    IN_STOCK,
    /** On a listing and for sale; counted in {@code quantity_available}. */
    LISTED,
    /** Held by an order that has not shipped; counted in {@code quantity_reserved}. */
    RESERVED,
    /** Left with a parcel. */
    SOLD,
    /** Came back from a buyer and waits for the seller to inspect it. */
    RETURNED,
    /** Lost or damaged, and out of stock for good. */
    WRITTEN_OFF;

    /**
     * Whether the card is physically with the seller. These are exactly the cards
     * the stock ledger and the average cost count, so the three always reconcile.
     */
    public boolean onHand() {
        return this == IN_STOCK || this == LISTED || this == RESERVED || this == RETURNED;
    }

    /** Whether the seller may move it, take it off a listing or write it off: nobody is buying it and it has not left. */
    public boolean sellerMovable() {
        return this == IN_STOCK || this == LISTED || this == RETURNED;
    }
}
