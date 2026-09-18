package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One physical card, with an identity that survives every price change and every
 * move between listings [RQ-9].
 *
 * @param publicUid       what the site and a QR code show; the sequential id never
 *                        leaves the server
 * @param listingId       null while the card is in the seller's hands
 * @param acquisitionCost what this one card cost; fixed once entered, because the
 *                        stock ledger and the average cost were booked from it
 * @param acquiredAt      oldest first is the order cards are picked for a sale
 */
public record ListingUnit(
        long id,
        UUID publicUid,
        long sellerProfileId,
        long catalogVariantId,
        CardCondition condition,
        Long listingId,
        ListingUnitStatus status,
        String certNumber,
        BigDecimal acquisitionCost,
        OffsetDateTime acquiredAt,
        String unitNote,
        OffsetDateTime soldAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
