package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleRequest;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.AdminSale;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.AdminSalePage;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.SaleListingRow;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.ScopePreview;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.Summary;
import com.pegasus.pegasustcgapi.dto.sale.SaleActionRequests;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.repository.sale.AdminSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.NewPriceSale;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin side of direct price discounts [TCG-677]: Pegasus campaigns (flash
 * sales and game/category/set sales, paid for by Pegasus) end to end, and pausing
 * a shop's own sale when something is wrong with it.
 *
 * <p>What may change once something has sold at a sale's price is
 * {@link SaleEditRules}. Every change re-prices listings at once through
 * {@link SalePriceRefresher} and is written to the {@code pegasus.audit.sale} log;
 * there is no audit table or seller notification channel yet, so
 * {@code notifySeller} is recorded there too.
 */
@Service
public class AdminSaleService {

    static final int HOME_MAX = 8;
    static final Duration FLASH_MAX = Duration.ofHours(72);

    private static final Logger AUDIT = LoggerFactory.getLogger("pegasus.audit.sale");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final Set<SaleType> PLATFORM_TYPES = Set.of(SaleType.FLASH_SALE, SaleType.CATEGORY_SALE);
    private static final Set<DiscountType> PLATFORM_DISCOUNTS = Set.of(DiscountType.PERCENT, DiscountType.FIXED_OFF);
    private static final Set<ScopeType> PLATFORM_SCOPES =
            Set.of(ScopeType.ALL, ScopeType.GAME, ScopeType.CATEGORY, ScopeType.CARD_SET);

    private final PriceSaleRepository sales;
    private final AdminSaleRepository views;
    private final SalePriceRefresher prices;
    private final Clock clock;

    public AdminSaleService(PriceSaleRepository sales, AdminSaleRepository views, SalePriceRefresher prices,
            Clock clock) {
        this.sales = sales;
        this.views = views;
        this.prices = prices;
        this.clock = clock;
    }

    public AdminSalePage list(OwnerType owner, SaleType type, SaleStatus status, String q, int page, int size) {
        OffsetDateTime now = now();
        Paging paging = Paging.of(page, size);
        List<AdminSale> items = views.page(owner, type, status, q, paging, now);
        long total = views.count(owner, type, status, q, now);
        return new AdminSalePage(PageResponse.of(items, paging.page(), paging.size(), total),
                views.statusCounts(owner, type, q, now));
    }

    public AdminSale get(long id) {
        return views.find(id, now()).orElseThrow(() -> new NotFoundException(ErrorCode.SALE_NOT_FOUND));
    }

    public Summary summary() {
        OffsetDateTime now = now();
        OffsetDateTime from = now.minus(WINDOW);
        return new Summary(
                views.countActive(OwnerType.PLATFORM, now),
                views.countActive(OwnerType.SELLER, now),
                views.countListingsOnSale(),
                views.saleSales(from),
                views.saleSubsidy(from));
    }

    public PageResponse<SaleListingRow> listings(long id, int page, int size) {
        PriceSale sale = sales.findById(id).orElseThrow(() -> new NotFoundException(ErrorCode.SALE_NOT_FOUND));
        Paging paging = Paging.of(page, size);
        return PageResponse.of(views.listings(sale, paging), paging.page(), paging.size(),
                views.countListings(sale));
    }

    /** What the scope would reach if the sale opened now, before it is saved. */
    public ScopePreview previewScope(SaleActionRequests.PreviewScope request) {
        List<Long> ids = distinct(request.scopeIds());
        requireScope(request.scopeType(), ids);
        List<Long> excluded = distinct(request.excludedSellerIds());
        requireShops(excluded);
        return views.previewScope(request.scopeType(), ids, conditions(request.conditions()),
                request.minUnitPrice(), excluded);
    }

    @Transactional
    public AdminSale create(AdminSaleRequest request, long adminId) {
        NewPriceSale draft = draft(request, null, adminId);
        validate(draft, null);
        long id = insert(draft);
        prices.refreshAll();
        audit("create", id, draft.slug(), adminId, "");
        return get(id);
    }

    @Transactional
    public AdminSale update(long id, AdminSaleRequest request, long adminId) {
        PriceSale current = lockPlatform(id);
        NewPriceSale next = draft(request, current, current.createdBy());
        validate(next, current);
        if (sales.hasOrders(id)) {
            SaleEditRules.checkAfterOrders(current, next);
        }
        if (!next.slug().equals(current.slug()) && sales.slugExists(next.slug())) slugTaken();
        try {
            sales.update(id, next);
        } catch (DuplicateKeyException e) {
            slugTaken();
        }
        prices.refreshAll();
        audit("update", id, next.slug(), adminId, "");
        return get(id);
    }

    /** Only a Pegasus sale nothing has sold at; else 409 SALE_IN_USE. */
    @Transactional
    public void delete(long id, long adminId) {
        PriceSale current = lockPlatform(id);
        if (sales.hasOrders(id)) throw new ConflictException(ErrorCode.SALE_IN_USE);
        sales.delete(id);
        // Its listing_sale_price rows went with it; another sale may now be the cheapest.
        prices.refreshAll();
        audit("delete", id, current.slug(), adminId, "");
    }

    /**
     * Ends a Pegasus sale now, sold at or not; its listings go back to their own
     * price at once. A sale that has not started has nothing to end: delete or
     * pause it instead. An ended sale is returned as it is.
     */
    @Transactional
    public AdminSale end(long id, long adminId) {
        PriceSale current = lockPlatform(id);
        // timestamptz keeps microseconds; truncating keeps the stored end at or
        // before this read, so the sale reads as ended right away.
        OffsetDateTime now = now().truncatedTo(ChronoUnit.MICROS);
        if (!current.effectiveEnd().isAfter(now)) return get(id);
        if (!current.startsAt().isBefore(now)) {
            invalid("This sale has not started; delete or pause it instead");
        }
        sales.endAt(id, now);
        prices.refreshAll();
        audit("end", id, current.slug(), adminId, "plannedEnd=" + current.endsAt());
        return get(id);
    }

    /**
     * Pausing twice keeps the first pause and its reason. A shop's sale needs a
     * reason, because the shop is shown it.
     *
     * <p>A shop's own pause is the exception: the shop could lift it again, so
     * Pegasus's pause replaces it. The shop never gives a reason, Pegasus always
     * does, which is how the two are told apart.
     */
    @Transactional
    public AdminSale pause(long id, SaleActionRequests.Pause request, long adminId) {
        PriceSale current = lock(id);
        String reason = cleanReason(request.reason());
        if (current.ownerType() == OwnerType.SELLER && reason == null) {
            invalid("A reason is required to pause a shop's sale");
        }
        boolean pausedByShop = current.ownerType() == OwnerType.SELLER
                && current.pausedAt() != null && current.pauseReason() == null;
        if (current.pausedAt() == null || pausedByShop) {
            sales.pause(id, adminId, reason, now());
            prices.refreshAll();
            audit("pause", id, current.slug(), adminId, notifyNote(current, request.notifySellerOrFalse())
                    + (reason == null ? "" : " reason=\"" + reason + "\""));
        }
        return get(id);
    }

    @Transactional
    public AdminSale resume(long id, SaleActionRequests.Resume request, long adminId) {
        PriceSale current = lock(id);
        if (current.pausedAt() != null) {
            sales.resume(id);
            prices.refreshAll();
            audit("resume", id, current.slug(), adminId, notifyNote(current, request.notifySellerOrFalse()));
        }
        return get(id);
    }

    /**
     * Saves the home page in one go: up to {@value #HOME_MAX} Pegasus sales that
     * have not ended, at most one of them a flash sale, in display order.
     */
    @Transactional
    public List<AdminSale> replaceHomeOrder(List<Long> orderedIds, long adminId) {
        if (orderedIds.size() > HOME_MAX) invalid("The home page shows at most " + HOME_MAX + " sales");
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) invalid("A sale appears twice in the home order");
        OffsetDateTime now = now();
        int flash = 0;
        // Locked in id order so two admins saving at once cannot deadlock.
        for (Long id : orderedIds.stream().sorted().toList()) {
            PriceSale s = lock(id);
            if (s.ownerType() != OwnerType.PLATFORM || s.statusAt(now, false) == SaleStatus.ENDED) {
                invalid("Only Pegasus sales that have not ended can be on the home page: " + s.slug());
            }
            if (s.saleType() == SaleType.FLASH_SALE) flash++;
        }
        if (flash > 1) invalid("The home page shows one flash sale at a time");
        sales.replaceHomeOrder(orderedIds);
        audit("home-order", 0, "-", adminId, "ids=" + orderedIds);
        List<AdminSale> home = new ArrayList<>();
        for (Long id : sales.findHomeOrder()) home.add(get(id));
        return home;
    }

    /** The request as a row; defaults filled in, and fields that mean nothing for the type cleared. */
    private NewPriceSale draft(AdminSaleRequest r, PriceSale current, Long createdBy) {
        Integer homeSort = null;
        if (r.featuredOnHomeOrFalse()) {
            if (r.homeSort() != null) homeSort = r.homeSort();
            else if (current != null && current.featuredOnHome()) homeSort = current.homeSort();
            else homeSort = sales.nextHomeSort();
        }
        String banner = r.bannerImageUrl() == null || r.bannerImageUrl().isBlank() ? null : r.bannerImageUrl().strip();
        return new NewPriceSale(
                r.name().strip(),
                r.slug(),
                OwnerType.PLATFORM,
                null,
                r.saleType(),
                r.discountType(),
                r.discountValue(),
                r.discountType() == DiscountType.PERCENT ? r.maxDiscountPerUnit() : null,
                r.scopeType(),
                distinct(r.scopeIds()),
                conditions(r.conditions()),
                r.minUnitPrice(),
                r.perListingQuota(),
                r.perBuyerLimit(),
                r.budgetCap(),
                banner,
                r.featuredOnHomeOrFalse(),
                homeSort,
                r.startsAt(),
                r.endsAt(),
                createdBy,
                distinct(r.excludedSellerIds()));
    }

    /** The schema's CHECKs and the campaign rules, answered as 400s with a reason. */
    private void validate(NewPriceSale s, PriceSale current) {
        if (!PLATFORM_TYPES.contains(s.saleType())) invalid("A Pegasus sale is a FLASH_SALE or a CATEGORY_SALE");
        if (!PLATFORM_DISCOUNTS.contains(s.discountType())) invalid("A Pegasus sale takes PERCENT or FIXED_OFF");
        if (!PLATFORM_SCOPES.contains(s.scopeType())) invalid("A Pegasus sale covers ALL, GAME, CATEGORY or CARD_SET");
        if (s.discountType() == DiscountType.PERCENT
                && (s.discountValue().compareTo(BigDecimal.ONE) < 0
                    || s.discountValue().compareTo(BigDecimal.valueOf(90)) > 0)) {
            invalid("A percent discount must be between 1 and 90");
        }
        requireScope(s.scopeType(), s.scopeIds());
        requireShops(s.excludedSellerIds());
        if (!s.endsAt().isAfter(s.startsAt())) invalid("The sale must end after it starts");
        if (current == null && !s.endsAt().isAfter(now())) invalid("The sale must end in the future");
        if (s.saleType() == SaleType.FLASH_SALE
                && Duration.between(s.startsAt(), s.endsAt()).compareTo(FLASH_MAX) > 0) {
            invalid("A flash sale runs for at most 72 hours");
        }
        if (s.saleType() != SaleType.FLASH_SALE && (s.perListingQuota() != null || s.perBuyerLimit() != null)) {
            invalid("Quotas and per-buyer limits are for flash sales only");
        }
        if (current != null && s.budgetCap() != null && s.budgetCap().compareTo(current.subsidyReserved()) < 0) {
            invalid("The budget cannot be below the " + current.subsidyReserved() + " baht already used");
        }
        if (s.featuredOnHome() && s.saleType() == SaleType.FLASH_SALE) {
            boolean otherFlash = sales.findHomeOrder().stream()
                    .filter(id -> current == null || id != current.id())
                    .map(sales::findById)
                    .flatMap(Optional::stream)
                    .anyMatch(o -> o.saleType() == SaleType.FLASH_SALE);
            if (otherFlash) invalid("The home page already shows a flash sale; take it off first");
        }
        boolean newlyFeatured = s.featuredOnHome() && (current == null || !current.featuredOnHome());
        if (newlyFeatured && sales.findHomeOrder().size() >= HOME_MAX) {
            invalid("The home page already shows " + HOME_MAX + " sales; take one off first");
        }
        if (current == null && sales.slugExists(s.slug())) slugTaken();
    }

    private void requireScope(ScopeType scope, List<Long> ids) {
        if ((scope == ScopeType.ALL) != ids.isEmpty()) {
            invalid("Pick games, categories or card sets for that scope, and none for ALL");
        }
        if (!views.scopeTargetsExist(scope, ids)) {
            throw new NotFoundException(switch (scope) {
                case GAME -> ErrorCode.GAME_NOT_FOUND;
                case CATEGORY -> ErrorCode.CATEGORY_NOT_FOUND;
                case CARD_SET -> ErrorCode.CARD_SET_NOT_FOUND;
                default -> ErrorCode.SALE_INVALID;
            });
        }
    }

    /** Every shop a campaign leaves out must exist; else 404 SELLER_NOT_FOUND. */
    private void requireShops(List<Long> sellerIds) {
        if (!views.shopsExist(sellerIds)) {
            throw new NotFoundException(ErrorCode.SELLER_NOT_FOUND);
        }
    }

    private long insert(NewPriceSale draft) {
        try {
            return sales.insert(draft);
        } catch (DuplicateKeyException e) {
            slugTaken();
            return 0;
        }
    }

    private PriceSale lock(long id) {
        return sales.lockById(id).orElseThrow(() -> new NotFoundException(ErrorCode.SALE_NOT_FOUND));
    }

    /** Shop sales belong to the shop; the admin can only pause or resume them. */
    private PriceSale lockPlatform(long id) {
        PriceSale s = lock(id);
        if (s.ownerType() != OwnerType.PLATFORM) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED, "Shop sales can only be paused or resumed here");
        }
        return s;
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    private static List<CardCondition> conditions(List<CardCondition> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    private static String notifyNote(PriceSale s, boolean notifySeller) {
        return s.ownerType() == OwnerType.SELLER
                ? "seller=" + s.sellerProfileId() + " notifySeller=" + notifySeller : "";
    }

    /** Control characters out, so a reason stays one line in the shop's view and in the audit log. */
    private static String cleanReason(String reason) {
        if (reason == null) return null;
        String clean = reason.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.isEmpty() ? null : clean;
    }

    private static void slugTaken() {
        throw new BadRequestException(ErrorCode.SALE_INVALID, "Another sale already uses this slug");
    }

    private static void invalid(String message) {
        throw new BadRequestException(ErrorCode.SALE_INVALID, message);
    }

    private void audit(String action, long id, String slug, long adminId, String extra) {
        AUDIT.info("sale.{} id={} slug={} by={} {}", action, id, slug, adminId,
                Objects.requireNonNullElse(extra, "").strip());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
