package com.pegasus.pegasustcgapi.port;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The price of a listing right now, for the cart and checkout — e.g. to tell a
 * buyer that a price moved since the card went into their basket [CR-3].
 */
public interface PricingPort {

    /** Empty when the listing does not exist or was deleted. */
    Optional<ListingOffer> offer(long listingId);

    /** One query for a whole basket. Missing or deleted listings are simply absent. */
    Map<Long, ListingOffer> offers(Collection<Long> listingIds);

    /**
     * @param purchasable ACTIVE, with cards available, from a VERIFIED seller who
     *                    is not on vacation. Checkout still has to reserve: this is
     *                    a snapshot, not a hold.
     */
    record ListingOffer(
            long listingId,
            long sellerProfileId,
            long catalogVariantId,
            CardCondition condition,
            BigDecimal price,
            String currency,
            int quantityAvailable,
            ListingStatus status,
            boolean purchasable) {
    }
}
