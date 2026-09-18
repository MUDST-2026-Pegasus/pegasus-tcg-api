package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.repository.ListingRepository.PublicListing;
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
        String primaryPhotoUrl,
        OffsetDateTime publishedAt) {

    public static PublicListingResponse of(PublicListing row, ListingCard card, String primaryPhotoUrl) {
        Listing l = row.listing();
        return new PublicListingResponse(l.id(), card, l.condition(), l.gradingCompany(), l.gradeValue(),
                l.price(), l.currency(), l.quantityAvailable(), l.status(), l.publicNote(),
                new Seller(row.sellerUsername(), row.sellerDisplayName(), row.sellerAvatarUrl()),
                primaryPhotoUrl, l.publishedAt());
    }

    /** @param username links to {@code /u/{username}} */
    public record Seller(String username, String displayName, String avatarUrl) {
    }
}
