package com.pegasus.pegasustcgapi.model;

/** Why stock moved. Inbound types must carry a unit cost; the table's CHECK says so. */
public enum MovementType {

    /** The first cards of a printing and condition this seller ever stocked. */
    INITIAL_STOCK,
    /** More of a printing and condition the seller has stocked before. */
    RESTOCK,
    /** Left with a parcel. Written when the order ships, not when it is placed. */
    SALE,
    /** A sale undone after it was booked. */
    CANCEL_RESTOCK,
    /** Came back from a buyer [after-sales]. */
    RETURN_RESTOCK,
    /** A correction, e.g. a card entered by mistake. */
    ADJUSTMENT,
    /** Lost or damaged. */
    LOSS
}
