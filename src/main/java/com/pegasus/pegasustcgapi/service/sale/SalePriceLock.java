package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.SaleListingKey;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Keeps the struck-through price honest [TCG-676]: while a sale that covers a
 * listing is SCHEDULED or ACTIVE, the listing's price may come down but not go
 * up, by the seller or by AUTO pricing. Otherwise a shop could raise a price the
 * day before its sale and "discount" back to where it was.
 */
@Service
public class SalePriceLock {

    private final PriceSaleRepository sales;
    private final ListingSalePriceRepository prices;
    private final Clock clock;

    public SalePriceLock(PriceSaleRepository sales, ListingSalePriceRepository prices, Clock clock) {
        this.sales = sales;
        this.prices = prices;
        this.clock = clock;
    }

    /** Throws {@code SALE_PRICE_LOCKED} when {@code newPrice} is a rise on a locked listing. */
    public void requireAllowed(long listingId, BigDecimal oldPrice, BigDecimal newPrice) {
        if (newPrice.compareTo(oldPrice) > 0 && !lockedAmong(List.of(listingId)).isEmpty()) {
            throw new ConflictException(ErrorCode.SALE_PRICE_LOCKED,
                    "This listing is in a sale until it ends; its price can only come down");
        }
    }

    /** Which of these listings a scheduled or running sale covers. */
    public Set<Long> lockedAmong(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Set.of();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PriceSale> locking = sales.findPriceLocking(now);
        if (locking.isEmpty()) {
            return Set.of();
        }
        List<ListingFacts> facts = prices.findFacts(listingIds);
        Map<SaleListingKey, SaleListing> rows = sales.findListings(
                locking.stream().map(PriceSale::id).toList(), listingIds);

        return facts.stream()
                .filter(l -> locking.stream().anyMatch(s -> SalePriceResolver.covers(s, l,
                        rows.get(new SaleListingKey(s.id(), l.listingId())))))
                .map(ListingFacts::listingId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
