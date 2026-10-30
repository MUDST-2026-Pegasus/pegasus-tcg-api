package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.AvailablePromotion;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.BestPick;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.CodeOutcome;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.PromoPreview;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.SellerPreview;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.Source;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.repository.PromotionClaimRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRedemptionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.VariantScope;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Basket;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.CodeResult;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Line;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Pick;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Result;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.SellerTotals;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Discount codes at the till [TCG-569]: which codes this buyer may use, what they
 * take off, and the use they hold on an order until it is paid or called off.
 *
 * <p>{@link PromotionCalculator} does the arithmetic; this decides what it is given.
 * A code is checked, in order, for: existing, belonging in its slot (the Pegasus
 * slot or that shop's), its status, being collected when it must be, the buyer's
 * own use limit and being a first order when it must be. A guest gets the checks
 * that need no account; checkout always runs them all again.
 */
@Service
public class CheckoutPromotionService {

    private final PromotionRepository promotions;
    private final PromotionClaimRepository claims;
    private final PromotionRedemptionRepository redemptions;
    private final Clock clock;

    public CheckoutPromotionService(PromotionRepository promotions, PromotionClaimRepository claims,
            PromotionRedemptionRepository redemptions, Clock clock) {
        this.promotions = promotions;
        this.claims = claims;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    /** A cart line before its game and category are known. */
    public record BasketLine(long cartItemId, long sellerProfileId, long catalogVariantId, BigDecimal lineTotal) {
    }

    /**
     * A code the buyer typed, and what came of it.
     *
     * @param promotion null when no code by that name exists
     * @param shopSlot  the shop it was typed for; null for the Pegasus slot
     * @param error     null when applied
     */
    public record CodeCheck(String code, Promotion promotion, Long shopSlot, ErrorCode error,
            BigDecimal discount, BigDecimal shortfall) {

        public boolean applied() {
            return error == null;
        }
    }

    /** The basket worked out with the codes that passed, and every typed code's outcome. */
    public record Evaluation(Result result, List<CodeCheck> checks) {

        public Optional<CodeCheck> firstFailure() {
            return checks.stream().filter(c -> !c.applied()).findFirst();
        }

        public List<Promotion> applied() {
            return checks.stream().filter(CodeCheck::applied).map(CodeCheck::promotion).toList();
        }
    }

    public Basket basket(List<BasketLine> lines, Map<Long, BigDecimal> shippingBySeller) {
        Map<Long, VariantScope> scopes = promotions.scopeOfVariants(
                lines.stream().map(BasketLine::catalogVariantId).distinct().toList());
        List<Line> calculatorLines = lines.stream().map(l -> {
            VariantScope scope = scopes.get(l.catalogVariantId());
            // A variant with no product row cannot be bought anyway; it simply counts for no scope.
            long game = scope == null ? -1 : scope.gameId();
            long category = scope == null ? -1 : scope.categoryId();
            return new Line(l.cartItemId(), l.sellerProfileId(), game, category, l.lineTotal());
        }).toList();
        return new Basket(calculatorLines, shippingBySeller);
    }

    /** @param buyerId null for a guest */
    public Evaluation evaluate(Long buyerId, Basket basket, String platformCode, Map<Long, String> sellerCodes) {
        List<CodeCheck> slots = new ArrayList<>();
        if (platformCode != null && !platformCode.isBlank()) {
            slots.add(resolve(platformCode, null, buyerId));
        }
        if (sellerCodes != null) {
            sellerCodes.forEach((shop, code) -> {
                if (code != null && !code.isBlank()) slots.add(resolve(code, shop, buyerId));
            });
        }
        return calculate(basket, slots);
    }

    /** The calculator's best pick from the buyer's collected codes and the live public ones. */
    public Pick bestPick(Long buyerId, Basket basket, Collection<Long> sellerIds) {
        List<Promotion> usable = candidates(buyerId, sellerIds).values().stream()
                .filter(p -> eligibility(p, buyerId) == null)
                .toList();
        return PromotionCalculator.bestPick(basket, usable);
    }

    /**
     * Turns an autoPick into the same evaluation a typed set of codes would get. No
     * buyer checks here: {@link #bestPick} only picks codes this buyer may use.
     */
    public Evaluation evaluate(Basket basket, Pick pick) {
        List<CodeCheck> slots = pick.promotions().stream()
                .map(p -> new CodeCheck(p.code(), p, p.sellerProfileId(), null, null, null))
                .toList();
        return calculate(basket, slots);
    }

    /**
     * The picker's three groups: collected codes, public codes still to collect, other
     * public codes for these shops. Each is tried on the basket alone.
     */
    public List<AvailablePromotion> available(Long buyerId, Basket basket, Collection<Long> sellerIds,
            Map<Long, String> sellerNames) {
        Set<Long> claimed = buyerId == null ? Set.of()
                : Set.copyOf(promotions.findClaimedLive(buyerId, now()).stream().map(Promotion::id).toList());
        List<AvailablePromotion> out = new ArrayList<>();
        for (Promotion p : candidates(buyerId, sellerIds).values()) {
            Source source = claimed.contains(p.id()) ? Source.CLAIMED
                    : p.claimable() ? Source.CLAIMABLE : Source.PUBLIC;
            Integer remaining = p.claimLimit() == null ? null : Math.max(0, p.claimLimit() - p.claimedCount());
            ErrorCode error = eligibility(p, buyerId);
            BigDecimal discount = null;
            BigDecimal shortfall = null;
            if (error == null) {
                CodeResult r = PromotionCalculator.calculate(basket, List.of(p)).codes().getFirst();
                error = r.error();
                discount = r.applied() ? r.discount() : null;
                shortfall = r.shortfall();
            }
            out.add(new AvailablePromotion(p.id(), p.code(), p.name(), p.ownerType(), p.sellerProfileId(),
                    p.sellerProfileId() == null ? null : sellerNames.get(p.sellerProfileId()),
                    p.discountType(), p.discountValue(), p.maxDiscount(), p.minSpend(), p.endsAt(), source,
                    source == Source.CLAIMABLE ? remaining : null, error == null, discount,
                    error == null ? null : error.name(), shortfall));
        }
        return out;
    }

    /**
     * The preview the cart and checkout pages show. With shipping still estimated the
     * totals leave shipping out, and a FREE_SHIPPING code waits for a delivery choice.
     */
    public PromoPreview preview(Evaluation evaluation, Map<Long, String> sellerNames, boolean shippingEstimated,
            BestPick bestPick) {
        Result r = evaluation.result();
        List<SellerPreview> sellers = new ArrayList<>();
        BigDecimal items = BigDecimal.ZERO;
        BigDecimal shipping = BigDecimal.ZERO;
        BigDecimal own = BigDecimal.ZERO;
        BigDecimal pegasus = BigDecimal.ZERO;
        BigDecimal grand = BigDecimal.ZERO;
        for (SellerTotals s : r.sellers()) {
            BigDecimal platform = shippingEstimated
                    ? s.platformDiscount().subtract(s.platformShippingDiscount()) : s.platformDiscount();
            BigDecimal total = shippingEstimated
                    ? s.itemsSubtotal().subtract(s.sellerDiscount()).subtract(platform) : s.grandTotal();
            sellers.add(new SellerPreview(s.sellerProfileId(), sellerNames.get(s.sellerProfileId()),
                    s.itemsSubtotal(), shippingEstimated ? null : s.shippingFee(), s.sellerDiscount(), platform,
                    total));
            items = items.add(s.itemsSubtotal());
            shipping = shipping.add(s.shippingFee());
            own = own.add(s.sellerDiscount());
            pegasus = pegasus.add(platform);
            grand = grand.add(total);
        }
        List<CodeOutcome> codes = evaluation.checks().stream().map(c -> {
            Promotion p = c.promotion();
            boolean pending = shippingEstimated && c.applied() && p != null
                    && p.discountType() == DiscountType.FREE_SHIPPING;
            Long shop = p == null ? c.shopSlot() : p.sellerProfileId();
            return new CodeOutcome(p == null ? c.code() : p.code(), p == null ? null : p.ownerType(), shop,
                    shop == null ? null : sellerNames.get(shop), c.applied() ? "APPLIED" : c.error().name(),
                    pending ? null : c.discount(), c.shortfall(), pending);
        }).toList();
        return new PromoPreview(sellers, items, shippingEstimated ? null : shipping, own, pegasus, grand,
                shippingEstimated, codes, bestPick);
    }

    /**
     * Takes a use of every applied code and records it on the order, in the
     * checkout's transaction. Returns the code that ran out between the check and
     * now, if any; the caller then rolls the whole order back.
     *
     * @param sellerOrderIdBySeller each shop's part of the order, for shop codes
     */
    public Optional<Promotion> reserve(long buyerId, long salesOrderId, Map<Long, Long> sellerOrderIdBySeller,
            Evaluation evaluation) {
        for (CodeCheck c : evaluation.checks()) {
            if (!c.applied()) continue;
            Promotion p = c.promotion();
            if (!promotions.takeUse(p.id())) return Optional.of(p);
            Long sellerOrderId = p.ownerType() == OwnerType.SELLER
                    ? sellerOrderIdBySeller.get(p.sellerProfileId()) : null;
            redemptions.insertReserved(p.id(), buyerId, salesOrderId, sellerOrderId, c.discount());
        }
        return Optional.empty();
    }

    /** The order was paid: its codes are spent and are not given back by a later cancel. */
    public void consume(long salesOrderId) {
        redemptions.consumeForSalesOrder(salesOrderId);
    }

    /** The order was cancelled or timed out unpaid: every code it held is given back. */
    public void release(long salesOrderId) {
        redemptions.releaseForSalesOrder(salesOrderId).forEach(promotions::releaseUse);
    }

    /** One shop dropped out before payment: its own code is given back. */
    public void releaseSellerOrder(long sellerOrderId) {
        redemptions.releaseForSellerOrder(sellerOrderId).forEach(promotions::releaseUse);
    }

    /** Codes still on these orders, by sales order, oldest first. */
    public Map<Long, List<PromotionRedemptionRepository.Applied>> appliedBySalesOrder(Collection<Long> salesOrderIds) {
        Map<Long, List<PromotionRedemptionRepository.Applied>> out = new LinkedHashMap<>();
        redemptions.appliedFor(salesOrderIds)
                .forEach(a -> out.computeIfAbsent(a.salesOrderId(), id -> new ArrayList<>()).add(a));
        return out;
    }

    /** Runs the calculator on the codes that passed, and folds its answers back into the slots. */
    private Evaluation calculate(Basket basket, List<CodeCheck> slots) {
        List<Promotion> passing = slots.stream().filter(CodeCheck::applied).map(CodeCheck::promotion).toList();
        Result result = PromotionCalculator.calculate(basket, passing);
        List<CodeCheck> checks = new ArrayList<>();
        int next = 0;
        for (CodeCheck slot : slots) {
            if (!slot.applied()) {
                checks.add(slot);
                continue;
            }
            CodeResult r = result.codes().get(next++);
            checks.add(new CodeCheck(slot.code(), slot.promotion(), slot.shopSlot(), r.error(),
                    r.applied() ? r.discount() : null, r.shortfall()));
        }
        // The calculator skips a code it turns down, so the totals already leave it out.
        return new Evaluation(result, checks);
    }

    private CodeCheck resolve(String typed, Long shopSlot, Long buyerId) {
        String code = typed.strip();
        Optional<Promotion> found = promotions.findByCode(code);
        if (found.isEmpty()) return new CodeCheck(code, null, shopSlot, ErrorCode.PROMO_NOT_FOUND, null, null);
        Promotion p = found.get();
        boolean rightSlot = shopSlot == null
                ? p.ownerType() == OwnerType.PLATFORM
                : p.ownerType() == OwnerType.SELLER && shopSlot.equals(p.sellerProfileId());
        ErrorCode error = rightSlot ? eligibility(p, buyerId) : ErrorCode.PROMO_WRONG_SELLER;
        return new CodeCheck(p.code(), p, shopSlot, error, null, null);
    }

    /** Everything but the arithmetic; null when the code may go on this buyer's order. */
    private ErrorCode eligibility(Promotion p, Long buyerId) {
        PromotionStatus status = p.statusAt(now());
        switch (status) {
            case SCHEDULED: return ErrorCode.PROMO_NOT_STARTED;
            case EXPIRED: return ErrorCode.PROMO_EXPIRED;
            case PAUSED: return ErrorCode.PROMO_PAUSED;
            case USED_UP: return ErrorCode.PROMO_USED_UP;
            case ACTIVE: break;
        }
        if (p.claimRequired() && (buyerId == null || !claims.isClaimed(p.id(), buyerId))) {
            return ErrorCode.PROMO_NOT_CLAIMED;
        }
        if (buyerId != null && redemptions.countHeld(p.id(), buyerId) >= p.usageLimitPerUser()) {
            return ErrorCode.PROMO_USER_LIMIT_REACHED;
        }
        if (buyerId != null && p.newBuyerOnly() && promotions.hasPaidOrder(buyerId)) {
            return ErrorCode.PROMO_NEW_BUYER_ONLY;
        }
        return null;
    }

    /** Collected codes first, then live public ones, for Pegasus and the shops in the basket. */
    private Map<Long, Promotion> candidates(Long buyerId, Collection<Long> sellerIds) {
        OffsetDateTime now = now();
        Map<Long, Promotion> out = new LinkedHashMap<>();
        if (buyerId != null) {
            promotions.findClaimedLive(buyerId, now).stream()
                    .filter(p -> p.ownerType() == OwnerType.PLATFORM || sellerIds.contains(p.sellerProfileId()))
                    .forEach(p -> out.put(p.id(), p));
        }
        promotions.findPublicLive(sellerIds.isEmpty() ? List.of(-1L) : sellerIds, now)
                .forEach(p -> out.putIfAbsent(p.id(), p));
        return out;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
