package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.SellerPromotionRequest;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.Redemption;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.SellerPromotion;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.SellerPromotionPage;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.Summary;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.repository.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.NewPromotion;
import com.pegasus.pegasustcgapi.repository.SellerPromotionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop's own discount codes [TCG-568]. The shop pays for them; they cover every
 * item it sells and are PERCENT or FIXED. Creating one needs a VERIFIED shop.
 * A code that is not the caller's — another shop's or Pegasus's — is 404, so the
 * API does not confirm it exists. Editing or resuming a code also needs a VERIFIED
 * shop; a suspended one can still read, pause and delete its codes.
 *
 * <p>Editing a used or claimed code follows {@link PromotionEditRules}, as for
 * Pegasus codes. A code Pegasus paused can only be resumed by Pegasus.
 */
@Service
public class SellerPromotionService {

    private static final Logger AUDIT = LoggerFactory.getLogger("pegasus.audit.promotion");
    private static final Duration WINDOW = Duration.ofDays(30);

    private final PromotionRepository promotions;
    private final SellerPromotionRepository views;
    private final SellerOnboardingService sellers;
    private final Clock clock;

    public SellerPromotionService(PromotionRepository promotions, SellerPromotionRepository views,
            SellerOnboardingService sellers, Clock clock) {
        this.promotions = promotions;
        this.views = views;
        this.sellers = sellers;
        this.clock = clock;
    }

    public SellerPromotionPage list(long userId, PromotionStatus status, int page, int size) {
        SellerProfile shop = sellers.requireProfile(userId);
        OffsetDateTime now = now();
        Paging paging = Paging.of(page, size);
        List<SellerPromotion> items = views.page(shop.id(), userId, status, paging, now);
        return new SellerPromotionPage(
                PageResponse.of(items, paging.page(), paging.size(), views.count(shop.id(), status, now)),
                views.statusCounts(shop.id(), now));
    }

    public SellerPromotion get(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        return views.find(id, shop.id(), userId, now())
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
    }

    public Summary summary(long userId) {
        SellerProfile shop = sellers.requireProfile(userId);
        OffsetDateTime now = now();
        // Open-ended from 30 days ago, so a row the database stamps just after this read counts.
        OffsetDateTime from = now.minus(WINDOW);
        return new Summary(
                views.countActive(shop.id(), now),
                views.countRedemptions(shop.id(), from),
                views.shopDiscount(shop.id(), from),
                views.salesWithCodes(shop.id(), from));
    }

    public PageResponse<Redemption> redemptions(long userId, long id, int page, int size) {
        SellerProfile shop = sellers.requireProfile(userId);
        get(userId, id);
        Paging paging = Paging.of(page, size);
        return PageResponse.of(views.redemptions(id, shop.id(), paging), paging.page(), paging.size(),
                views.countRedemptionsOf(id));
    }

    @Transactional
    public SellerPromotion create(long userId, SellerPromotionRequest request) {
        SellerProfile shop = sellers.requireVerifiedSeller(userId);
        NewPromotion draft = draft(request, shop.id(), userId);
        validate(draft, null);
        if (promotions.codeExists(draft.code())) throw new ConflictException(ErrorCode.PROMO_CODE_TAKEN);
        long id;
        try {
            id = promotions.insert(draft);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.PROMO_CODE_TAKEN);
        }
        audit("create", id, draft.code(), userId);
        return get(userId, id);
    }

    @Transactional
    public SellerPromotion update(long userId, long id, SellerPromotionRequest request) {
        SellerProfile shop = sellers.requireVerifiedSeller(userId);
        Promotion current = lockOwn(id, shop.id());
        NewPromotion next = draft(request, shop.id(), current.createdBy());
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
        audit("update", id, next.code(), userId);
        return get(userId, id);
    }

    /** Only a code no order has held and nobody has claimed. */
    @Transactional
    public void delete(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        Promotion current = lockOwn(id, shop.id());
        if (current.usedCount() > 0 || promotions.hasRedemptions(id)
                || current.claimedCount() > 0 || promotions.hasClaims(id)) {
            throw new ConflictException(ErrorCode.PROMO_IN_USE);
        }
        promotions.delete(id);
        audit("delete", id, current.code(), userId);
    }

    /** Pausing an already paused code changes nothing, whoever paused it. */
    @Transactional
    public SellerPromotion pause(long userId, long id) {
        SellerProfile shop = sellers.requireProfile(userId);
        Promotion current = lockOwn(id, shop.id());
        if (current.pausedAt() == null) {
            promotions.pause(id, userId, null, now());
            audit("pause", id, current.code(), userId);
        }
        return get(userId, id);
    }

    /** 409 {@code PROMO_PAUSED} when Pegasus paused it: only Pegasus can lift its own pause. */
    @Transactional
    public SellerPromotion resume(long userId, long id) {
        SellerProfile shop = sellers.requireVerifiedSeller(userId);
        Promotion current = lockOwn(id, shop.id());
        if (current.pausedAt() != null) {
            if (!Objects.equals(current.pausedBy(), userId)) {
                throw new ConflictException(ErrorCode.PROMO_PAUSED,
                        "Pegasus paused this code; contact support to have it resumed");
            }
            promotions.resume(id);
            audit("resume", id, current.code(), userId);
        }
        return get(userId, id);
    }

    private NewPromotion draft(SellerPromotionRequest r, long shopId, Long createdBy) {
        if (Boolean.TRUE.equals(r.featuredOnHome()) || Boolean.TRUE.equals(r.claimRequired())) {
            invalid("Only Pegasus codes can be featured on the home page or require claiming");
        }
        if (r.discountType() == DiscountType.FREE_SHIPPING) {
            invalid("A shop code is a percent or a fixed amount off; free shipping is a Pegasus code");
        }
        return new NewPromotion(
                r.code().strip().toUpperCase(Locale.ROOT),
                r.name().strip(),
                r.description() == null || r.description().isBlank() ? null : r.description().strip(),
                OwnerType.SELLER,
                shopId,
                r.discountType(),
                r.discountValue(),
                r.discountType() == DiscountType.FIXED ? null : r.maxDiscount(),
                r.minSpend() == null ? BigDecimal.ZERO : r.minSpend(),
                ScopeType.ALL,
                List.of(),
                r.visibility() == null ? Visibility.PUBLIC : r.visibility(),
                r.newBuyerOnlyOrFalse(),
                r.usageLimitTotal(),
                r.usageLimitPerUser() == null ? 1 : r.usageLimitPerUser(),
                r.claimableOrFalse(),
                false,
                r.claimLimit(),
                false,
                null,
                r.startsAt(),
                r.endsAt(),
                createdBy);
    }

    private void validate(NewPromotion p, Promotion current) {
        if (p.discountType() == DiscountType.PERCENT
                && (p.discountValue().compareTo(BigDecimal.ONE) < 0
                    || p.discountValue().compareTo(BigDecimal.valueOf(90)) > 0)) {
            invalid("A percent discount must be between 1 and 90");
        }
        // A shop cannot give away more than the order it is attached to.
        if (p.discountType() == DiscountType.FIXED && p.minSpend().signum() > 0
                && p.discountValue().compareTo(p.minSpend()) > 0) {
            invalid("A fixed discount cannot be more than the minimum spend");
        }
        if (!p.endsAt().isAfter(p.startsAt())) invalid("The code must end after it starts");
        if (current == null && !p.endsAt().isAfter(now())) invalid("The code must end in the future");
        if (p.claimable() && p.visibility() != Visibility.PUBLIC) {
            invalid("Only a public code can be collected from the store page");
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

    /** Another shop's code, or Pegasus's, is not found rather than forbidden. */
    private Promotion lockOwn(long id, long shopId) {
        return promotions.lockShopCode(id, shopId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
    }

    private static void invalid(String message) {
        throw new BadRequestException(ErrorCode.PROMO_INVALID, message);
    }

    private void audit(String action, long id, String code, long userId) {
        AUDIT.info("promotion.{} id={} code={} by={} seller=true", action, id, code, userId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
