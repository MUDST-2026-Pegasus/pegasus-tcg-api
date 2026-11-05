package com.pegasus.pegasustcgapi.port.pricing;

import com.pegasus.pegasustcgapi.dto.sale.SalePricing;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
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
     * @param price       what one card sells for now: the sale price while a sale
     *                    runs [TCG-679], else the asking price
     * @param purchasable ACTIVE, with cards available, from a VERIFIED seller who
     *                    is not on vacation. Checkout still has to reserve: this is
     *                    a snapshot, not a hold.
     * @param listPrice   the seller's asking price
     * @param pricing     the price as a buyer is shown it, sale included
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
            boolean purchasable,
            String sellerName,
            BigDecimal listPrice,
            SalePricing pricing) {

        public ListingOffer {
            if (listPrice == null) {
                listPrice = price;
            }
            if (pricing == null && price != null) {
                pricing = SalePricing.none(price);
            }
        }

        /** Off sale: the price is the asking price. */
        public ListingOffer(
                long listingId,
                long sellerProfileId,
                long catalogVariantId,
                CardCondition condition,
                BigDecimal price,
                String currency,
                int quantityAvailable,
                ListingStatus status,
                boolean purchasable,
                String sellerName) {
            this(listingId, sellerProfileId, catalogVariantId, condition, price, currency,
                    quantityAvailable, status, purchasable, sellerName, price, null);
        }

        /** This offer at another price, sale included. */
        public ListingOffer priced(SalePricing p) {
            return new ListingOffer(listingId, sellerProfileId, catalogVariantId, condition, p.salePrice(), currency,
                    quantityAvailable, status, purchasable, sellerName, p.listPrice(), p);
        }

        public ListingOffer(
                long listingId,
                long sellerProfileId,
                long catalogVariantId,
                CardCondition condition,
                BigDecimal price,
                String currency,
                int quantityAvailable,
                ListingStatus status,
                boolean purchasable) {
            this(listingId, sellerProfileId, catalogVariantId, condition, price, currency,
                    quantityAvailable, status, purchasable, null);
        }
    }
}
