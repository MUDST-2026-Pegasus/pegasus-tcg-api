package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Offer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** A listing's price right now, for the cart and checkout. */
@Service
public class PricingService implements PricingPort {

    private final ListingRepository listings;

    public PricingService(ListingRepository listings) {
        this.listings = listings;
    }

    @Override
    public Optional<ListingOffer> offer(long listingId) {
        return Optional.ofNullable(offers(List.of(listingId)).get(listingId));
    }

    @Override
    public Map<Long, ListingOffer> offers(Collection<Long> listingIds) {
        return listings.findOffers(listingIds.stream().distinct().toList()).stream()
                .map(PricingService::toOffer)
                .collect(Collectors.toMap(ListingOffer::listingId, Function.identity()));
    }

    /** Purchasable means exactly what the reservation query will accept, plus at least one card. */
    static ListingOffer toOffer(Offer offer) {
        Listing l = offer.listing();
        boolean purchasable = l.status() == ListingStatus.ACTIVE
                && l.quantityAvailable() > 0
                && offer.sellerStatus() == SellerStatus.VERIFIED
                && !offer.sellerOnVacation()
                && offer.sellerAccountActive();

        return new ListingOffer(l.id(), l.sellerProfileId(), l.catalogVariantId(), l.condition(), l.price(),
                l.currency(), l.quantityAvailable(), l.status(), purchasable);
    }
}
