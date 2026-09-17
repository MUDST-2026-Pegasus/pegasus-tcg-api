package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One asking price over any number of identical cards [RQ-9] — a price group,
 * not a card. The cards are {@link ListingUnit}s.
 *
 * <p>A seller may hold several listings of the same printing in the same
 * condition. That is the point of the design, which is why no unique index
 * stands in the way.
 *
 * @param quantityAvailable a cache of the LISTED units, written only by the
 *                          database trigger; never computed or set here
 * @param version           bumped on every price and stock change
 */
public record Listing(
        long id,
        long sellerProfileId,
        long catalogVariantId,
        CardCondition condition,
        String gradingCompany,
        BigDecimal gradeValue,
        BigDecimal price,
        String currency,
        PricingMode pricingMode,
        BigDecimal autoPriceOffsetPercent,
        BigDecimal autoPriceFloor,
        BigDecimal autoPriceCeiling,
        OffsetDateTime lastAutoPricedAt,
        int quantityTotal,
        int quantityReserved,
        int quantityAvailable,
        ListingStatus status,
        String lotLabel,
        String publicNote,
        int version,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** Whether a card of this printing, condition and seller may join this group. */
    public boolean matches(ListingUnit unit) {
        return unit.sellerProfileId() == sellerProfileId
                && unit.catalogVariantId() == catalogVariantId
                && unit.condition() == condition;
    }
}
