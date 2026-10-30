package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Basket;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.CodeResult;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Line;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Pick;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.Result;
import com.pegasus.pegasustcgapi.service.PromotionCalculator.SellerTotals;
import com.pegasus.pegasustcgapi.support.PromotionFixture;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The worked example is Figma PRM-O1: minmin_tcg (Pikachu ฿450 + Gengar ฿380, ship ฿45)
 * and CardVault BKK (Luffy ฿2,400, free shipping), with MINMIN50 and PEGASUS10.
 */
class PromotionCalculatorTest {

    static final long MINMIN = 11;
    static final long CARDVAULT = 22;
    static final long POKEMON = 1;
    static final long ONE_PIECE = 2;
    static final long SINGLES = 7;

    static final Line PIKACHU = line(101, MINMIN, POKEMON, "450.00");
    static final Line GENGAR = line(102, MINMIN, POKEMON, "380.00");
    static final Line LUFFY = line(201, CARDVAULT, ONE_PIECE, "2400.00");

    static final Basket PRM_O1 = basket(List.of(PIKACHU, GENGAR, LUFFY), Map.of(MINMIN, "45.00", CARDVAULT, "0.00"));

    static final Promotion MINMIN50 = PromotionFixture.seller("MINMIN50", MINMIN).fixed("50").minSpend("500").build();
    static final Promotion PEGASUS10 = PromotionFixture.platform("PEGASUS10").percent("10", "200").build();

    @Test
    void figmaExampleSplitsPegasusDiscountByWhatShopCodesLeft() {
        Result r = PromotionCalculator.calculate(PRM_O1, List.of(MINMIN50, PEGASUS10));

        SellerTotals minmin = seller(r, MINMIN);
        assertThat(minmin.itemsSubtotal()).isEqualByComparingTo("830.00");
        assertThat(minmin.sellerDiscount()).isEqualByComparingTo("50.00");
        assertThat(minmin.platformDiscount()).isEqualByComparingTo("49.06");
        assertThat(minmin.grandTotal()).isEqualByComparingTo("775.94");
        assertThat(minmin.commissionBase()).isEqualByComparingTo("780.00");

        SellerTotals cardVault = seller(r, CARDVAULT);
        assertThat(cardVault.sellerDiscount()).isEqualByComparingTo("0.00");
        assertThat(cardVault.platformDiscount()).isEqualByComparingTo("150.94");
        assertThat(cardVault.grandTotal()).isEqualByComparingTo("2249.06");
        assertThat(cardVault.commissionBase()).isEqualByComparingTo("2400.00");

        assertThat(r.discountTotal()).isEqualByComparingTo("250.00");
        assertThat(r.grandTotal()).isEqualByComparingTo("3025.00");
        assertThat(r.codes()).extracting(CodeResult::applied).containsExactly(true, true);
        assertThat(r.codes().get(1).discount()).isEqualByComparingTo("200.00");
    }

    @Test
    void figmaExampleSpreadsDiscountsOverCardsForPartialReturns() {
        Result r = PromotionCalculator.calculate(PRM_O1, List.of(MINMIN50, PEGASUS10));

        // Gengar: shop code 22.89 + Pegasus 22.46, so returning it refunds 334.65.
        assertThat(r.discountByLine().get(GENGAR.id())).isEqualByComparingTo("45.35");
        assertThat(GENGAR.lineTotal().subtract(r.discountByLine().get(GENGAR.id()))).isEqualByComparingTo("334.65");
        assertThat(r.discountByLine().get(PIKACHU.id())).isEqualByComparingTo("53.71");
        assertThat(r.discountByLine().get(LUFFY.id())).isEqualByComparingTo("150.94");
        assertLinesAddUpToSellerDiscounts(r, PRM_O1.lines());
    }

    @Test
    void percentStopsAtMaxDiscount() {
        // 10% of 3,230 is 323; the cap holds it at 200.
        Result r = PromotionCalculator.calculate(PRM_O1, List.of(PEGASUS10));

        assertThat(r.codes().getFirst().discount()).isEqualByComparingTo("200.00");
        assertThat(r.discountTotal()).isEqualByComparingTo("200.00");

        Promotion uncapped = PromotionFixture.platform("PEG10").percent("10", null).build();
        assertThat(PromotionCalculator.calculate(PRM_O1, List.of(uncapped)).discountTotal())
                .isEqualByComparingTo("323.00");
    }

    @Test
    void fixedNeverTakesMoreThanTheItems() {
        Promotion shopHuge = PromotionFixture.seller("MINMIN1000", MINMIN).fixed("1000").build();
        Result shop = PromotionCalculator.calculate(PRM_O1, List.of(shopHuge));

        assertThat(seller(shop, MINMIN).sellerDiscount()).isEqualByComparingTo("830.00");
        assertThat(seller(shop, MINMIN).grandTotal()).isEqualByComparingTo("45.00");
        assertThat(shop.discountByLine().get(PIKACHU.id())).isEqualByComparingTo("450.00");
        assertThat(shop.discountByLine().get(GENGAR.id())).isEqualByComparingTo("380.00");

        Promotion pegasusHuge = PromotionFixture.platform("PEG5000").fixed("5000").build();
        Result pegasus = PromotionCalculator.calculate(PRM_O1, List.of(shopHuge, pegasusHuge));

        assertThat(pegasus.codes().get(1).discount()).isEqualByComparingTo("2400.00");
        assertThat(pegasus.grandTotal()).isEqualByComparingTo("45.00");
        assertLinesAddUpToSellerDiscounts(pegasus, PRM_O1.lines());
    }

    @Test
    void freeShippingCapsEachShopAndLeavesCardsAlone() {
        Basket twoPaidShipping = basket(List.of(PIKACHU, GENGAR, LUFFY), Map.of(MINMIN, "45.00", CARDVAULT, "60.00"));
        Promotion freeShip = PromotionFixture.platform("FREESHIP").freeShipping("50").build();

        Result r = PromotionCalculator.calculate(twoPaidShipping, List.of(freeShip));

        assertThat(seller(r, MINMIN).platformShippingDiscount()).isEqualByComparingTo("45.00");
        assertThat(seller(r, CARDVAULT).platformShippingDiscount()).isEqualByComparingTo("50.00");
        assertThat(seller(r, CARDVAULT).platformDiscount()).isEqualByComparingTo("50.00");
        assertThat(seller(r, CARDVAULT).grandTotal()).isEqualByComparingTo("2410.00");
        assertThat(r.codes().getFirst().discount()).isEqualByComparingTo("95.00");
        assertThat(r.discountByLine().values()).allSatisfy(d -> assertThat(d).isZero());

        Promotion pokemonOnly = PromotionFixture.platform("FREEPKM").freeShipping(null).games(POKEMON).build();
        Result scoped = PromotionCalculator.calculate(twoPaidShipping, List.of(pokemonOnly));
        assertThat(seller(scoped, MINMIN).platformShippingDiscount()).isEqualByComparingTo("45.00");
        assertThat(seller(scoped, CARDVAULT).platformShippingDiscount()).isEqualByComparingTo("0.00");

        // Nothing to take off when every covered shop already ships free.
        Result nothing = PromotionCalculator.calculate(PRM_O1, List.of(
                PromotionFixture.platform("FREEOP").freeShipping(null).games(ONE_PIECE).build()));
        assertThat(nothing.codes().getFirst().error()).isEqualTo(ErrorCode.PROMO_NOT_APPLICABLE);
    }

    @Test
    void leftoverSatangGoToTheLargestShop() {
        Basket threeShops = basket(List.of(
                line(1, 31, POKEMON, "100.00"),
                line(2, 32, POKEMON, "100.00"),
                line(3, 33, POKEMON, "101.00")), Map.of(31L, "0", 32L, "0", 33L, "0"));

        Result r = PromotionCalculator.calculate(threeShops,
                List.of(PromotionFixture.platform("TEN").fixed("10").build()));

        assertThat(seller(r, 31).platformDiscount()).isEqualByComparingTo("3.32");
        assertThat(seller(r, 32).platformDiscount()).isEqualByComparingTo("3.32");
        assertThat(seller(r, 33).platformDiscount()).isEqualByComparingTo("3.36");
        assertThat(r.discountTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void splitNeverOvershootsOnTinyAmounts() {
        Map<String, BigDecimal> equal = new LinkedHashMap<>();
        for (String k : List.of("a", "b", "c", "d")) equal.put(k, new BigDecimal("1.00"));

        Map<String, BigDecimal> shares = PromotionCalculator.split(new BigDecimal("0.02"), equal);

        assertThat(shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.02");
        assertThat(shares.values()).allSatisfy(s -> assertThat(s.signum()).isNotNegative());
    }

    @Test
    void codeWithNothingInScopeIsNotApplicable() {
        Promotion otherGame = PromotionFixture.platform("YUGIOH").percent("10", null).games(99L).build();

        Result r = PromotionCalculator.calculate(PRM_O1, List.of(otherGame));

        assertThat(r.codes().getFirst().error()).isEqualTo(ErrorCode.PROMO_NOT_APPLICABLE);
        assertThat(r.discountTotal()).isZero();
    }

    @Test
    void scopeLimitsWhichCardsCountAndCarryTheDiscount() {
        Promotion onePieceGame = PromotionFixture.platform("OPGAME").percent("20", null).games(ONE_PIECE).build();

        Result r = PromotionCalculator.calculate(PRM_O1, List.of(onePieceGame));

        assertThat(seller(r, CARDVAULT).platformDiscount()).isEqualByComparingTo("480.00");
        assertThat(seller(r, MINMIN).platformDiscount()).isZero();
        assertThat(r.discountByLine().get(PIKACHU.id())).isZero();
        assertThat(r.discountByLine().get(LUFFY.id())).isEqualByComparingTo("480.00");

        Promotion singles = PromotionFixture.platform("SINGLES5").percent("5", null).categories(SINGLES).build();
        assertThat(PromotionCalculator.calculate(PRM_O1, List.of(singles)).discountTotal())
                .isEqualByComparingTo("161.50");
    }

    @Test
    void minimumSpendReportsTheShortfall() {
        Promotion big = PromotionFixture.platform("BIG").fixed("300").minSpend("5000").build();

        Result alone = PromotionCalculator.calculate(PRM_O1, List.of(big));
        assertThat(alone.codes().getFirst().error()).isEqualTo(ErrorCode.PROMO_MIN_SPEND_NOT_MET);
        assertThat(alone.codes().getFirst().shortfall()).isEqualByComparingTo("1770.00");

        // The shop code comes off first, so the Pegasus code needs more.
        Result afterShop = PromotionCalculator.calculate(PRM_O1, List.of(MINMIN50, big));
        assertThat(afterShop.codes().get(1).shortfall()).isEqualByComparingTo("1820.00");
        assertThat(afterShop.codes().getFirst().applied()).isTrue();

        Basket gengarOnly = basket(List.of(GENGAR), Map.of(MINMIN, "45.00"));
        CodeResult shop = PromotionCalculator.calculate(gengarOnly, List.of(MINMIN50)).codes().getFirst();
        assertThat(shop.error()).isEqualTo(ErrorCode.PROMO_MIN_SPEND_NOT_MET);
        assertThat(shop.shortfall()).isEqualByComparingTo("120.00");
    }

    @Test
    void oneCodeForPegasusAndOnePerShopInTheBasket() {
        Promotion strangerShop = PromotionFixture.seller("OTHER50", 33).fixed("50").build();
        Promotion secondMinmin = PromotionFixture.seller("MINMIN20", MINMIN).fixed("20").build();
        Promotion secondPegasus = PromotionFixture.platform("PEG100").fixed("100").build();

        Result r = PromotionCalculator.calculate(PRM_O1,
                List.of(MINMIN50, strangerShop, secondMinmin, PEGASUS10, secondPegasus, PEGASUS10));

        assertThat(r.codes()).extracting(CodeResult::error).containsExactly(
                null,
                ErrorCode.PROMO_WRONG_SELLER,
                ErrorCode.PROMO_ALREADY_APPLIED,
                null,
                ErrorCode.PROMO_ALREADY_APPLIED,
                ErrorCode.PROMO_ALREADY_APPLIED);
        assertThat(r.discountTotal()).isEqualByComparingTo("250.00");
    }

    @Test
    void refusesABasketWithoutShippingForEveryShop() {
        Basket missing = basket(List.of(PIKACHU, LUFFY), Map.of(MINMIN, "45.00"));

        assertThatThrownBy(() -> PromotionCalculator.calculate(missing, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomBasketsKeepEveryInvariant() {
        Random random = new Random(566);
        for (int round = 0; round < 500; round++) {
            List<Line> lines = new ArrayList<>();
            Map<Long, BigDecimal> shipping = new LinkedHashMap<>();
            int shops = 1 + random.nextInt(4);
            long lineId = 1;
            for (long shop = 1; shop <= shops; shop++) {
                shipping.put(shop, cents(random, 10_000));
                for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) {
                    lines.add(new Line(lineId++, shop, 1 + random.nextInt(3), SINGLES, cents(random, 500_000)));
                }
            }
            List<Promotion> codes = new ArrayList<>();
            codes.add(randomCode(random, PromotionFixture.platform("P" + round), true));
            for (long shop = 1; shop <= shops; shop++) {
                if (random.nextBoolean()) codes.add(randomCode(random, PromotionFixture.seller("S" + round + shop, shop), false));
            }

            Result r = PromotionCalculator.calculate(new Basket(lines, shipping), codes);

            assertLinesAddUpToSellerDiscounts(r, lines);
            for (Line l : lines) {
                assertThat(r.discountByLine().get(l.id())).isBetween(BigDecimal.ZERO, l.lineTotal());
            }
            for (SellerTotals s : r.sellers()) {
                assertThat(s.grandTotal().signum()).isNotNegative();
                assertThat(s.platformShippingDiscount()).isLessThanOrEqualTo(s.shippingFee());
                assertThat(s.grandTotal()).isEqualByComparingTo(
                        s.itemsSubtotal().add(s.shippingFee()).subtract(s.discountAmount()));
            }
            BigDecimal byCodes = r.codes().stream().map(CodeResult::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(byCodes).isEqualByComparingTo(r.discountTotal());
            assertThat(r.discountTotal().scale()).isLessThanOrEqualTo(2);
        }
    }

    // ---------- bestPick ----------

    @Test
    void bestPickOnTheCartInFigmaC1ChoosesPegasus10AndMinmin50() {
        List<Promotion> wallet = List.of(
                PromotionFixture.platform("PEG100").fixed("100").build(),
                PromotionFixture.platform("FREESHIP").freeShipping("60").build(),
                PEGASUS10,
                PromotionFixture.seller("MINMIN5", MINMIN).percent("5", null).build(),
                MINMIN50,
                PromotionFixture.seller("CV300", CARDVAULT).fixed("300").minSpend("3000").build());

        Pick pick = PromotionCalculator.bestPick(PRM_O1, wallet);

        assertThat(pick.promotions()).extracting(Promotion::code).containsExactly("MINMIN50", "PEGASUS10");
        assertThat(pick.result().discountTotal()).isEqualByComparingTo("250.00");
        assertThat(pick.result().codes()).allSatisfy(c -> assertThat(c.applied()).isTrue());
    }

    @Test
    void bestPickBreaksTiesWithTheCodeThatEndsFirst() {
        Promotion later = PromotionFixture.platform("LATER100").fixed("100")
                .window(PromotionFixture.NOW.minusDays(1), PromotionFixture.NOW.plusDays(20)).build();
        Promotion sooner = PromotionFixture.platform("SOONER100").fixed("100")
                .window(PromotionFixture.NOW.minusDays(1), PromotionFixture.NOW.plusDays(2)).build();
        Promotion shopLater = PromotionFixture.seller("MMLATER", MINMIN).fixed("50")
                .window(PromotionFixture.NOW.minusDays(1), PromotionFixture.NOW.plusDays(9)).build();
        Promotion shopSooner = PromotionFixture.seller("MMSOONER", MINMIN).fixed("50")
                .window(PromotionFixture.NOW.minusDays(1), PromotionFixture.NOW.plusDays(3)).build();

        Pick pick = PromotionCalculator.bestPick(PRM_O1, List.of(later, shopLater, sooner, shopSooner));

        assertThat(pick.promotions()).extracting(Promotion::code).containsExactly("MMSOONER", "SOONER100");
    }

    @Test
    void bestPickLeavesOutCodesThatCannotApply() {
        Promotion tooBig = PromotionFixture.platform("BIG").fixed("300").minSpend("5000").build();
        Promotion stranger = PromotionFixture.seller("OTHER50", 33).fixed("50").build();

        Pick pick = PromotionCalculator.bestPick(PRM_O1, List.of(tooBig, stranger, tooBig));

        assertThat(pick.promotions()).isEmpty();
        assertThat(pick.result().discountTotal()).isZero();
        assertThat(pick.result().grandTotal()).isEqualByComparingTo("3275.00");
    }

    /** Σ of each shop's line discounts equals its item discount (everything but free shipping). */
    static void assertLinesAddUpToSellerDiscounts(Result r, List<Line> lines) {
        for (SellerTotals s : r.sellers()) {
            BigDecimal onLines = lines.stream()
                    .filter(l -> l.sellerProfileId() == s.sellerProfileId())
                    .map(l -> r.discountByLine().get(l.id()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(onLines).isEqualByComparingTo(s.discountAmount().subtract(s.platformShippingDiscount()));
        }
    }

    static Basket basket(List<Line> lines, Map<Long, String> shipping) {
        Map<Long, BigDecimal> fees = new LinkedHashMap<>();
        shipping.forEach((k, v) -> fees.put(k, new BigDecimal(v)));
        return new Basket(lines, fees);
    }

    static Line line(long id, long seller, long game, String total) {
        return new Line(id, seller, game, SINGLES, new BigDecimal(total));
    }

    static SellerTotals seller(Result r, long sellerProfileId) {
        return r.sellers().stream().filter(s -> s.sellerProfileId() == sellerProfileId).findFirst().orElseThrow();
    }

    private static BigDecimal cents(Random random, int maxCents) {
        return BigDecimal.valueOf(random.nextInt(maxCents + 1), 2);
    }

    private static Promotion randomCode(Random random, PromotionFixture f, boolean platform) {
        int kind = random.nextInt(platform ? 3 : 2);
        if (kind == 0) f.percent(Integer.toString(1 + random.nextInt(90)), random.nextBoolean() ? null : "150");
        else if (kind == 1) f.fixed(cents(random, 300_000).max(new BigDecimal("0.01")).toPlainString());
        else f.freeShipping(random.nextBoolean() ? null : "40");
        if (platform && random.nextBoolean()) f.games(1L + random.nextInt(3));
        if (random.nextInt(4) == 0) f.minSpend(cents(random, 300_000).toPlainString());
        return f.build();
    }
}
