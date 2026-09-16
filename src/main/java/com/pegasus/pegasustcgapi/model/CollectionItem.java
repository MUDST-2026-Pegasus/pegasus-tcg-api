package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A card someone keeps, as opposed to one they sell.
 *
 * <p>Deliberately not a {@code listing_unit}: stock has a cost, a reservation
 * state and a place in the books, and a keepsake has none of those. Mixing them
 * would make every seller-side query filter the keepsakes back out.
 *
 * @param acquiredPrice what one card cost, not the whole row: a row of three
 *                      bought together at 400 each carries 400
 * @param imageKey      the owner's own photo of the card; the official art comes
 *                      from the catalogue
 */
public record CollectionItem(
        long id,
        long userId,
        long catalogVariantId,
        CardCondition condition,
        int quantity,
        CollectionSource source,
        String gradingCompany,
        BigDecimal gradeValue,
        String certNumber,
        BigDecimal acquiredPrice,
        OffsetDateTime acquiredAt,
        String imageKey,
        String personalNote,
        boolean publicItem,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** A card that arrived through an order is tied to that sale: which card, and how many. */
    public boolean fromPurchase() {
        return source == CollectionSource.PURCHASE;
    }
}
