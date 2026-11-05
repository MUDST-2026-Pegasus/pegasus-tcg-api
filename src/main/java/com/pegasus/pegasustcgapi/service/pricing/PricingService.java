package com.pegasus.pegasustcgapi.service.pricing;

import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.Offer;
import com.pegasus.pegasustcgapi.service.sale.SalePricingService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * A listing's price right now, for the cart and checkout: the sale price while a
 * sale runs, as every page shows it [TCG-679].
 */
@Service
public class PricingService implements PricingPort {

    private final ListingRepository listings;
    private final SalePricingService salePricing;

    public PricingService(ListingRepository listings, SalePricingService salePricing) {
        this.listings = listings;
        this.salePricing = salePricing;
    }

    @Override
    public Optional<ListingOffer> offer(long listingId) {
        return Optional.ofNullable(offers(List.of(listingId)).get(listingId));
    }

    @Override
    public Map<Long, ListingOffer> offers(Collection<Long> listingIds) {
        Map<Long, ListingOffer> offers = listings.findOffers(listingIds.stream().distinct().toList()).stream()
                .map(PricingService::toOffer)
                .collect(Collectors.toMap(ListingOffer::listingId, Function.identity()));
        Map<Long, BigDecimal> listPrices = new LinkedHashMap<>();
        offers.values().forEach(o -> listPrices.put(o.listingId(), o.price()));
        Map<Long, ListingOffer> priced = new LinkedHashMap<>();
        salePricing.pricing(listPrices).forEach((id, p) -> priced.put(id, offers.get(id).priced(p)));
        return priced;
    }

    /** Purchasable means exactly what the reservation query will accept, plus at least one card. */
    static ListingOffer toOffer(Offer offer) {
        Listing l = offer.listing();
        boolean purchasable = l.status() == ListingStatus.ACTIVE
                && l.quantityAvailable() > 0
                && offer.sellerStatus() == SellerStatus.VERIFIED
                && !offer.sellerOnVacation()
                && offer.sellerAccountActive();

        String sellerName = offer.sellerDisplayName() == null || offer.sellerDisplayName().isBlank()
                ? offer.sellerUsername()
                : offer.sellerDisplayName();
        return new ListingOffer(l.id(), l.sellerProfileId(), l.catalogVariantId(), l.condition(), l.price(),
                l.currency(), l.quantityAvailable(), l.status(), purchasable, sellerName);
    }
}
