package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.dto.sale.SalePricing;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.cart.CartItem;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.SaleListingKey;
import com.pegasus.pegasustcgapi.repository.sale.SaleCheckoutRepository;
import com.pegasus.pegasustcgapi.repository.sale.SaleCheckoutRepository.HeldLine;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.Resolved;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sale prices at checkout [TCG-680]. Everything here is worked out afresh from
 * the sales themselves, never from {@code listing_sale_price}: a buyer pays what
 * the sale says at the moment the order is placed.
 *
 * <ol>
 *   <li>{@link #resolve} prices each listing.</li>
 *   <li>{@link #plan} applies each sale's per-buyer limit: cards past it sell at
 *       the asking price, on a line of their own.</li>
 *   <li>{@link #hold} takes flash quota and Pegasus budget, each with one guarded
 *       UPDATE, inside the checkout transaction.</li>
 *   <li>{@link #release} gives both back when an order is called off unpaid.</li>
 * </ol>
 */
@Service
public class SaleCheckoutService {

    private final PriceSaleRepository sales;
    private final ListingSalePriceRepository prices;
    private final SaleCheckoutRepository holds;
    private final SalePriceRefresher refresher;
    private final Clock clock;

    public SaleCheckoutService(PriceSaleRepository sales, ListingSalePriceRepository prices,
            SaleCheckoutRepository holds, SalePriceRefresher refresher, Clock clock) {
        this.sales = sales;
        this.prices = prices;
        this.holds = holds;
        this.refresher = refresher;
        this.clock = clock;
    }

    /** A listing's sale price now, and the sale it comes from. */
    public record LivePrice(Resolved price, PriceSale sale) {

        public SalePricing pricing(BigDecimal listPrice) {
            return new SalePricing(listPrice, price.salePrice(),
                    listPrice.subtract(price.salePrice()).multiply(BigDecimal.valueOf(100))
                            .divide(listPrice, 0, java.math.RoundingMode.HALF_UP).intValueExact(),
                    new SalePricing.Sale(sale.id(), sale.slug(), sale.name(), sale.saleType(), price.endsAt(),
                            price.quotaLeft(), sale.perBuyerLimit()));
        }

        public boolean pegasusPays() {
            return price.fundedBy() == OwnerType.PLATFORM;
        }

        /** The quota this listing has under the sale, or null with none. */
        Integer quota(SaleListing row) {
            return row != null && row.quota() != null ? row.quota() : sale.perListingQuota();
        }
    }

    /** Listings on sale now; the rest are absent. No cache is read. */
    public Map<Long, LivePrice> resolve(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PriceSale> running = sales.findRunning(now);
        if (running.isEmpty()) {
            return Map.of();
        }
        Map<Long, PriceSale> byId = running.stream().collect(Collectors.toMap(PriceSale::id, Function.identity()));
        List<Long> ids = List.copyOf(new LinkedHashSet<>(listingIds));
        Map<SaleListingKey, SaleListing> rows = sales.findListings(byId.keySet(), ids);

        Map<Long, LivePrice> out = new LinkedHashMap<>();
        for (ListingFacts facts : prices.findFacts(ids)) {
            SalePriceResolver.resolve(facts, running,
                            (saleId, listingId) -> rows.get(new SaleListingKey(saleId, listingId)), now)
                    .ifPresent(r -> out.put(facts.listingId(), new LivePrice(r, byId.get(r.saleId()))));
        }
        return out;
    }

    /**
     * The order lines a cart becomes. A line on sale keeps its cart item id; the
     * cards past the buyer's limit go on a line of their own at the asking price,
     * with the cart item id negated so discount codes can tell the two apart.
     *
     * @param lines   each line and the sale it sells under; absent means asking price
     */
    public record Plan(List<CartItem> lines, Map<Long, LivePrice> saleByLine) {

        public LivePrice saleOf(CartItem line) {
            return saleByLine.get(line.id());
        }

        /** What Pegasus pays the shop for these lines: its campaigns' discount, card by card. */
        public BigDecimal pegasusSubsidy(Collection<CartItem> of) {
            BigDecimal total = BigDecimal.ZERO;
            for (CartItem line : of) {
                LivePrice sale = saleOf(line);
                if (sale != null && sale.pegasusPays()) {
                    total = total.add(sale.price().discountAmount().multiply(BigDecimal.valueOf(line.quantity())));
                }
            }
            return total;
        }
    }

    /**
     * Applies per-buyer limits. A buyer who already bought the limit is refused;
     * one who has room for some of the cards gets those at the sale price and the
     * rest at the asking price.
     *
     * @param buyerId    null for a guest's preview, who has bought nothing yet
     * @param listPrices each listing's asking price
     * @param lock       hold the buyer's place in each limited sale until the
     *                   transaction ends; checkout passes true, a preview false
     */
    public Plan plan(Long buyerId, List<CartItem> items, Map<Long, LivePrice> live, Map<Long, BigDecimal> listPrices,
            boolean lock) {
        Map<Long, Integer> roomBySale = new HashMap<>();
        List<CartItem> lines = new ArrayList<>();
        Map<Long, LivePrice> saleByLine = new HashMap<>();
        for (CartItem item : items) {
            LivePrice sale = live.get(item.listingId());
            if (sale == null) {
                lines.add(item);
                continue;
            }
            int saleQty = item.quantity();
            Integer limit = sale.sale().perBuyerLimit();
            if (limit != null) {
                long saleId = sale.sale().id();
                Integer room = roomBySale.get(saleId);
                if (room == null) {
                    int bought = 0;
                    if (buyerId != null) {
                        if (lock) {
                            holds.lockBuyer(saleId, buyerId);
                        }
                        bought = holds.boughtAtSale(saleId, buyerId);
                    }
                    if (bought >= limit) {
                        throw new ApiException(ErrorCode.SALE_BUYER_LIMIT_REACHED,
                                ErrorCode.SALE_BUYER_LIMIT_REACHED.defaultMessage(),
                                Map.of("saleId", saleId, "listingId", item.listingId(), "perBuyerLimit", limit));
                    }
                    room = limit - bought;
                }
                saleQty = Math.min(saleQty, room);
                roomBySale.put(saleId, room - saleQty);
            }
            if (saleQty > 0) {
                CartItem onSale = new CartItem(item.id(), item.cartId(), item.listingId(), saleQty,
                        sale.price().salePrice(), item.addedAt(), item.updatedAt());
                lines.add(onSale);
                saleByLine.put(onSale.id(), sale);
            }
            int rest = item.quantity() - saleQty;
            if (rest > 0) {
                lines.add(new CartItem(-item.id(), item.cartId(), item.listingId(), rest,
                        listPrices.get(item.listingId()), item.addedAt(), item.updatedAt()));
            }
        }
        return new Plan(List.copyOf(lines), Map.copyOf(saleByLine));
    }

    /** What one order line holds against its sale until the order is paid. */
    public record Hold(int quota, BigDecimal subsidy) {
        public static final Hold NONE = new Hold(0, BigDecimal.ZERO);
    }

    /** A Pegasus campaign's budget had no room for this checkout's subsidy. */
    public static class BudgetShortException extends RuntimeException {
        private final long saleId;

        public BudgetShortException(long saleId) {
            super("Sale " + saleId + " has no budget left for this checkout", null, false, false);
            this.saleId = saleId;
        }

        public long saleId() {
            return saleId;
        }
    }

    /**
     * Takes each flash sale's quota and each capped campaign's budget for the
     * plan, inside the caller's transaction. A quota without room fails the
     * checkout with SALE_QUOTA_REACHED; a budget without room throws
     * {@link BudgetShortException}, for the caller to end the campaign and tell
     * the buyer the price changed.
     *
     * @return what each sale line holds, by line id
     */
    @Transactional
    public Map<Long, Hold> hold(Plan plan) {
        List<CartItem> onSale = plan.lines().stream().filter(l -> plan.saleOf(l) != null).toList();
        if (onSale.isEmpty()) {
            return Map.of();
        }
        Map<SaleListingKey, SaleListing> rows = sales.findListings(
                onSale.stream().map(l -> plan.saleOf(l).sale().id()).distinct().toList(),
                onSale.stream().map(CartItem::listingId).distinct().toList());

        Map<Long, Hold> out = new HashMap<>();
        Set<Long> repriced = new LinkedHashSet<>();
        Map<Long, BigDecimal> budgetBySale = new LinkedHashMap<>();
        for (CartItem line : onSale) {
            LivePrice sale = plan.saleOf(line);
            long saleId = sale.sale().id();
            int quotaHeld = 0;
            Integer quota = sale.quota(rows.get(new SaleListingKey(saleId, line.listingId())));
            if (quota != null) {
                if (!holds.takeQuota(saleId, line.listingId(), line.quantity(), sale.sale().perListingQuota())) {
                    throw new ConflictException(ErrorCode.SALE_QUOTA_REACHED,
                            ErrorCode.SALE_QUOTA_REACHED.defaultMessage(),
                            Map.of("saleId", saleId, "listingId", line.listingId()));
                }
                quotaHeld = line.quantity();
                repriced.add(line.listingId());
            }
            BigDecimal subsidy = BigDecimal.ZERO;
            if (sale.pegasusPays() && sale.sale().budgetCap() != null) {
                subsidy = sale.price().discountAmount().multiply(BigDecimal.valueOf(line.quantity()));
                budgetBySale.merge(saleId, subsidy, BigDecimal::add);
            }
            out.put(line.id(), new Hold(quotaHeld, subsidy));
        }
        for (Map.Entry<Long, BigDecimal> e : budgetBySale.entrySet()) {
            if (!holds.takeBudget(e.getKey(), e.getValue())) {
                throw new BudgetShortException(e.getKey());
            }
            PriceSale now = sales.findById(e.getKey()).orElseThrow();
            if (now.budgetExhausted()) {
                // Used up exactly: every listing it priced goes back to its own price.
                repriced.addAll(holds.listingsPricedBy(e.getKey()));
            }
        }
        refresher.refreshListings(repriced);
        return out;
    }

    /**
     * Ends a campaign whose budget a checkout found too small, in a transaction of
     * its own: the checkout that found it rolls back, the campaign stays ended.
     */
    @Transactional
    public void endForBudget(long saleId) {
        if (holds.markBudgetExhausted(saleId, OffsetDateTime.now(clock))) {
            refresher.refreshListings(holds.listingsPricedBy(saleId));
        }
    }

    /**
     * Gives back the quota and budget an unpaid seller order holds. Safe to call
     * again: a line gives back once and then holds nothing.
     */
    @Transactional
    public void release(long sellerOrderId) {
        List<HeldLine> held = holds.takeHeld(sellerOrderId);
        if (held.isEmpty()) {
            return;
        }
        Set<Long> repriced = new LinkedHashSet<>();
        for (HeldLine line : held) {
            if (line.quotaHeld() > 0 && line.listingId() != null) {
                holds.giveBackQuota(line.saleId(), line.listingId(), line.quotaHeld());
            }
            if (line.subsidyHeld().signum() > 0) {
                holds.giveBackBudget(line.saleId(), line.subsidyHeld());
            }
            if (line.listingId() != null) {
                repriced.add(line.listingId());
            }
        }
        refresher.refreshListings(repriced);
    }
}
