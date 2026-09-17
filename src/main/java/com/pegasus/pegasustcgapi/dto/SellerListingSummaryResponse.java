package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.PricingMode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A row of the seller's dashboard. The full shape is one request away. */
public record SellerListingSummaryResponse(
        long id,
        ListingCard card,
        CardCondition condition,
        BigDecimal price,
        String currency,
        PricingMode pricingMode,
        int quantityTotal,
        int quantityReserved,
        int quantityAvailable,
        ListingStatus status,
        String lotLabel,
        String primaryPhotoUrl,
        OffsetDateTime updatedAt) {

    public static SellerListingSummaryResponse of(Listing l, ListingCard card, String primaryPhotoUrl) {
        return new SellerListingSummaryResponse(l.id(), card, l.condition(), l.price(), l.currency(),
                l.pricingMode(), l.quantityTotal(), l.quantityReserved(), l.quantityAvailable(), l.status(),
                l.lotLabel(), primaryPhotoUrl, l.updatedAt());
    }
}
