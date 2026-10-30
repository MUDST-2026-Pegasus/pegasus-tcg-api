package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what discount codes take off a basket split by shop [TCG-566]. Pure:
 * no database, no clock. Whether a code is live, paused, used up, claimed or
 * allowed for this buyer is the caller's question; this answers what it is worth.
 *
 * <ol>
 *   <li>Each shop's own code first, on that shop's items subtotal.</li>
 *   <li>Then the one Pegasus code, on what is left of the items it covers after
 *       the shop codes, shared across shops in proportion to that remainder.</li>
 *   <li>FREE_SHIPPING (Pegasus only) takes each covered shop's shipping fee, up to
 *       {@code max_discount} per shop.</li>
 *   <li>A discount never exceeds what it is taken from. Every split rounds HALF_UP to
 *       2 places and the leftover satang go to the largest share.</li>
 * </ol>
 *
 * <p>Item discounts are also spread over the lines ({@link Result#discountByLine}),
 * so a partial return can refund what the buyer paid for that card. Shipping
 * discounts are not spread: they are not part of any card's price.
 */
public final class PromotionCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PromotionCalculator() {
    }

    /** One cart line: a listing at its current price times the quantity. */
    public record Line(long id, long sellerProfileId, long gameId, long categoryId, BigDecimal lineTotal) {
    }

    /** @param shippingBySeller the fee for each shop in {@code lines} — chosen, or that shop's default */
    public record Basket(List<Line> lines, Map<Long, BigDecimal> shippingBySeller) {
    }

    /**
     * How one code fared.
     *
     * @param error     null when applied
     * @param discount  what it takes off in total; zero unless applied
     * @param shortfall how much more to spend, for {@code PROMO_MIN_SPEND_NOT_MET}; otherwise null
     */
    public record CodeResult(Promotion promotion, ErrorCode error, BigDecimal discount, BigDecimal shortfall) {

        public boolean applied() {
            return error == null;
        }
    }

    /**
     * One shop's part of the order, ready for {@code seller_order}.
     *
     * @param platformDiscount         everything the Pegasus code takes here, shipping included
     * @param platformShippingDiscount the shipping part of {@code platformDiscount}
     * @param commissionBase           items subtotal less the shop's own discount
     */
    public record SellerTotals(
            long sellerProfileId,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal sellerDiscount,
            BigDecimal platformDiscount,
            BigDecimal platformShippingDiscount,
            BigDecimal grandTotal,
            BigDecimal commissionBase) {

        public BigDecimal discountAmount() {
            return sellerDiscount.add(platformDiscount);
        }
    }

    /**
     * @param sellers        in the order shops first appear in the basket
     * @param discountByLine each line's share of the item discounts, keyed by {@link Line#id}
     * @param codes          one per code passed in, in the same order
     */
    public record Result(List<SellerTotals> sellers, Map<Long, BigDecimal> discountByLine, List<CodeResult> codes) {

        public BigDecimal discountTotal() {
            return sellers.stream().map(SellerTotals::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal grandTotal() {
            return sellers.stream().map(SellerTotals::grandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * Applies {@code codes} to the basket. A code that cannot apply is reported and
     * skipped; the rest still apply. Codes are routed by owner: one Pegasus code,
     * one per shop, a repeat is {@code PROMO_ALREADY_APPLIED} and a code for a shop
     * not in the basket is {@code PROMO_WRONG_SELLER}.
     */
    public static Result calculate(Basket basket, List<Promotion> codes) {
        Map<Long, List<Line>> linesBySeller = groupBySeller(basket.lines());
        for (Long seller : linesBySeller.keySet()) {
            BigDecimal fee = basket.shippingBySeller().get(seller);
            if (fee == null || fee.signum() < 0) {
                throw new IllegalArgumentException("Missing or negative shipping fee for seller " + seller);
            }
        }

        CodeResult[] results = new CodeResult[codes.size()];
        int platformAt = -1;
        Map<Long, Integer> sellerCodeAt = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < codes.size(); i++) {
            Promotion p = codes.get(i);
            if (!seen.add(p.id())) {
                results[i] = rejected(p, ErrorCode.PROMO_ALREADY_APPLIED);
            } else if (p.ownerType() == OwnerType.PLATFORM) {
                if (platformAt >= 0) results[i] = rejected(p, ErrorCode.PROMO_ALREADY_APPLIED);
                else platformAt = i;
            } else if (!linesBySeller.containsKey(p.sellerProfileId())) {
                results[i] = rejected(p, ErrorCode.PROMO_WRONG_SELLER);
            } else if (sellerCodeAt.containsKey(p.sellerProfileId())) {
                results[i] = rejected(p, ErrorCode.PROMO_ALREADY_APPLIED);
            } else {
                sellerCodeAt.put(p.sellerProfileId(), i);
            }
        }

        Map<Long, BigDecimal> lineDiscount = new LinkedHashMap<>();
        Map<Long, BigDecimal> sellerDiscount = new LinkedHashMap<>();
        Map<Long, BigDecimal> platformItems = new LinkedHashMap<>();
        Map<Long, BigDecimal> platformShipping = new LinkedHashMap<>();
        linesBySeller.forEach((seller, lines) -> {
            lines.forEach(l -> lineDiscount.put(l.id(), BigDecimal.ZERO));
            sellerDiscount.put(seller, BigDecimal.ZERO);
            platformItems.put(seller, BigDecimal.ZERO);
            platformShipping.put(seller, BigDecimal.ZERO);
        });

        // Shop codes: each on its own shop's subtotal.
        sellerCodeAt.forEach((seller, i) -> {
            Promotion p = codes.get(i);
            List<Line> lines = linesBySeller.get(seller);
            Map<Long, BigDecimal> weights = new LinkedHashMap<>();
            lines.forEach(l -> weights.put(l.id(), l.lineTotal()));
            results[i] = applyToItems(p, weights, d -> {
                sellerDiscount.put(seller, d);
                split(d, weights).forEach((line, share) -> lineDiscount.merge(line, share, BigDecimal::add));
            });
        });

        // The Pegasus code: on what the shop codes left of the lines it covers.
        if (platformAt >= 0) {
            Promotion p = codes.get(platformAt);
            Map<Long, Map<Long, BigDecimal>> restBySeller = new LinkedHashMap<>();
            linesBySeller.forEach((seller, lines) -> lines.stream()
                    .filter(l -> p.covers(l.gameId(), l.categoryId()))
                    .forEach(l -> restBySeller.computeIfAbsent(seller, s -> new LinkedHashMap<>())
                            .put(l.id(), l.lineTotal().subtract(lineDiscount.get(l.id())))));
            Map<Long, BigDecimal> baseBySeller = new LinkedHashMap<>();
            restBySeller.forEach((seller, rest) -> baseBySeller.put(seller, sum(rest.values())));

            if (restBySeller.isEmpty()) {
                results[platformAt] = rejected(p, ErrorCode.PROMO_NOT_APPLICABLE);
            } else if (p.discountType() == DiscountType.FREE_SHIPPING) {
                results[platformAt] = applyFreeShipping(p, sum(baseBySeller.values()),
                        baseBySeller.keySet(), basket.shippingBySeller(), platformShipping);
            } else {
                results[platformAt] = applyToItems(p, baseBySeller, d ->
                        split(d, baseBySeller).forEach((seller, share) -> {
                            platformItems.put(seller, share);
                            split(share, restBySeller.get(seller))
                                    .forEach((line, s) -> lineDiscount.merge(line, s, BigDecimal::add));
                        }));
            }
        }

        List<SellerTotals> sellers = new ArrayList<>();
        linesBySeller.forEach((seller, lines) -> {
            BigDecimal subtotal = sum(lines.stream().map(Line::lineTotal).toList());
            BigDecimal shipping = basket.shippingBySeller().get(seller);
            BigDecimal own = sellerDiscount.get(seller);
            BigDecimal pegasus = platformItems.get(seller).add(platformShipping.get(seller));
            sellers.add(new SellerTotals(seller, subtotal, shipping, own, pegasus, platformShipping.get(seller),
                    subtotal.add(shipping).subtract(own).subtract(pegasus), subtotal.subtract(own)));
        });
        return new Result(List.copyOf(sellers), Collections.unmodifiableMap(lineDiscount), List.of(results));
    }

    private interface Apply {
        void discount(BigDecimal amount);
    }

    /** PERCENT or FIXED against the sum of {@code bases}; hands the amount to {@code apply}. */
    private static CodeResult applyToItems(Promotion p, Map<Long, BigDecimal> bases, Apply apply) {
        if (p.discountType() == DiscountType.FREE_SHIPPING) {
            // The schema keeps this to Pegasus codes; a shop code like it would discount nothing.
            return rejected(p, ErrorCode.PROMO_NOT_APPLICABLE);
        }
        BigDecimal base = sum(bases.values());
        if (base.compareTo(p.minSpend()) < 0) {
            return new CodeResult(p, ErrorCode.PROMO_MIN_SPEND_NOT_MET, BigDecimal.ZERO, p.minSpend().subtract(base));
        }
        BigDecimal d = p.discountType() == DiscountType.PERCENT
                ? base.multiply(p.discountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : p.discountValue();
        if (p.maxDiscount() != null) d = d.min(p.maxDiscount());
        d = d.min(base).setScale(2, RoundingMode.HALF_UP);
        if (d.signum() == 0) {
            return rejected(p, ErrorCode.PROMO_NOT_APPLICABLE);
        }
        apply.discount(d);
        return new CodeResult(p, null, d, null);
    }

    private static CodeResult applyFreeShipping(Promotion p, BigDecimal base, Set<Long> sellers,
            Map<Long, BigDecimal> shippingBySeller, Map<Long, BigDecimal> platformShipping) {
        if (base.compareTo(p.minSpend()) < 0) {
            return new CodeResult(p, ErrorCode.PROMO_MIN_SPEND_NOT_MET, BigDecimal.ZERO, p.minSpend().subtract(base));
        }
        Map<Long, BigDecimal> perSeller = new LinkedHashMap<>();
        for (Long seller : sellers) {
            BigDecimal d = shippingBySeller.get(seller);
            if (p.maxDiscount() != null) d = d.min(p.maxDiscount());
            perSeller.put(seller, d.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal total = sum(perSeller.values());
        if (total.signum() == 0) {
            return rejected(p, ErrorCode.PROMO_NOT_APPLICABLE);
        }
        platformShipping.putAll(perSeller);
        return new CodeResult(p, null, total, null);
    }

    /**
     * Splits {@code total} by weight. Every share but the largest rounds HALF_UP;
     * the largest takes whatever is left, so the shares always add up to the total.
     * If rounding up would overshoot (tiny totals over many equal weights), the
     * others round down instead.
     */
    static <K> Map<K, BigDecimal> split(BigDecimal total, Map<K, BigDecimal> weights) {
        Map<K, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal sumWeights = sum(weights.values());
        if (total.signum() == 0 || sumWeights.signum() == 0) {
            weights.keySet().forEach(k -> shares.put(k, BigDecimal.ZERO.setScale(2)));
            return shares;
        }
        K largest = null;
        for (Map.Entry<K, BigDecimal> e : weights.entrySet()) {
            if (largest == null || e.getValue().compareTo(weights.get(largest)) > 0) largest = e.getKey();
        }
        BigDecimal others = shareOthers(total, weights, sumWeights, largest, RoundingMode.HALF_UP, shares);
        if (others.compareTo(total) > 0) {
            others = shareOthers(total, weights, sumWeights, largest, RoundingMode.DOWN, shares);
        }
        shares.put(largest, total.subtract(others).setScale(2, RoundingMode.UNNECESSARY));
        return shares;
    }

    private static <K> BigDecimal shareOthers(BigDecimal total, Map<K, BigDecimal> weights, BigDecimal sumWeights,
            K largest, RoundingMode rounding, Map<K, BigDecimal> shares) {
        BigDecimal others = BigDecimal.ZERO;
        for (Map.Entry<K, BigDecimal> e : weights.entrySet()) {
            if (e.getKey().equals(largest)) {
                shares.put(e.getKey(), null);
                continue;
            }
            BigDecimal share = total.multiply(e.getValue()).divide(sumWeights, 2, rounding);
            shares.put(e.getKey(), share);
            others = others.add(share);
        }
        return others;
    }

    private static Map<Long, List<Line>> groupBySeller(List<Line> lines) {
        Map<Long, List<Line>> bySeller = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.lineTotal().signum() < 0) {
                throw new IllegalArgumentException("Negative line total on line " + l.id());
            }
            bySeller.computeIfAbsent(l.sellerProfileId(), s -> new ArrayList<>()).add(l);
        }
        return bySeller;
    }

    private static CodeResult rejected(Promotion p, ErrorCode error) {
        return new CodeResult(p, error, BigDecimal.ZERO, null);
    }

    private static BigDecimal sum(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) total = total.add(v);
        return total;
    }
}
