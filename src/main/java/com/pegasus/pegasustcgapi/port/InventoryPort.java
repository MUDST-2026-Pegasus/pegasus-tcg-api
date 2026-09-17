package com.pegasus.pegasustcgapi.port;

import com.pegasus.pegasustcgapi.model.CardCondition;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock, as the order module drives it [RQ-9, CR-8].
 *
 * <p>Every method joins the caller's transaction. Call them from inside checkout's
 * own, so that a failure later in checkout rolls a reservation back with it.
 *
 * <p>The order module owns {@code order_item_unit}: write one row per card this
 * port hands back, in the same transaction.
 *
 * <p>The life of a sold card through this port:
 * <pre>
 * reserve        LISTED   → RESERVED   at checkout; no ledger line, the card has not left
 * release        RESERVED → LISTED     unpaid or cancelled before shipping
 * commitSale     RESERVED → SOLD       when it ships: SALE −1, cost out of the average
 * restockReturn  SOLD     → RETURNED   the buyer sent it back: RETURN_RESTOCK +1
 * </pre>
 */
public interface InventoryPort {

    /**
     * Holds cards for an order, oldest acquired first, all or nothing.
     *
     * <p>Cards are picked with {@code FOR UPDATE SKIP LOCKED}: twenty buyers
     * racing for the last card do not queue behind each other — one gets it, the
     * rest find nothing and fail at once. Listings are taken in ascending id order,
     * so two checkouts that share listings cannot deadlock.
     *
     * @param quantityByListing how many cards to hold from each listing
     * @return the held cards, per listing
     * @throws com.pegasus.pegasustcgapi.exception.ConflictException
     *         INSUFFICIENT_STOCK when any listing is not on sale or cannot supply
     *         its quantity; nothing is held then
     */
    Map<Long, List<ReservedUnit>> reserve(Map<Long, Integer> quantityByListing);

    /**
     * Gives held cards back. Each returns to its listing, or to the seller's hands
     * if that listing has since been closed. A card that is no longer RESERVED is
     * skipped, so releasing twice is harmless.
     */
    void release(Collection<Long> unitIds);

    /**
     * The cards left with the parcel: SOLD, one SALE line each against the order
     * item, and their cost taken out of the seller's average.
     *
     * @param actorUserId who shipped it; null when the system did
     * @return what each card cost, as booked
     * @throws com.pegasus.pegasustcgapi.exception.ConflictException
     *         LISTING_UNIT_STATE when a card is not RESERVED
     */
    List<SoldUnit> commitSale(long orderItemId, Collection<Long> unitIds, Long actorUserId);

    /**
     * A card came back from the buyer. It is RETURNED — with the seller again, but
     * not for sale until they inspect it and move it — and goes back into the
     * books at the cost it left at.
     *
     * @throws com.pegasus.pegasustcgapi.exception.ConflictException
     *         LISTING_UNIT_STATE when the card is not SOLD
     */
    void restockReturn(long returnItemId, long unitId, Long actorUserId);

    /**
     * The seller's current average cost, for {@code order_item.unit_cost_snapshot}.
     * Zero when there is nothing on the books.
     */
    BigDecimal averageUnitCost(long sellerProfileId, long catalogVariantId, CardCondition condition);

    record ReservedUnit(long unitId, UUID publicUid) {
    }

    /** @param unitCost the average the card left at */
    record SoldUnit(long unitId, UUID publicUid, BigDecimal unitCost) {
    }
}
