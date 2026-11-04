package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.SaleListingKey;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.Resolved;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code listing_sale_price} in step with the sales and listings it comes
 * from [TCG-676].
 *
 * <p>Two ways in. A change that touches known listings — a seller moving a price,
 * publishing or closing a listing, a sale created, edited, paused or ended —
 * calls {@link #refreshListings} or {@link #refreshAll} straight away, in the
 * caller's transaction. Everything time-driven (a sale starting or ending) and
 * everything nobody calls in for (stock coming back turning SOLD_OUT into ACTIVE)
 * is caught by the scheduler's {@link #refreshAll} each minute.
 *
 * <p>Repeating a refresh changes nothing: a row is only written when the price it
 * would hold differs from the one it holds.
 */
@Service
public class SalePriceRefresher {

    static final int BATCH_SIZE = 500;

    private final PriceSaleRepository sales;
    private final ListingSalePriceRepository prices;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SalePriceRefresher(
            PriceSaleRepository sales,
            ListingSalePriceRepository prices,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.sales = sales;
        this.prices = prices;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Every listing that could hold a sale price, or holds one now, in batches of
     * {@value #BATCH_SIZE}, each its own transaction. With no sale running only
     * the rows already there are visited, to clear them.
     *
     * @return rows written or removed
     */
    public int refreshAll() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PriceSale> running = sales.findRunning(now);

        int changed = 0;
        long after = 0;
        while (true) {
            List<Long> ids = prices.nextCandidates(after, BATCH_SIZE, !running.isEmpty());
            if (ids.isEmpty()) {
                return changed;
            }
            changed += Objects.requireNonNull(transactions.execute(status -> refresh(ids, running, now)));
            after = ids.getLast();
        }
    }

    /** These listings only, in the caller's transaction (or a new one). @return rows written or removed */
    @Transactional
    public int refreshListings(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        return refresh(List.copyOf(listingIds), sales.findRunning(now), now);
    }

    private int refresh(List<Long> listingIds, List<PriceSale> running, OffsetDateTime now) {
        List<ListingFacts> facts = prices.findFacts(listingIds);
        Map<SaleListingKey, SaleListing> rows = sales.findListings(
                running.stream().map(PriceSale::id).toList(), listingIds);
        Map<Long, Resolved> current = prices.findByListingIds(listingIds);

        int changed = 0;
        List<Long> clear = new ArrayList<>();
        for (ListingFacts listing : facts) {
            Optional<Resolved> wanted = SalePriceResolver.resolve(listing, running,
                    (saleId, listingId) -> rows.get(new SaleListingKey(saleId, listingId)), now);
            Resolved held = current.get(listing.listingId());
            if (wanted.isEmpty()) {
                if (held != null) {
                    clear.add(listing.listingId());
                }
            } else if (held == null || !same(held, wanted.get())) {
                prices.upsert(wanted.get(), now);
                changed++;
            }
        }
        // A listing deleted since it got its row is not in facts; its row goes too.
        current.keySet().stream()
                .filter(id -> facts.stream().noneMatch(f -> f.listingId() == id))
                .forEach(clear::add);
        return changed + prices.delete(clear);
    }

    /** Equal amounts and instants, whatever their scale or offset. */
    private static boolean same(Resolved a, Resolved b) {
        return a.saleId() == b.saleId()
                && a.salePrice().compareTo(b.salePrice()) == 0
                && a.discountAmount().compareTo(b.discountAmount()) == 0
                && a.fundedBy() == b.fundedBy()
                && a.endsAt().isEqual(b.endsAt())
                && Objects.equals(a.quotaLeft(), b.quotaLeft());
    }
}
