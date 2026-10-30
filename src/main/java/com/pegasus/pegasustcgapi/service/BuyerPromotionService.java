package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Claim;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.ClaimResult;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.ClaimableCode;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.ScopeTarget;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Shop;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Terms;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Use;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Wallet;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.WalletCode;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.WalletTab;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionClaimSource;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.PromotionClaimRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRedemptionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRedemptionRepository.BuyerUse;
import com.pegasus.pegasustcgapi.repository.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.ClaimedPromotion;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository.Storefront;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collecting discount codes and the buyer's "My coupons" wallet [TCG-576].
 *
 * <p>Collecting a code costs nothing and does not take a use; only {@code claim_limit}
 * caps how many copies go out. Using a collected code is checkout's business
 * ({@link CheckoutPromotionService}).
 */
@Service
public class BuyerPromotionService {

    /** The home-page strip shows at most this many codes. */
    static final int FEATURED_LIMIT = 8;
    /** An ended code stays on the EXPIRED tab this long, then drops off. */
    static final int EXPIRED_SHOWN_DAYS = 30;

    private final PromotionRepository promotions;
    private final PromotionClaimRepository claims;
    private final PromotionRedemptionRepository redemptions;
    private final SellerProfileRepository sellerProfiles;
    private final UserRepository users;
    private final Clock clock;

    public BuyerPromotionService(PromotionRepository promotions, PromotionClaimRepository claims,
            PromotionRedemptionRepository redemptions, SellerProfileRepository sellerProfiles,
            UserRepository users, Clock clock) {
        this.promotions = promotions;
        this.claims = claims;
        this.redemptions = redemptions;
        this.sellerProfiles = sellerProfiles;
        this.users = users;
        this.clock = clock;
    }

    /** @param userId null for a guest, who sees every code as not yet collected */
    public List<ClaimableCode> featured(Long userId) {
        return strip(promotions.findFeaturedLive(now(), FEATURED_LIMIT), userId);
    }

    /** A store page's collectable codes; a profile that never became a shop has none. */
    public List<ClaimableCode> storeCodes(String username, Long userId) {
        AuthUser owner = users.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
        return sellerProfiles.findByUserId(owner.id())
                .map(shop -> strip(promotions.findShopClaimableLive(shop.id(), now()), userId))
                .orElse(List.of());
    }

    /**
     * Collects a code from a strip. Only PUBLIC codes that can be collected are
     * reachable by id; anything else is PROMO_NOT_FOUND, so ids cannot be probed.
     */
    @Transactional
    public ClaimResult claim(long userId, long promotionId, PromotionClaimSource source) {
        Promotion p = promotions.findById(promotionId)
                .filter(x -> x.claimable() && x.visibility() == Visibility.PUBLIC)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
        PromotionClaimSource from = source != null ? source
                : p.ownerType() == OwnerType.PLATFORM ? PromotionClaimSource.HOME : PromotionClaimSource.STORE;
        return collect(userId, p, from);
    }

    /** Collects a typed code, PRIVATE ones included: knowing the code is the invitation. */
    @Transactional
    public ClaimResult claimByCode(long userId, String code) {
        Promotion p = promotions.findByCode(code)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
        return collect(userId, p, PromotionClaimSource.WALLET_INPUT);
    }

    /**
     * A code's terms. A PRIVATE code is not found, unless the caller has collected
     * it, so the wallet can still open its terms.
     */
    public Terms terms(String code, Long userId) {
        Promotion p = promotions.findByCode(code)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROMO_NOT_FOUND));
        boolean claimed = userId != null && claims.isClaimed(p.id(), userId);
        if (p.visibility() == Visibility.PRIVATE && !claimed) {
            throw new NotFoundException(ErrorCode.PROMO_NOT_FOUND);
        }
        Map<Long, String> names = promotions.scopeNames(p.scopeType(), p.scopeIds());
        List<ScopeTarget> targets = p.scopeIds().stream()
                .filter(names::containsKey)
                .map(id -> new ScopeTarget(id, names.get(id)))
                .toList();
        return new Terms(p.id(), p.code(), p.name(), p.description(), p.ownerType(), shops(List.of(p)).get(p.sellerProfileId()),
                p.discountType(), p.discountValue(), p.maxDiscount(), p.minSpend(), p.scopeType(), targets,
                p.startsAt(), p.endsAt(), p.usageLimitPerUser(), p.newBuyerOnly(),
                p.ownerType() == OwnerType.PLATFORM, p.claimable(), p.claimRequired(), remainingClaims(p),
                p.statusAt(now()), claimed);
    }

    /**
     * "My coupons". AVAILABLE: collected, live or about to start, with uses left.
     * USED: went on a paid order (codes typed at checkout count too). EXPIRED:
     * collected, never used, and ended, paused or used up; ended ones drop off
     * after {@value #EXPIRED_SHOWN_DAYS} days. A code with uses left after one is
     * spent shows on both AVAILABLE and USED.
     *
     * @param owner null for both Pegasus and shop codes
     */
    public Wallet wallet(long userId, WalletTab tab, OwnerType owner) {
        OffsetDateTime now = now();
        Map<Long, Promotion> byId = new LinkedHashMap<>();
        Map<Long, OffsetDateTime> claimedAt = new HashMap<>();
        for (ClaimedPromotion c : promotions.findClaimedBy(userId)) {
            byId.put(c.promotion().id(), c.promotion());
            claimedAt.put(c.promotion().id(), c.claimedAt());
        }
        Map<Long, Integer> held = new HashMap<>();
        Map<Long, List<Use>> paid = new LinkedHashMap<>();
        for (BuyerUse u : redemptions.heldBy(userId)) {
            held.merge(u.promotionId(), 1, Integer::sum);
            if ("CONSUMED".equals(u.status())) {
                paid.computeIfAbsent(u.promotionId(), id -> new ArrayList<>())
                        .add(new Use(u.salesOrderId(), u.orderNumber(), u.amount(), u.status(), u.at()));
            }
        }
        promotions.findByIds(paid.keySet().stream().filter(id -> !byId.containsKey(id)).toList())
                .forEach(p -> byId.put(p.id(), p));

        Map<WalletTab, List<Promotion>> tabs = new EnumMap<>(WalletTab.class);
        for (WalletTab t : WalletTab.values()) tabs.put(t, new ArrayList<>());
        OffsetDateTime shownSince = now.minusDays(EXPIRED_SHOWN_DAYS);
        for (Promotion p : byId.values()) {
            if (owner != null && p.ownerType() != owner) continue;
            boolean collected = claimedAt.containsKey(p.id());
            boolean used = paid.containsKey(p.id());
            PromotionStatus status = p.statusAt(now);
            if (collected && usesLeft(p, held) > 0
                    && (status == PromotionStatus.ACTIVE || status == PromotionStatus.SCHEDULED)) {
                tabs.get(WalletTab.AVAILABLE).add(p);
            }
            if (used) {
                tabs.get(WalletTab.USED).add(p);
            }
            if (collected && !used && p.endsAt().isAfter(shownSince) && (status == PromotionStatus.EXPIRED
                    || status == PromotionStatus.PAUSED || status == PromotionStatus.USED_UP)) {
                tabs.get(WalletTab.EXPIRED).add(p);
            }
        }

        List<Promotion> shown = new ArrayList<>(tabs.get(tab));
        switch (tab) {
            case AVAILABLE -> shown.sort(Comparator.comparing(Promotion::endsAt).thenComparing(Promotion::id));
            // Uses come newest first, so a code's first use is its latest.
            case USED -> shown.sort(Comparator.comparing((Promotion p) -> paid.get(p.id()).getFirst().at())
                    .reversed());
            case EXPIRED -> shown.sort(Comparator.comparing(Promotion::endsAt).reversed());
        }
        Map<Long, Shop> shops = shops(shown);
        List<WalletCode> items = shown.stream().map(p -> new WalletCode(p.id(), p.code(), p.name(),
                p.description(), p.ownerType(), shops.get(p.sellerProfileId()), p.discountType(),
                p.discountValue(), p.maxDiscount(), p.minSpend(), p.startsAt(), p.endsAt(), p.statusAt(now),
                claimedAt.get(p.id()), usesLeft(p, held),
                tab == WalletTab.USED ? paid.get(p.id()) : List.of())).toList();
        Map<WalletTab, Integer> counts = new EnumMap<>(WalletTab.class);
        tabs.forEach((t, list) -> counts.put(t, list.size()));
        return new Wallet(tab, items, counts);
    }

    /**
     * Collecting is idempotent: a repeat hands back the first claim, whatever the
     * code's state now. A new claim inserts first and then takes a copy; if the
     * copies have run out the exception rolls the insert back.
     */
    private ClaimResult collect(long userId, Promotion p, PromotionClaimSource source) {
        Optional<PromotionClaimRepository.Claim> existing = claims.find(p.id(), userId);
        if (existing.isPresent()) {
            return new ClaimResult(view(p, existing.get()), true);
        }
        ErrorCode refused = switch (p.statusAt(now())) {
            case ACTIVE -> null;
            case SCHEDULED -> ErrorCode.PROMO_NOT_STARTED;
            case PAUSED -> ErrorCode.PROMO_PAUSED;
            case USED_UP -> ErrorCode.PROMO_USED_UP;
            case EXPIRED -> ErrorCode.PROMO_EXPIRED;
        };
        if (refused != null) throw new ApiException(refused);
        // A double tap that lost the race on the unique key gets the winner's claim.
        if (!claims.insertIfAbsent(p.id(), userId, source)) {
            return new ClaimResult(view(p, claims.find(p.id(), userId).orElseThrow()), true);
        }
        if (!promotions.takeClaimSlot(p.id())) {
            throw new ConflictException(ErrorCode.PROMO_CLAIM_FULL);
        }
        return new ClaimResult(view(p, claims.find(p.id(), userId).orElseThrow()), false);
    }

    private List<ClaimableCode> strip(List<Promotion> codes, Long userId) {
        Set<Long> claimed = userId == null ? Set.of()
                : claims.findClaimedAmong(userId, codes.stream().map(Promotion::id).toList());
        Map<Long, Shop> shops = shops(codes);
        return codes.stream().map(p -> new ClaimableCode(p.id(), p.code(), p.name(), p.description(),
                p.ownerType(), shops.get(p.sellerProfileId()), p.discountType(), p.discountValue(),
                p.maxDiscount(), p.minSpend(), p.endsAt(), p.claimRequired(), remainingClaims(p),
                claimed.contains(p.id()))).toList();
    }

    private Map<Long, Shop> shops(List<Promotion> codes) {
        List<Long> ids = codes.stream().map(Promotion::sellerProfileId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Shop> out = new HashMap<>();
        for (Storefront s : sellerProfiles.storefronts(ids).values()) {
            out.put(s.sellerProfileId(), new Shop(s.sellerProfileId(), s.name(), s.username()));
        }
        return out;
    }

    private static Claim view(Promotion p, PromotionClaimRepository.Claim c) {
        return new Claim(c.id(), p.id(), p.code(), c.source(), c.claimedAt());
    }

    private static Integer remainingClaims(Promotion p) {
        return p.claimLimit() == null ? null : Math.max(0, p.claimLimit() - p.claimedCount());
    }

    private static int usesLeft(Promotion p, Map<Long, Integer> held) {
        return Math.max(0, p.usageLimitPerUser() - held.getOrDefault(p.id(), 0));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
