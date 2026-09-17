package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.PricingMode;
import java.math.BigDecimal;

/**
 * Another live listing of the same seller, printing and condition. Allowed — it
 * is how one seller asks two prices for one card — but shown, so the seller can
 * tell when two of them should really be one.
 *
 * @param samePrice         priced exactly like the listing it is shown on
 * @param followsSameMarket both are AUTO_MEDIAN, so the nightly job will price them alike
 */
public record SimilarListingResponse(
        long id,
        String lotLabel,
        BigDecimal price,
        PricingMode pricingMode,
        ListingStatus status,
        int quantityAvailable,
        boolean samePrice,
        boolean followsSameMarket) {

    public static SimilarListingResponse of(Listing other, Listing shownOn) {
        return new SimilarListingResponse(other.id(), other.lotLabel(), other.price(), other.pricingMode(),
                other.status(), other.quantityAvailable(),
                other.price().compareTo(shownOn.price()) == 0,
                other.pricingMode() == PricingMode.AUTO_MEDIAN && shownOn.pricingMode() == PricingMode.AUTO_MEDIAN);
    }
}
