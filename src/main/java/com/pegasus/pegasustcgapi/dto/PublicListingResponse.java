package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.repository.ListingRepository.PublicListing;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A listing as a buyer sees it. Its own shape rather than a filtered seller one,
 * so a field added to the seller's view cannot leak here by default: no lot
 * label, pricing strategy, floor, ceiling, version or reserved count.
 *
 * @param seller the profile the listing is sold from — the storefront is the profile [RQ-2]
 */
public record PublicListingResponse(
        long id,
        ListingCard card,
        CardCondition condition,
        String gradingCompany,
        BigDecimal gradeValue,
        BigDecimal price,
        String currency,
        int quantityAvailable,
        ListingStatus status,
        String publicNote,
        Seller seller,
        Delivery delivery,
        String primaryPhotoUrl,
        OffsetDateTime publishedAt) {

    public static PublicListingResponse of(PublicListing row, ListingCard card, String primaryPhotoUrl,
            String sellerAvatarUrl) {
        Listing l = row.listing();
        return new PublicListingResponse(l.id(), card, l.condition(), l.gradingCompany(), l.gradeValue(),
                l.price(), l.currency(), l.quantityAvailable(), l.status(), l.publicNote(),
                new Seller(row.sellerUsername(), row.sellerDisplayName(), sellerAvatarUrl,
                        row.verified(), row.ratingAvg(), row.ratingCount(), row.salesCount()),
                new Delivery(row.delivery().fastestEstDaysMin(), row.delivery().fastestEstDaysMax(),
                        row.delivery().freeShipping(), row.delivery().optionCount()),
                primaryPhotoUrl, l.publishedAt());
    }

    /** @param username links to {@code /u/{username}} */
    public record Seller(String username, String displayName, String avatarUrl,
                         boolean verified,
                         @Schema(description = "Average rating; null until the seller has a review", nullable = true)
                         BigDecimal ratingAvg,
                         int ratingCount, int salesCount) {
    }

    @Schema(description = "Summary of the seller's active shipping options for one unit at this listing price")
    public record Delivery(Short fastestEstDaysMin, Short fastestEstDaysMax,
                           boolean freeShipping, int optionCount) {}
}
