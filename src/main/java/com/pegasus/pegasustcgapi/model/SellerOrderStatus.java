package com.pegasus.pegasustcgapi.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Where one seller's part of an order stands [CR-7]. Stored as {@code varchar}
 * with a CHECK, like every status in this schema.
 *
 * <p>The main line is PENDING_PAYMENT → PAID → PREPARING → SHIPPED → DELIVERED →
 * COMPLETED, with CANCELLED reachable from anywhere before the parcel goes out.
 * Keeping the allowed moves here rather than as string lists at each call site is
 * what lets a transition be rejected in one place, the way {@link ListingStatus}
 * does for listings.
 */
public enum SellerOrderStatus {

    /** The order exists and its cards are held, but no money has arrived. */
    PENDING_PAYMENT,
    /** Paid for; escrow holds the money until the buyer has the cards. */
    PAID,
    /** The seller has accepted it and is packing. */
    PREPARING,
    /** Handed to the carrier, with a tracking number the seller typed. */
    SHIPPED,
    /** Known to have arrived. */
    DELIVERED,
    /** Finished: escrow released and the cards are in the buyer's collection. */
    COMPLETED,
    /** Called off before it shipped; the cards went back on sale. */
    CANCELLED,
    /** After-sales owns these three; the order module never drives them. */
    RETURN_REQUESTED,
    RETURNED,
    REFUNDED;

    /** Cancelling is only possible while the parcel is still in the seller's hands. */
    public static final Set<SellerOrderStatus> CANCELLABLE =
            EnumSet.of(PENDING_PAYMENT, PAID, PREPARING);

    /** What the buyer may confirm receipt of, and what the escrow sweep may close. */
    public static final Set<SellerOrderStatus> CONFIRMABLE = EnumSet.of(SHIPPED, DELIVERED);

    /** The seller may post it from either of these. */
    public static final Set<SellerOrderStatus> SHIPPABLE = EnumSet.of(PAID, PREPARING);

    /**
     * Where this status may move next.
     *
     * <p>The after-sales statuses have no targets here on purpose: returns and
     * refunds are another module's state machine, and inventing its edges would
     * let this module drive transitions it does not own.
     */
    public Set<SellerOrderStatus> targets() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAID, CANCELLED);
            case PAID -> EnumSet.of(PREPARING, SHIPPED, CANCELLED);
            case PREPARING -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED, COMPLETED);
            case DELIVERED -> EnumSet.of(COMPLETED);
            case COMPLETED, CANCELLED, RETURN_REQUESTED, RETURNED, REFUNDED ->
                    EnumSet.noneOf(SellerOrderStatus.class);
        };
    }

    public boolean canMoveTo(SellerOrderStatus target) {
        return targets().contains(target);
    }

    /** Nothing more will happen to it. */
    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == REFUNDED;
    }

    /** [CR-7]'s "pending": paid for, not yet posted. */
    public boolean pending() {
        return this == PAID || this == PREPARING;
    }

    /** [CR-7]'s "fulfilled": SHIPPED and beyond. */
    public boolean fulfilled() {
        return this == SHIPPED || this == DELIVERED || this == COMPLETED;
    }

    /** @throws IllegalArgumentException when the column holds something this build does not know */
    public static SellerOrderStatus of(String name) {
        return SellerOrderStatus.valueOf(name);
    }

    /** Null when the name is not a status, for query parameters a caller typed. */
    public static SellerOrderStatus parseOrNull(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return SellerOrderStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** The column stores names, so comparisons against it need strings. */
    public static List<String> namesOf(Set<SellerOrderStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }
}
