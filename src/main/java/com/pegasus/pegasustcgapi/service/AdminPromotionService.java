package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.AdminPromotionRequest;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.AdminPromotion;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.AdminPromotionPage;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.Redemption;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.Summary;
import com.pegasus.pegasustcgapi.dto.PromotionActionRequests;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.repository.AdminPromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.NewPromotion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin side of discount codes [TCG-567]: Pegasus codes end to end, and
 * pausing a shop's code when something is wrong with it.
 *
 * <p>What may change on a code that has been used or claimed is
 * {@link PromotionEditRules}, shared with the shop's own API.
 *
 * <p>Every change is written to the {@code pegasus.audit.promotion} log. There is
 * no audit table or seller notification channel yet, so {@code notifySeller} is
 * recorded there too, for whoever builds them.
 */
@Service
public class AdminPromotionService {

    static final int HOME_STRIP_MAX = 8;

    private static final Logger AUDIT = LoggerFactory.getLogger("pegasus.audit.promotion");
    private static final Duration WINDOW = Duration.ofDays(30);

    private final PromotionRepository promotions;
    private final AdminPromotionRepository views;
    private final Clock clock;

    public AdminPromotionService(PromotionRepository promotions, AdminPromotionRepository views, Clock clock) {
        this.promotions = promotions;
        this.views = views;
        this.clock = clock;
    }

    public AdminPromotionPage list(OwnerType owner, PromotionStatus status, String q, int page, int size) {
        OffsetDateTime now = now();
        Paging paging = Paging.of(page, size);
        List<AdminPromotion> items = views.page(owner, status, q, paging, now);
        long total = views.count(owner, status, q, now);
        return new AdminPromotionPage(PageResponse.of(items, paging.page(), paging.size(), total),
                views.statusCounts(owner, q, now));
    }

    public AdminPromotion get(long id) {
        return views.find(id, now()).orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
    }

    public Summary summary() {
        OffsetDateTime now = now();
        OffsetDateTime from = now.minus(WINDOW);
        // The current window is open-ended so a row stamped by the database a moment
        // after this clock read still counts.
        long redemptions = views.countRedemptions(from, null);
        long before = views.countRedemptions(from.minus(WINDOW), from);
        BigDecimal change = before == 0 ? null
                : BigDecimal.valueOf(redemptions - before).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(before), 1, RoundingMode.HALF_UP);
        return new Summary(
                views.countActive(OwnerType.PLATFORM, now),
                views.countActive(OwnerType.SELLER, now),
                redemptions,
                change,
                views.countClaims(from, null),
                views.platformDiscountCost(from, null),
                views.salesWithCodes(from, null),
                views.gmv(from, null),
                views.countPaidOrders(from, null),
                views.countPaidOrdersWithCodes(from, null));
    }

    public PageResponse<Redemption> redemptions(long id, int page, int size) {
        get(id);
        Paging paging = Paging.of(page, size);
        return PageResponse.of(views.redemptions(id, paging), paging.page(), paging.size(),
                views.countRedemptionsOf(id));
    }

    @Transactional
    public AdminPromotion create(AdminPromotionRequest request, long adminId) {
        NewPromotion draft = draft(request, null, adminId);
        validate(draft, null);
        if (promotions.codeExists(draft.code())) throw new ConflictException(ErrorCode.PROMO_CODE_TAKEN);
        long id;
        try {
            id = promotions.insert(draft);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.PROMO_CODE_TAKEN);
        }
        audit("create", id, draft.code(), adminId, "");
        return get(id);
    }

    @Transactional
    public AdminPromotion update(long id, AdminPromotionRequest request, long adminId) {
        Promotion current = lockPlatform(id);
        NewPromotion next = draft(request, current, current.createdBy());
        validate(next, current);
        PromotionEditRules.check(current, next, promotions.hasRedemptions(id));
        if (!next.code().equals(current.code()) && promotions.codeExists(next.code())) {
            throw new ConflictException(ErrorCode.PROMO_CODE_TAKEN);
        }
        try {
            promotions.update(id, next);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.PROMO_CODE_TAKEN);
        }
        audit("update", id, next.code(), adminId, "");
        return get(id);
    }

    /** Only a Pegasus code that no order has held and nobody has claimed. */
    @Transactional
    public void delete(long id, long adminId) {
        Promotion current = lockPlatform(id);
        if (current.usedCount() > 0 || promotions.hasRedemptions(id)
                || current.claimedCount() > 0 || promotions.hasClaims(id)) {
            throw new ConflictException(ErrorCode.PROMO_IN_USE);
        }
        promotions.delete(id);
        audit("delete", id, current.code(), adminId, "");
    }

    /**
     * Ends a Pegasus code now, used or not. PUT refuses an earlier end once the code
     * is used ({@link PromotionEditRules}), but ending does not change how any past
     * order was priced, so it gets its own action. Orders already holding the code
     * keep it until they are paid or released.
     *
     * <p>A code that has not started has nothing to end: delete or pause it instead.
     * An ended code is returned as it is.
     */
    @Transactional
    public AdminPromotion end(long id, long adminId) {
        Promotion current = lockPlatform(id);
        OffsetDateTime now = now();
        if (!current.endsAt().isAfter(now)) return get(id);
        if (!current.startsAt().isBefore(now)) {
            invalid("This code has not started; delete or pause it instead");
        }
        promotions.endAt(id, now);
        audit("end", id, current.code(), adminId, "was=" + current.endsAt());
        return get(id);
    }

    /**
     * Pausing twice keeps the first pause and its reason. A shop's code needs a
     * reason, because the shop is shown it.
     *
     * <p>A shop's own pause is the exception: the shop could lift it again, so
     * Pegasus's pause replaces it. The shop never gives a reason, Pegasus always
     * does, which is how the two are told apart.
     */
    @Transactional
    public AdminPromotion pause(long id, PromotionActionRequests.Pause request, long adminId) {
        Promotion current = lock(id);
        String reason = cleanReason(request.reason());
        if (current.ownerType() == OwnerType.SELLER && reason == null) {
            throw new BadRequestException(ErrorCode.PROMO_INVALID, "A reason is required to pause a shop's code");
        }
        boolean pausedByShop = current.ownerType() == OwnerType.SELLER
                && current.pausedAt() != null && current.pauseReason() == null;
        if (current.pausedAt() == null || pausedByShop) {
            promotions.pause(id, adminId, reason, now());
            audit("pause", id, current.code(), adminId, notifyNote(current, request.notifySellerOrFalse())
                    + (reason == null ? "" : " reason=\"" + reason + "\""));
        }
        return get(id);
    }

    @Transactional
    public AdminPromotion resume(long id, PromotionActionRequests.Resume request, long adminId) {
        Promotion current = lock(id);
        if (current.pausedAt() != null) {
            promotions.resume(id);
            audit("resume", id, current.code(), adminId, notifyNote(current, request.notifySellerOrFalse()));
        }
        return get(id);
    }

    /**
     * Saves the home-page strip in one go: up to {@value #HOME_STRIP_MAX} public,
     * claimable Pegasus codes that have not ended, in display order.
     */
    @Transactional
    public List<AdminPromotion> replaceHomeOrder(List<Long> orderedIds, long adminId) {
        if (orderedIds.size() > HOME_STRIP_MAX) {
            throw new BadRequestException(ErrorCode.PROMO_INVALID,
                    "The home page shows at most " + HOME_STRIP_MAX + " codes");
        }
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new BadRequestException(ErrorCode.PROMO_INVALID, "A code appears twice in the home order");
        }
        OffsetDateTime now = now();
        // Locked in id order so two admins saving at once cannot deadlock.
        for (Long id : orderedIds.stream().sorted().toList()) {
            Promotion p = lock(id);
            if (p.ownerType() != OwnerType.PLATFORM || !p.claimable() || p.visibility() != Visibility.PUBLIC
                    || p.statusAt(now) == PromotionStatus.EXPIRED) {
                throw new BadRequestException(ErrorCode.PROMO_INVALID,
                        "Only public, claimable Pegasus codes that have not ended can be on the home page: "
                                + p.code());
            }
        }
        promotions.replaceHomeOrder(orderedIds);
        audit("home-order", 0, "-", adminId, "ids=" + orderedIds);
        List<AdminPromotion> strip = new ArrayList<>();
        for (Long id : promotions.findHomeOrder()) strip.add(get(id));
        return strip;
    }

    /** The request as a row; defaults filled in, and fields that mean nothing for the type cleared. */
    private NewPromotion draft(AdminPromotionRequest r, Promotion current, Long createdBy) {
        boolean freeShipping = r.discountType() == DiscountType.FREE_SHIPPING;
        BigDecimal value = freeShipping ? BigDecimal.ZERO : r.discountValue();
        if (value == null) throw new BadRequestException(ErrorCode.PROMO_INVALID, "discountValue is required");
        ScopeType scope = r.scopeType() == null ? ScopeType.ALL : r.scopeType();
        List<Long> scopeIds = r.scopeIds() == null ? List.of() : r.scopeIds().stream().distinct().toList();
        Integer homeSort = null;
        if (r.featuredOnHomeOrFalse()) {
            if (r.homeSort() != null) homeSort = r.homeSort();
            else if (current != null && current.featuredOnHome()) homeSort = current.homeSort();
            else homeSort = promotions.nextHomeSort();
        }
        return new NewPromotion(
                r.code().strip().toUpperCase(Locale.ROOT),
                r.name().strip(),
                r.description() == null || r.description().isBlank() ? null : r.description().strip(),
                OwnerType.PLATFORM,
                null,
                r.discountType(),
                value,
                r.discountType() == DiscountType.FIXED ? null : r.maxDiscount(),
                r.minSpend() == null ? BigDecimal.ZERO : r.minSpend(),
                scope,
                scopeIds,
                r.visibility() == null ? Visibility.PUBLIC : r.visibility(),
                r.newBuyerOnlyOrFalse(),
                r.usageLimitTotal(),
                r.usageLimitPerUser() == null ? 1 : r.usageLimitPerUser(),
                r.claimableOrFalse(),
                r.claimRequiredOrFalse(),
                r.claimLimit(),
                r.featuredOnHomeOrFalse(),
                homeSort,
                r.startsAt(),
                r.endsAt(),
                createdBy);
    }

    /** The schema's CHECKs, answered as 400s with a reason instead of a constraint name. */
    private void validate(NewPromotion p, Promotion current) {
        if (p.discountType() == DiscountType.PERCENT
                && (p.discountValue().compareTo(BigDecimal.ONE) < 0
                    || p.discountValue().compareTo(BigDecimal.valueOf(90)) > 0)) {
            invalid("A percent discount must be between 1 and 90");
        }
        if (p.discountType() == DiscountType.FIXED && p.discountValue().signum() <= 0) {
            invalid("A fixed discount must be more than 0");
        }
        if ((p.scopeType() == ScopeType.ALL) != p.scopeIds().isEmpty()) {
            invalid("Pick games or categories for a GAME or CATEGORY code, and none for ALL");
        }
        if (!promotions.scopeTargetsExist(p.scopeType(), p.scopeIds())) {
            throw new NotFoundException(p.scopeType() == ScopeType.GAME
                    ? ErrorCode.GAME_NOT_FOUND : ErrorCode.CATEGORY_NOT_FOUND);
        }
        if (!p.endsAt().isAfter(p.startsAt())) invalid("The code must end after it starts");
        if (current == null && !p.endsAt().isAfter(now())) invalid("The code must end in the future");
        if (p.claimRequired() && !p.claimable()) invalid("A code that must be claimed has to be claimable");
        if (p.featuredOnHome() && (!p.claimable() || p.visibility() != Visibility.PUBLIC)) {
            invalid("Only public, claimable codes can be featured on the home page");
        }
        boolean newlyFeatured = p.featuredOnHome() && (current == null || !current.featuredOnHome());
        if (newlyFeatured && promotions.countFeatured() >= HOME_STRIP_MAX) {
            invalid("The home page already shows " + HOME_STRIP_MAX + " codes; take one off first");
        }
        int claimed = current == null ? 0 : current.claimedCount();
        if (p.claimLimit() != null && p.claimLimit() < claimed) {
            invalid("The claim limit cannot be below the " + claimed + " codes already claimed");
        }
        int used = current == null ? 0 : current.usedCount();
        if (p.usageLimitTotal() != null && p.usageLimitTotal() < used) {
            invalid("The usage limit cannot be below the " + used + " uses already taken");
        }
    }

    private Promotion lock(long id) {
        return promotions.lockById(id).orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
    }

    /** Shop codes belong to the shop; the admin can only pause or resume them. */
    private Promotion lockPlatform(long id) {
        Promotion p = lock(id);
        if (p.ownerType() != OwnerType.PLATFORM) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED, "Shop codes can only be paused or resumed here");
        }
        return p;
    }

    private static String notifyNote(Promotion p, boolean notifySeller) {
        return p.ownerType() == OwnerType.SELLER
                ? "seller=" + p.sellerProfileId() + " notifySeller=" + notifySeller : "";
    }

    /** Control characters out, so a reason stays one line in the shop's view and in the audit log. */
    private static String cleanReason(String reason) {
        if (reason == null) return null;
        String clean = reason.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.isEmpty() ? null : clean;
    }

    private static void invalid(String message) {
        throw new BadRequestException(ErrorCode.PROMO_INVALID, message);
    }

    private void audit(String action, long id, String code, long adminId, String extra) {
        AUDIT.info("promotion.{} id={} code={} by={} {}", action, id, code, adminId,
                Objects.requireNonNullElse(extra, "").strip());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
