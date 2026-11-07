package com.pegasus.pegasustcgapi.model.listing;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.pricing.PricingMode;

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
 * @param block             why an admin suspended it; null unless BLOCKED
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
        OffsetDateTime updatedAt,
        Block block) {

    /**
     * @param blockedBy  the admin's user id
     * @param fromStatus where lifting the block goes back to
     */
    public record Block(String reason, Long blockedBy, OffsetDateTime at, ListingStatus fromStatus) {
    }

    /** A listing no admin has suspended. */
    public Listing(
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
        this(id, sellerProfileId, catalogVariantId, condition, gradingCompany, gradeValue, price, currency,
                pricingMode, autoPriceOffsetPercent, autoPriceFloor, autoPriceCeiling, lastAutoPricedAt,
                quantityTotal, quantityReserved, quantityAvailable, status, lotLabel, publicNote, version,
                publishedAt, createdAt, updatedAt, null);
    }

    /** Whether a card of this printing, condition and seller may join this group. */
    public boolean matches(ListingUnit unit) {
        return unit.sellerProfileId() == sellerProfileId
                && unit.catalogVariantId() == catalogVariantId
                && unit.condition() == condition;
    }
}
