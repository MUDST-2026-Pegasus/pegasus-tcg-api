package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.PricingMode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One of the caller's own listings, everything included. Buyers get
 * {@link PublicListingResponse}, which has no lot label, pricing strategy or version.
 *
 * @param similarListings the seller's other live listings of this card in this
 *                        condition; see {@link #message(String)}
 */
public record SellerListingResponse(
        long id,
        ListingCard card,
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
        List<ListingPhotoResponse> photos,
        List<SimilarListingResponse> similarListings) {

    public static SellerListingResponse of(Listing l, ListingCard card, List<ListingPhotoResponse> photos,
            List<SimilarListingResponse> similarListings) {

        return new SellerListingResponse(l.id(), card, l.condition(), l.gradingCompany(), l.gradeValue(),
                l.price(), l.currency(), l.pricingMode(), l.autoPriceOffsetPercent(), l.autoPriceFloor(),
                l.autoPriceCeiling(), l.lastAutoPricedAt(), l.quantityTotal(), l.quantityReserved(),
                l.quantityAvailable(), l.status(), l.lotLabel(), l.publicNote(), l.version(), l.publishedAt(),
                l.createdAt(), l.updatedAt(), photos, similarListings);
    }

    /**
     * The message for a write. When a twin exists, it asks whether to merge —
     * the schema guide's replacement for the unique index it removed, which would
     * have refused the second listing outright.
     */
    public String message(String done) {
        for (SimilarListingResponse twin : similarListings) {
            if (twin.samePrice()) {
                return done + ". Listing #" + twin.id()
                        + " already sells this card in this condition at the same price — merge them?";
            }
        }
        for (SimilarListingResponse twin : similarListings) {
            if (twin.followsSameMarket()) {
                return done + ". Listing #" + twin.id()
                        + " follows the same market price for this card and condition, so the two will end up"
                        + " priced alike — merge them?";
            }
        }
        return done;
    }
}
