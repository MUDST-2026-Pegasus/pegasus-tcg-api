package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleRequest;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.PlatformListing;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.PlatformPrice;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.SaleItem;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.SellerSale;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.SellerSalePage;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.Summary;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.Winner;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.repository.sale.AdminSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.NewPriceSale;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.SaleListingKey;
import com.pegasus.pegasustcgapi.repository.sale.SellerSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.SellerSaleRepository.ItemRow;
import com.pegasus.pegasustcgapi.repository.sale.SellerSaleRepository.SaleRow;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.Resolved;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import com.pegasus.pegasustcgapi.service.seller.SellerOnboardingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop's own sales [TCG-678]: the shop lowers prices on its listings and pays
 * the difference itself. A sale is SHOP (every listing), CATEGORY (the shop's
 * listings in some catalogue categories) or LISTINGS (picked listings, each with
 * its own price or one discount for all).
 *
 * <ul>
 *   <li>Creating, editing and resuming need a VERIFIED shop, as for shop codes; a
 *       suspended shop can still read, pause, end and delete.</li>
 *   <li>Another shop's sale, or Pegasus's, is 404.</li>
 *   <li>Two sales of one shop may not cover the same listing at the same time:
 *       409 {@code SALE_OVERLAP} names the listings.</li>
 *   <li>A sale Pegasus paused stays paused until Pegasus resumes it
 *       ({@code SALE_PAUSED} with the reason).</li>
 *   <li>Once something has sold at its price, only what {@link SaleEditRules}
 *       allows may change, and the picked listings and their prices stay.</li>
 * </ul>
 *
 * <p>Every change re-prices the shop's listings at once through {@link SalePriceRefresher}.
 */
@Service
public class SellerSaleService {

    private static final Logger AUDIT = LoggerFactory.getLogger("pegasus.audit.sale");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final BigDecimal MIN_PRICE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Set<ScopeType> SHOP_SCOPES = Set.of(ScopeType.SHOP, ScopeType.CATEGORY, ScopeType.LISTINGS);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PriceSaleRepository sales;
    private final SellerSaleRepository views;
    private final AdminSaleRepository catalogue;
    private final ListingSalePriceRepository listings;
    private final SalePriceRefresher prices;
    private final SellerOnboardingService sellers;
    private final Clock clock;

    public SellerSaleService(PriceSaleRepository sales, SellerSaleRepository views, AdminSaleRepository catalogue,
            ListingSalePriceRepository listings, SalePriceRefresher prices, SellerOnboardingService sellers,
            Clock clock) {
        this.sales = sales;
        this.views = views;
        this.catalogue = catalogue;
        this.listings = listings;
        this.prices = prices;
        this.sellers = sellers;
        this.clock = clock;
    }

    // ---------- reads ----------

    public SellerSalePage list(long userId, SaleStatus status, int page, int size) {
        SellerProfile shop = sellers.requireProfile(userId);
        OffsetDateTime now = now();
        Paging paging = Paging.of(page, size);
        List<SellerSale> items = views.page(shop.id(), status, paging, now).stream()
                .map(r -> toView(r, List.of()))
                .toList();
        return new SellerSalePage(
                PageResponse.of(items, paging.page(), paging.size(), views.count(shop.id(), status, now)),
                views.statusCounts(shop.id(), now));
    }

    public SellerSale get(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        return view(shop.id(), id);
    }

    public Summary summary(long userId) {
        SellerProfile shop = sellers.requireProfile(userId);
        OffsetDateTime now = now();
        BigDecimal[] totals = views.saleTotals(shop.id(), now.minus(WINDOW));
        return new Summary(views.countActive(shop.id(), now), views.countListingsOnSale(shop.id()),
                totals[0], totals[1]);
    }

    public PageResponse<PlatformListing> platformListings(long userId, int page, int size) {
        SellerProfile shop = sellers.requireProfile(userId);
        Paging paging = Paging.of(page, size);
        return PageResponse.of(views.platformListings(shop.id(), paging), paging.page(), paging.size(),
                views.countPlatformListings(shop.id()));
    }

    // ---------- writes ----------

    @Transactional
    public SellerSale create(long userId, SellerSaleRequest request) {
        SellerProfile shop = sellers.requireVerifiedSeller(userId);
        Draft draft = draft(request, shop.id(), null, userId);
        if (!draft.sale().endsAt().isAfter(now())) invalid("The sale must end in the future");
        checkItems(draft, shop.id());
        checkOverlap(draft, shop.id(), null);

        long id = insert(draft.sale());
        writeItems(id, draft);
        prices.refreshListings(views.shopListingIds(shop.id()));
        audit("create", id, draft.sale().slug(), userId);
        return view(shop.id(), id);
    }

    @Transactional
    public SellerSale update(long userId, long id, SellerSaleRequest request) {
        SellerProfile shop = sellers.requireVerifiedSeller(userId);
        PriceSale current = lockOwn(id, shop.id());
        if (current.statusAt(now(), false) == SaleStatus.ENDED) invalid("This sale has ended and cannot be edited");
        Draft draft = draft(request, shop.id(), current, current.createdBy());
        checkItems(draft, shop.id());
        if (sales.hasOrders(id)) {
            SaleEditRules.checkAfterOrders(current, draft.sale());
            checkItemsUnchanged(id, draft);
        }
        checkOverlap(draft, shop.id(), id);

        sales.update(id, draft.sale());
        views.removeListingsExcept(id, draft.items().keySet());
        writeItems(id, draft);
        prices.refreshListings(views.shopListingIds(shop.id()));
        audit("update", id, current.slug(), userId);
        return view(shop.id(), id);
    }

    /** Only a sale nothing has sold at; else 409 SALE_IN_USE. */
    @Transactional
    public void delete(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        PriceSale current = lockOwn(id, shop.id());
        if (sales.hasOrders(id)) throw new ConflictException(ErrorCode.SALE_IN_USE);
        sales.delete(id);
        prices.refreshListings(views.shopListingIds(shop.id()));
        audit("delete", id, current.slug(), userId);
    }

    /** Pausing a paused sale changes nothing, whoever paused it. */
    @Transactional
    public SellerSale pause(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        PriceSale current = lockOwn(id, shop.id());
        if (current.pausedAt() == null && current.statusAt(now(), false) != SaleStatus.ENDED) {
            // No reason: that is how a shop's own pause is told from Pegasus's.
            sales.pause(id, userId, null, now());
            prices.refreshListings(views.shopListingIds(shop.id()));
            audit("pause", id, current.slug(), userId);
        }
        return view(shop.id(), id);
    }

    /** 409 {@code SALE_PAUSED} with Pegasus's reason when Pegasus paused it. */
    @Transactional
    public SellerSale resume(long userId, long id) {
        SellerProfile shop = sellers.requireVerifiedSeller(userId);
        PriceSale current = lockOwn(id, shop.id());
        if (current.pausedAt() != null) {
            if (pausedByPegasus(current, userId)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", current.pauseReason());
                throw new ConflictException(ErrorCode.SALE_PAUSED,
                        "Pegasus paused this sale; contact support to have it resumed", details);
            }
            sales.resume(id);
            prices.refreshListings(views.shopListingIds(shop.id()));
            audit("resume", id, current.slug(), userId);
        }
        return view(shop.id(), id);
    }

    /**
     * Ends a running sale now; its listings go back to their own price at once.
     * One that has not started has nothing to end — delete it instead. An ended
     * sale is returned as it is.
     */
    @Transactional
    public SellerSale end(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        PriceSale current = lockOwn(id, shop.id());
        OffsetDateTime now = now().truncatedTo(ChronoUnit.MICROS);
        if (!current.effectiveEnd().isAfter(now)) return view(shop.id(), id);
        if (!current.startsAt().isBefore(now)) invalid("This sale has not started; delete it instead");
        sales.endAt(id, now);
        prices.refreshListings(views.shopListingIds(shop.id()));
        audit("end", id, current.slug(), userId);
        return view(shop.id(), id);
    }

    // ---------- the form ----------

    /**
     * The request as a row, plus the listings it picks.
     *
     * @param items listing id → its own price (FIXED_PRICE) or null; empty unless LISTINGS
     */
    private record Draft(NewPriceSale sale, Map<Long, BigDecimal> items) {
    }

    private Draft draft(SellerSaleRequest r, long shopId, PriceSale current, Long createdBy) {
        ScopeType scope = r.scopeType();
        if (!SHOP_SCOPES.contains(scope)) invalid("A shop sale covers SHOP, CATEGORY or LISTINGS");
        List<Long> scopeIds = r.scopeIds() == null ? List.of() : r.scopeIds().stream().distinct().toList();
        if ((scope == ScopeType.CATEGORY) == scopeIds.isEmpty()) {
            invalid("Pick categories for a CATEGORY sale, and none for SHOP or LISTINGS");
        }
        if (scope == ScopeType.CATEGORY && !catalogue.scopeTargetsExist(scope, scopeIds)) {
            throw new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        Map<Long, BigDecimal> items = new LinkedHashMap<>();
        List<SellerSaleRequest.Item> given = r.items() == null ? List.of() : r.items();
        if (scope == ScopeType.LISTINGS) {
            if (given.isEmpty()) invalid("Pick at least one listing");
            for (SellerSaleRequest.Item item : given) {
                if (items.containsKey(item.listingId())) invalid("Listing " + item.listingId() + " appears twice");
                items.put(item.listingId(), item.salePrice());
            }
        } else if (!given.isEmpty()) {
            invalid("Listings are picked only for a LISTINGS sale");
        }

        long priced = items.values().stream().filter(Objects::nonNull).count();
        DiscountType type = r.discountType();
        BigDecimal value = r.discountValue();
        if (priced > 0) {
            if (priced != items.size()) invalid("Give every listing its own sale price, or none of them");
            if (type != null && type != DiscountType.FIXED_PRICE) {
                invalid("Listings with their own prices take no sale-wide discount");
            }
            type = DiscountType.FIXED_PRICE;
            value = BigDecimal.ZERO;
        } else {
            if (type == null || value == null) invalid("Choose a discount");
            if (type == DiscountType.FIXED_PRICE) invalid("A fixed price is set per listing, in items");
            if (type == DiscountType.PERCENT
                    && (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.valueOf(90)) > 0)) {
                invalid("A percent discount must be between 1 and 90");
            }
        }
        if (!r.endsAt().isAfter(r.startsAt())) invalid("The sale must end after it starts");

        NewPriceSale sale = new NewPriceSale(
                r.name().strip(),
                current != null ? current.slug() : newSlug(shopId),
                OwnerType.SELLER,
                shopId,
                SaleType.SHOP_SALE,
                type,
                value,
                type == DiscountType.PERCENT ? r.maxDiscountPerUnit() : null,
                scope,
                scopeIds,
                r.conditions() == null ? List.of() : r.conditions().stream().distinct().toList(),
                r.minUnitPrice(),
                null,
                null,
                null,
                null,
                false,
                null,
                r.startsAt(),
                r.endsAt(),
                createdBy);
        return new Draft(sale, items);
    }

    /**
     * Each picked listing must be the shop's, live, and end up cheaper but at
     * least ฿1. Every row at fault is reported at once, as {@code details.rows}.
     */
    private void checkItems(Draft draft, long shopId) {
        if (draft.items().isEmpty()) return;
        Map<Long, ListingFacts> facts = listings.findFacts(draft.items().keySet()).stream()
                .collect(Collectors.toMap(ListingFacts::listingId, Function.identity()));
        NewPriceSale s = draft.sale();
        List<Map<String, Object>> rows = new ArrayList<>();
        draft.items().forEach((listingId, own) -> {
            ListingFacts f = facts.get(listingId);
            if (f == null || f.sellerProfileId() != shopId) {
                rows.add(row(listingId, "LISTING_NOT_FOUND", "Not one of this shop's listings"));
                return;
            }
            if (f.status() != ListingStatus.ACTIVE) {
                rows.add(row(listingId, "LISTING_NOT_LISTED", "Only a listing on sale now can be discounted"));
                return;
            }
            BigDecimal price = switch (s.discountType()) {
                case FIXED_PRICE -> own;
                case FIXED_OFF -> f.price().subtract(s.discountValue());
                case PERCENT -> {
                    BigDecimal d = f.price().multiply(s.discountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    yield f.price().subtract(s.maxDiscountPerUnit() == null ? d : d.min(s.maxDiscountPerUnit()));
                }
            };
            if (price.compareTo(f.price()) >= 0) {
                rows.add(row(listingId, "SALE_PRICE_NOT_LOWER",
                        "The sale price must be below the current price of " + f.price()));
            } else if (price.compareTo(MIN_PRICE) < 0) {
                rows.add(row(listingId, "SALE_PRICE_TOO_LOW", "The sale price cannot go below 1 baht"));
            }
        });
        if (!rows.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("rows", rows);
            throw new BadRequestException(ErrorCode.SALE_INVALID, "Some listings cannot take this sale", details);
        }
    }

    /**
     * 409 {@code SALE_OVERLAP} when another open sale of the shop, over a period
     * that meets this one's, covers any listing this one covers. Two sales of the
     * whole shop clash even while the shop has no listing.
     */
    private void checkOverlap(Draft draft, long shopId, Long selfId) {
        NewPriceSale s = draft.sale();
        List<PriceSale> clashing = views.findOpen(shopId, selfId, now()).stream()
                .filter(o -> o.startsAt().isBefore(s.endsAt()) && s.startsAt().isBefore(o.effectiveEnd()))
                .toList();
        if (clashing.isEmpty()) return;

        PriceSale self = asSale(selfId == null ? 0 : selfId, s);
        List<Long> candidates = s.scopeType() == ScopeType.LISTINGS
                ? List.copyOf(draft.items().keySet())
                : views.shopListingIds(shopId);
        List<ListingFacts> facts = listings.findFacts(candidates);
        Map<SaleListingKey, SaleListing> rows = sales.findListings(
                clashing.stream().map(PriceSale::id).toList(), candidates);

        Set<Long> listingIds = new TreeSet<>();
        Set<Long> saleIds = new LinkedHashSet<>();
        for (PriceSale other : clashing) {
            if (other.scopeType() == ScopeType.SHOP && s.scopeType() == ScopeType.SHOP) saleIds.add(other.id());
            for (ListingFacts f : facts) {
                SaleListing mine = draft.items().containsKey(f.listingId())
                        ? new SaleListing(draft.items().get(f.listingId()), null, 0) : null;
                if (SalePriceResolver.covers(self, f, mine)
                        && SalePriceResolver.covers(other, f, rows.get(new SaleListingKey(other.id(), f.listingId())))) {
                    listingIds.add(f.listingId());
                    saleIds.add(other.id());
                }
            }
        }
        if (!saleIds.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("listingIds", List.copyOf(listingIds));
            details.put("saleIds", List.copyOf(saleIds));
            throw new ConflictException(ErrorCode.SALE_OVERLAP,
                    "Another sale of this shop already covers these listings at that time", details);
        }
    }

    /** Once sold at, the picked listings and their own prices are part of what past orders paid. */
    private void checkItemsUnchanged(long id, Draft draft) {
        Map<Long, BigDecimal> before = new HashMap<>();
        for (ItemRow row : views.items(id)) before.put(row.listingId(), row.rowSalePrice());
        boolean same = before.keySet().equals(draft.items().keySet())
                && draft.items().entrySet().stream().allMatch(e -> sameAmount(before.get(e.getKey()), e.getValue()));
        if (!same) {
            throw new BadRequestException(ErrorCode.SALE_INVALID, "Cards have sold at this sale's price: its "
                    + "listings and their prices can no longer change");
        }
    }

    private void writeItems(long id, Draft draft) {
        draft.items().forEach((listingId, price) -> sales.putListing(id, listingId, price, null));
    }

    private long insert(NewPriceSale sale) {
        try {
            return sales.insert(sale);
        } catch (DuplicateKeyException e) {
            // A random slug colliding is next to impossible; one retry with a fresh one settles it.
            return sales.insert(withSlug(sale, newSlug(sale.sellerProfileId())));
        }
    }

    // ---------- views ----------

    private SellerSale view(long shopId, long id) {
        SaleRow row = views.find(id, shopId, now()).orElseThrow(() -> new NotFoundException(ErrorCode.SALE_NOT_FOUND));
        List<SaleItem> items = row.sale().scopeType() == ScopeType.LISTINGS ? items(row.sale()) : List.of();
        return toView(row, items);
    }

    /**
     * The picked listings with this sale's price for each, and the cheapest Pegasus
     * campaign that is scheduled or running over it, if any.
     */
    private List<SaleItem> items(PriceSale sale) {
        List<ItemRow> rows = views.items(sale.id());
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(ItemRow::listingId).toList();
        Map<Long, ListingFacts> facts = listings.findFacts(ids).stream()
                .collect(Collectors.toMap(ListingFacts::listingId, Function.identity()));
        List<PriceSale> pegasus = sales.findPriceLocking(now()).stream()
                .filter(p -> p.ownerType() == OwnerType.PLATFORM)
                .toList();
        Map<SaleListingKey, SaleListing> platformRows = pegasus.isEmpty() ? Map.of()
                : sales.findListings(pegasus.stream().map(PriceSale::id).toList(), ids);

        List<SaleItem> items = new ArrayList<>();
        for (ItemRow r : rows) {
            ListingFacts f = facts.get(r.listingId());
            BigDecimal mine = null;
            PlatformPrice platform = null;
            Winner winner = null;
            if (f != null && f.status() == ListingStatus.ACTIVE) {
                mine = SalePriceResolver.price(sale, f, new SaleListing(r.rowSalePrice(), null, 0))
                        .map(Resolved::salePrice).orElse(null);
                Optional<PriceSale> best = Optional.empty();
                BigDecimal bestPrice = null;
                for (PriceSale p : pegasus) {
                    Optional<Resolved> got = SalePriceResolver.price(p, f,
                            platformRows.get(new SaleListingKey(p.id(), f.listingId())));
                    if (got.isPresent() && (bestPrice == null || got.get().salePrice().compareTo(bestPrice) < 0)) {
                        best = Optional.of(p);
                        bestPrice = got.get().salePrice();
                    }
                }
                if (best.isPresent()) {
                    PriceSale p = best.get();
                    platform = new PlatformPrice(p.id(), p.name(), p.slug(), bestPrice, p.startsAt(), p.effectiveEnd());
                    // The lowest price wins; on a tie Pegasus's campaign does.
                    winner = mine == null || bestPrice.compareTo(mine) <= 0 ? Winner.PLATFORM : Winner.THIS_SALE;
                }
            }
            items.add(new SaleItem(r.listingId(), r.productName(), r.condition(), r.listPrice(), mine, platform,
                    winner));
        }
        return items;
    }

    private SellerSale toView(SaleRow row, List<SaleItem> items) {
        PriceSale s = row.sale();
        boolean byPegasus = s.pausedAt() != null && pausedByPegasus(s, null);
        return new SellerSale(s.id(), s.name(), s.slug(), s.scopeType(), s.scopeIds(), s.discountType(),
                s.discountValue(), s.maxDiscountPerUnit(), s.conditions(), s.minUnitPrice(), s.startsAt(),
                s.endsAt(), s.endedAt(), row.status(), s.pausedAt(), byPegasus,
                byPegasus ? s.pauseReason() : null, s.createdAt(), s.updatedAt(), row.soldUnits(),
                row.listingCount(), items);
    }

    // ---------- helpers ----------

    /**
     * Pegasus always gives a reason when it pauses a shop's sale; the shop never
     * does. A pause by anyone but this user is Pegasus's too.
     */
    private static boolean pausedByPegasus(PriceSale s, Long userId) {
        return s.pauseReason() != null || (userId != null && !Objects.equals(s.pausedBy(), userId)
                && s.pausedBy() != null);
    }

    private PriceSale lockOwn(long id, long shopId) {
        return views.lockOwn(id, shopId).orElseThrow(() -> new NotFoundException(ErrorCode.SALE_NOT_FOUND));
    }

    /** A draft as the resolver reads a sale. */
    private static PriceSale asSale(long id, NewPriceSale s) {
        return new PriceSale(id, s.name(), s.slug(), s.ownerType(), s.sellerProfileId(), s.saleType(),
                s.discountType(), s.discountValue(), s.maxDiscountPerUnit(), s.scopeType(), s.scopeIds(),
                s.conditions(), s.minUnitPrice(), s.perListingQuota(), s.perBuyerLimit(), s.budgetCap(),
                BigDecimal.ZERO, s.bannerImageUrl(), s.featuredOnHome(), s.homeSort(), s.startsAt(), s.endsAt(),
                null, null, null, null, s.createdBy(), 0, null, null);
    }

    private static NewPriceSale withSlug(NewPriceSale s, String slug) {
        return new NewPriceSale(s.name(), slug, s.ownerType(), s.sellerProfileId(), s.saleType(), s.discountType(),
                s.discountValue(), s.maxDiscountPerUnit(), s.scopeType(), s.scopeIds(), s.conditions(),
                s.minUnitPrice(), s.perListingQuota(), s.perBuyerLimit(), s.budgetCap(), s.bannerImageUrl(),
                s.featuredOnHome(), s.homeSort(), s.startsAt(), s.endsAt(), s.createdBy());
    }

    /** Shop sales have no page of their own yet, but slugs are unique across every sale. */
    private static String newSlug(long shopId) {
        return "shop-" + shopId + "-" + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
    }

    private static Map<String, Object> row(long listingId, String code, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("listingId", listingId);
        row.put("code", code);
        row.put("message", message);
        return row;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static void invalid(String message) {
        throw new BadRequestException(ErrorCode.SALE_INVALID, message);
    }

    private void audit(String action, long id, String slug, long userId) {
        AUDIT.info("sale.{} id={} slug={} by={} seller=true", action, id, slug, userId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
