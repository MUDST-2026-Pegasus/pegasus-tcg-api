package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerShippingOption.SELLER_SHIPPING_OPTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.AvailablePromotion;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.CodeOutcome;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.PromoPreview;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews.Source;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.PromoPreviewRequest;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionClaimSource;
import com.pegasus.pegasustcgapi.repository.PromotionClaimRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.NewPromotion;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Figma PRM-B1/C1 basket: minmin_tcg sells Pikachu ฿450 and Gengar ฿380 and
 * charges ฿45 to post; CardVault BKK sells Luffy ฿2,400 and posts free. MINMIN50 is
 * ฿50 off minmin over ฿500; PEGASUS10 is 10% off everything, at most ฿200.
 */
class CheckoutPromotionIntegrationTest extends PostgresIntegrationTest {

    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired PromotionRepository promotions;
    @Autowired PromotionClaimRepository claims;
    @Autowired PlatformTransactionManager transactions;

    private Seller minmin;
    private Seller cardVault;
    private Listing pikachu;
    private Listing gengar;
    private Listing luffy;
    private long minminShipping;
    private long cardVaultShipping;
    private String pegasus10;
    private String minmin50;

    @BeforeEach
    void basket() {
        minmin = data.seller();
        cardVault = data.seller();
        minminShipping = shipping(minmin, "45.00");
        cardVaultShipping = shipping(cardVault, "0.00");
        short pokemon = data.game("Pokemon");
        short onePiece = data.game("One Piece");
        pikachu = data.onSale(minmin, data.card(pokemon, "Pikachu ex " + data.tag(), "SAR"), CardCondition.NM, 3, "450.00");
        gengar = data.onSale(minmin, data.card(pokemon, "Gengar " + data.tag(), "HR"), CardCondition.LP, 3, "380.00");
        luffy = data.onSale(cardVault, data.card(onePiece, "Monkey.D.Luffy " + data.tag(), "SEC"), CardCondition.NM, 3,
                "2400.00");
        // Ends before every other test's codes, so a tie in bestPick goes to it.
        pegasus10 = insert(platform(code("PEGASUS10"), DiscountType.PERCENT, "10", "200", "0").endsIn(2)).code;
        minmin50 = insert(shop(code("MINMIN50"), minmin, DiscountType.FIXED, "50", "500").endsIn(2)).code;
    }

    @Test
    void previewMatchesTheCartAndCheckoutDesigns() {
        AuthPrincipal buyer = buyerWithFigmaCart();

        // C1: no delivery chosen yet, so shipping is left out of the total.
        PromoPreview cartPage = checkout.promoPreview(buyer, null,
                new PromoPreviewRequest(null, null, pegasus10, Map.of(minmin.profileId(), minmin50), null));
        assertThat(cartPage.shippingEstimated()).isTrue();
        assertThat(cartPage.shippingTotal()).isNull();
        assertThat(cartPage.grandTotal()).isEqualByComparingTo("2980.00");
        assertThat(cartPage.codes()).extracting(CodeOutcome::status).containsExactly("APPLIED", "APPLIED");

        // B1: delivery chosen.
        PromoPreview checkoutPage = checkout.promoPreview(buyer, null, new PromoPreviewRequest(null,
                deliveries(), pegasus10, Map.of(minmin.profileId(), minmin50), null));
        assertThat(checkoutPage.shippingEstimated()).isFalse();
        assertThat(checkoutPage.grandTotal()).isEqualByComparingTo("3025.00");
        assertThat(checkoutPage.platformDiscountTotal()).isEqualByComparingTo("200.00");
        assertThat(checkoutPage.sellers()).anySatisfy(s -> {
            assertThat(s.sellerProfileId()).isEqualTo(minmin.profileId());
            assertThat(s.platformDiscount()).isEqualByComparingTo("49.06");
            assertThat(s.grandTotal()).isEqualByComparingTo("775.94");
        });
        assertThat(promotions.findByCode(pegasus10).orElseThrow().usedCount()).isZero();
    }

    @Test
    void autoPickOnCartC1ChoosesPegasus10AndMinmin50() {
        AuthPrincipal buyer = buyerWithFigmaCart();
        // Other tests leave equally good 10% codes behind; ending first wins the tie.
        dsl.update(PROMOTION).set(PROMOTION.ENDS_AT, OffsetDateTime.now(clock).plusHours(1))
                .where(PROMOTION.CODE.eq(pegasus10)).execute();
        insert(platform(code("PEG100"), DiscountType.FIXED, "100", null, "0").endsIn(1));
        insert(shop(code("MINMIN5"), minmin, DiscountType.PERCENT, "5", "0").endsIn(1));
        claims.insertIfAbsent(promotions.findByCode(minmin50).orElseThrow().id(), buyer.userId(),
                PromotionClaimSource.STORE);

        PromoPreview picked = checkout.promoPreview(buyer, null, new PromoPreviewRequest(null, null, null, null, true));

        assertThat(picked.bestPick().platformCode()).isEqualTo(pegasus10);
        assertThat(picked.bestPick().sellerCodes()).containsEntry(minmin.profileId(), minmin50);
        assertThat(picked.grandTotal()).isEqualByComparingTo("2980.00");
    }

    @Test
    void checkoutWritesTheDiscountSplitAndHoldsEachCode() {
        AuthPrincipal buyer = buyerWithFigmaCart();

        CheckoutResponse order = checkout.checkout(buyer, key(), null, request(pegasus10,
                Map.of(minmin.profileId(), minmin50), new BigDecimal("3025.00")));

        assertThat(order.grandTotal()).isEqualByComparingTo("3025.00");
        assertThat(order.discountTotal()).isEqualByComparingTo("250.00");
        SellerOrderRecord minminPart = sellerOrder(order.orderId(), minmin);
        assertThat(minminPart.getSellerDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(minminPart.getPlatformDiscountAmount()).isEqualByComparingTo("49.06");
        assertThat(minminPart.getGrandTotal()).isEqualByComparingTo("775.94");
        assertThat(minminPart.getSellerNetAmount()).isEqualByComparingTo(minminPart.getGrandTotal()
                .add(minminPart.getPlatformDiscountAmount()).subtract(minminPart.getCommissionAmount()));
        // Commission is on 780 (after the shop's own code), not 830 and not 730.94.
        assertThat(minminPart.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("780.00")
                .multiply(minminPart.getCommissionRatePercent()).divide(new BigDecimal("100"), 2,
                        java.math.RoundingMode.HALF_UP));
        assertThat(sellerOrder(order.orderId(), cardVault).getPlatformDiscountAmount())
                .isEqualByComparingTo("150.94");
        assertThat(allocated(minminPart.getId(), gengar.id())).isEqualByComparingTo("45.35");

        assertThat(redemptionStatuses(order.orderId())).containsOnly("RESERVED").hasSize(2);
        assertThat(promotions.findByCode(pegasus10).orElseThrow().usedCount()).isEqualTo(1);

        paid(order.orderId());
        assertThat(redemptionStatuses(order.orderId())).containsOnly("CONSUMED");
    }

    @Test
    void checkoutWithoutCodesIsUnchanged() {
        AuthPrincipal buyer = buyerWithFigmaCart();

        CheckoutResponse order = checkout.checkout(buyer, key(), null, new CheckoutRequest(null, deliveries(), null));

        assertThat(order.discountTotal()).isZero();
        assertThat(order.grandTotal()).isEqualByComparingTo("3275.00");
        assertThat(redemptionStatuses(order.orderId())).isEmpty();
    }

    @Test
    void aChangedTotalPlacesNothing() {
        AuthPrincipal buyer = buyerWithFigmaCart();
        int before = available(pikachu);

        assertThatThrownBy(() -> checkout.checkout(buyer, key(), null,
                request(pegasus10, Map.of(), new BigDecimal("3000.00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode().name()).isEqualTo("PROMO_CHANGED");
                    assertThat((BigDecimal) e.details().get("grandTotal")).isEqualByComparingTo("3075.00");
                });

        assertThat(available(pikachu)).isEqualTo(before);
        assertThat(promotions.findByCode(pegasus10).orElseThrow().usedCount()).isZero();
    }

    @Test
    void theLastUseTakenMidCheckoutIsPromoChangedAndRollsBack() {
        AuthPrincipal buyer = buyerWithFigmaCart();
        String lastOne = insert(platform(code("LASTONE"), DiscountType.FIXED, "100", null, "0").limit(1)).code;
        long id = promotions.findByCode(lastOne).orElseThrow().id();
        int before = available(pikachu);
        // Another checkout takes the last use between this one's check and its reservation.
        doAnswer(call -> {
            dsl.update(PROMOTION).set(PROMOTION.USED_COUNT, 1).where(PROMOTION.ID.eq(id)).execute();
            return call.callRealMethod();
        }).when(inventoryPort).reserve(anyMap());

        assertThatThrownBy(() -> checkout.checkout(buyer, key(), null,
                request(lastOne, Map.of(), new BigDecimal("3175.00"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode().name()).isEqualTo("PROMO_CHANGED");
                    assertThat((BigDecimal) e.details().get("grandTotal")).isEqualByComparingTo("3275.00");
                    assertThat(e.details().get("code")).isEqualTo(lastOne);
                });
        assertThat(available(pikachu)).isEqualTo(before);
        assertThat(promotions.findById(id).orElseThrow().usedCount()).isZero();
    }

    @Test
    void twoCheckoutsRacingForTheLastUseGetOneOrder() throws Exception {
        String lastOne = insert(platform(code("RACE"), DiscountType.FIXED, "100", null, "0").limit(1)).code;
        AuthPrincipal first = buyerWithFigmaCart();
        AuthPrincipal second = buyerWithFigmaCart();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (AuthPrincipal buyer : List.of(first, second)) {
                Callable<String> attempt = () -> {
                    start.await();
                    try {
                        checkout.checkout(buyer, key(), null, request(lastOne, Map.of(), new BigDecimal("3175.00")));
                        return "PLACED";
                    } catch (ApiException e) {
                        return e.errorCode().name();
                    }
                };
                outcomes.add(pool.submit(attempt));
            }
            start.countDown();
            List<String> results = List.of(outcomes.get(0).get(), outcomes.get(1).get());
            assertThat(results).containsOnlyOnce("PLACED");
            // Racing, the loser trips on the guarded UPDATE; a beat late, it sees the code used up.
            assertThat(results).anyMatch(r -> r.equals("PROMO_CHANGED") || r.equals("PROMO_USED_UP"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(promotions.findByCode(lastOne).orElseThrow().usedCount()).isEqualTo(1);
    }

    @Test
    void paymentTimeoutGivesTheUseBack() {
        AuthPrincipal buyer = buyerWithFigmaCart();
        CheckoutResponse order = checkout.checkout(buyer, key(), null, request(pegasus10,
                Map.of(minmin.profileId(), minmin50), new BigDecimal("3025.00")));
        assertThat(promotions.findByCode(minmin50).orElseThrow().usedCount()).isEqualTo(1);

        order.sellerOrders().forEach(so -> lifecycle.expireUnpaidSellerOrder(so.id()));

        assertThat(redemptionStatuses(order.orderId())).containsOnly("RELEASED");
        assertThat(promotions.findByCode(pegasus10).orElseThrow().usedCount()).isZero();
        assertThat(promotions.findByCode(minmin50).orElseThrow().usedCount()).isZero();
        assertThat(dsl.select(SALES_ORDER.STATUS).from(SALES_ORDER).where(SALES_ORDER.ID.eq(order.orderId()))
                .fetchSingle(SALES_ORDER.STATUS)).isEqualTo("CANCELLED");
    }

    @Test
    void everyCheckoutErrorCodeIsReportedPerCode() {
        AuthPrincipal buyer = buyerWithFigmaCart();
        OffsetDateTime now = OffsetDateTime.now(clock);

        assertThat(outcome(buyer, "NOSUCH" + data.tag(), null).status()).isEqualTo("PROMO_NOT_FOUND");
        assertThat(outcome(buyer, minmin50, null).status()).isEqualTo("PROMO_WRONG_SELLER");
        assertThat(outcome(buyer, null, pegasus10).status()).isEqualTo("PROMO_WRONG_SELLER");
        assertThat(outcome(buyer, insert(platform(code("SOON"), DiscountType.FIXED, "10", null, "0")
                .window(now.plusDays(1), now.plusDays(2))).code, null).status()).isEqualTo("PROMO_NOT_STARTED");
        assertThat(outcome(buyer, insert(platform(code("OLD"), DiscountType.FIXED, "10", null, "0")
                .window(now.minusDays(3), now.minusDays(1))).code, null).status()).isEqualTo("PROMO_EXPIRED");
        String paused = insert(platform(code("STOP"), DiscountType.FIXED, "10", null, "0")).code;
        promotions.pause(promotions.findByCode(paused).orElseThrow().id(), data.admin().userId(), null, now);
        assertThat(outcome(buyer, paused, null).status()).isEqualTo("PROMO_PAUSED");
        String gone = insert(platform(code("GONE"), DiscountType.FIXED, "10", null, "0").limit(1)).code;
        promotions.takeUse(promotions.findByCode(gone).orElseThrow().id());
        assertThat(outcome(buyer, gone, null).status()).isEqualTo("PROMO_USED_UP");

        CodeOutcome short1 = outcome(buyer, insert(platform(code("BIG"), DiscountType.FIXED, "300", null, "5000")).code,
                null);
        assertThat(short1.status()).isEqualTo("PROMO_MIN_SPEND_NOT_MET");
        assertThat(short1.shortfall()).isEqualByComparingTo("1770.00");
        assertThat(outcome(buyer, insert(platform(code("YUGI"), DiscountType.PERCENT, "10", null, "0")
                .games(32000L)).code, null).status()).isEqualTo("PROMO_NOT_APPLICABLE");

        String mustClaim = insert(platform(code("CLAIMME"), DiscountType.FIXED, "10", null, "0").claimRequired()).code;
        assertThat(outcome(buyer, mustClaim, null).status()).isEqualTo("PROMO_NOT_CLAIMED");
        claims.insertIfAbsent(promotions.findByCode(mustClaim).orElseThrow().id(), buyer.userId(),
                PromotionClaimSource.HOME);
        assertThat(outcome(buyer, mustClaim, null).status()).isEqualTo("APPLIED");

        String once = insert(platform(code("ONCE"), DiscountType.FIXED, "10", null, "0")).code;
        String firstOrder = insert(platform(code("FIRST"), DiscountType.FIXED, "10", null, "0").newBuyerOnly()).code;
        assertThat(outcome(buyer, firstOrder, null).status()).isEqualTo("APPLIED");
        CheckoutResponse placed = checkout.checkout(buyer, key(), null,
                request(once, Map.of(), new BigDecimal("3265.00")));
        paid(placed.orderId());
        AuthPrincipal again = refill(buyer);
        assertThat(outcome(again, once, null).status()).isEqualTo("PROMO_USER_LIMIT_REACHED");
        assertThat(outcome(again, firstOrder, null).status()).isEqualTo("PROMO_NEW_BUYER_ONLY");

        // Checkout refuses a code the same way, and places nothing.
        String notClaimed = insert(platform(code("NOTMINE"), DiscountType.FIXED, "10", null, "0").claimRequired()).code;
        assertThatThrownBy(() -> checkout.checkout(again, key(), null, request(notClaimed, Map.of(), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode().name()).isEqualTo("PROMO_NOT_CLAIMED"));
    }

    @Test
    void guestsPreviewThroughTheirCartSession() {
        ApiClient api = new ApiClient(port);
        Response added = api.post("/api/v1/cart/items", null, Map.of("listingId", pikachu.id(), "quantity", 1));
        String session = added.header("X-Cart-Session").orElseThrow();
        Map<String, String> headers = Map.of("X-Cart-Session", session);
        String mustClaim = insert(platform(code("GUESTCLM"), DiscountType.FIXED, "10", null, "0").claimRequired()).code;

        Response ok = api.post("/api/v1/checkout/promo-preview", null,
                Map.of("platformCode", pegasus10.toLowerCase(Locale.ROOT)), headers);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.data().path("codes").get(0).path("status").asString()).isEqualTo("APPLIED");
        assertThat(ok.data().path("grandTotal").decimalValue()).isEqualByComparingTo("405.00");

        Response refused = api.post("/api/v1/checkout/promo-preview", null, Map.of("platformCode", mustClaim),
                headers);
        assertThat(refused.data().path("codes").get(0).path("status").asString()).isEqualTo("PROMO_NOT_CLAIMED");
    }

    @Test
    void pickerGroupsCollectedClaimableAndPublicCodes() {
        AuthPrincipal buyer = buyerWithFigmaCart();
        claims.insertIfAbsent(promotions.findByCode(minmin50).orElseThrow().id(), buyer.userId(),
                PromotionClaimSource.STORE);
        String toCollect = insert(platform(code("COLLECT"), DiscountType.FIXED, "30", null, "0")
                .claimable(100, 40)).code;

        List<AvailablePromotion> picker = checkout.availablePromotions(buyer, null, null);

        AvailablePromotion collected = find(picker, minmin50);
        assertThat(collected.source()).isEqualTo(Source.CLAIMED);
        assertThat(collected.applicable()).isTrue();
        assertThat(collected.estimatedDiscount()).isEqualByComparingTo("50.00");
        AvailablePromotion claimable = find(picker, toCollect);
        assertThat(claimable.source()).isEqualTo(Source.CLAIMABLE);
        assertThat(claimable.remainingClaims()).isEqualTo(60);
        AvailablePromotion open = find(picker, pegasus10);
        assertThat(open.source()).isEqualTo(Source.PUBLIC);
        assertThat(open.estimatedDiscount()).isEqualByComparingTo("200.00");
        assertThat(picker).noneMatch(p -> p.ownerType() == OwnerType.SELLER
                && !List.of(minmin.profileId(), cardVault.profileId()).contains(p.sellerProfileId()));
    }

    // ---------- helpers ----------

    /** Marks the order paid the way the payment flow does, inside its transaction. */
    private void paid(long salesOrderId) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> lifecycle.markOrderPaidBySystem(salesOrderId, null));
    }

    private AuthPrincipal buyerWithFigmaCart() {
        AuthPrincipal buyer = data.buyer();
        for (Listing l : List.of(pikachu, gengar, luffy)) cart.addItem(buyer, null, new CartItemRequest(l.id(), 1));
        return buyer;
    }

    /** The same buyer with the Figma cart again, after an order emptied it. */
    private AuthPrincipal refill(AuthPrincipal buyer) {
        for (Listing l : List.of(pikachu, gengar, luffy)) cart.addItem(buyer, null, new CartItemRequest(l.id(), 1));
        return buyer;
    }

    private CodeOutcome outcome(AuthPrincipal buyer, String platformCode, String minminCode) {
        Map<Long, String> shops = minminCode == null ? Map.of() : Map.of(minmin.profileId(), minminCode);
        return checkout.promoPreview(buyer, null,
                new PromoPreviewRequest(null, deliveries(), platformCode, shops, null)).codes().getFirst();
    }

    private CheckoutRequest request(String platformCode, Map<Long, String> sellerCodes, BigDecimal expected) {
        return new CheckoutRequest(null, deliveries(), null, null, platformCode, sellerCodes, expected);
    }

    private Map<Long, Long> deliveries() {
        return Map.of(minmin.profileId(), minminShipping, cardVault.profileId(), cardVaultShipping);
    }

    private long shipping(Seller seller, String fee) {
        return dsl.insertInto(SELLER_SHIPPING_OPTION)
                .set(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_SHIPPING_OPTION.NAME, "Kerry Express")
                .set(SELLER_SHIPPING_OPTION.BASE_FEE, new BigDecimal(fee))
                .set(SELLER_SHIPPING_OPTION.PER_ITEM_FEE, BigDecimal.ZERO)
                .set(SELLER_SHIPPING_OPTION.IS_ACTIVE, true)
                .set(SELLER_SHIPPING_OPTION.DISPLAY_ORDER, (short) 0)
                .returningResult(SELLER_SHIPPING_OPTION.ID)
                .fetchSingle(SELLER_SHIPPING_OPTION.ID);
    }

    private SellerOrderRecord sellerOrder(long salesOrderId, Seller seller) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId))
                .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(seller.profileId()))
                .fetchSingle();
    }

    private BigDecimal allocated(long sellerOrderId, long listingId) {
        return dsl.select(ORDER_ITEM.DISCOUNT_ALLOCATED).from(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId)).and(ORDER_ITEM.LISTING_ID.eq(listingId))
                .fetchSingle(ORDER_ITEM.DISCOUNT_ALLOCATED);
    }

    private List<String> redemptionStatuses(long salesOrderId) {
        return dsl.select(PROMOTION_REDEMPTION.STATUS).from(PROMOTION_REDEMPTION)
                .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(salesOrderId))
                .fetch(PROMOTION_REDEMPTION.STATUS);
    }

    private int available(Listing listing) {
        return dsl.fetchCount(com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT,
                com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT.LISTING_ID.eq(listing.id())
                        .and(com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT.STATUS.eq("AVAILABLE")));
    }

    private static AvailablePromotion find(List<AvailablePromotion> picker, String code) {
        return picker.stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow();
    }

    private String key() {
        return "promo-" + data.tag();
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    // ---------- promotion builder ----------

    private Draft platform(String code, DiscountType type, String value, String max, String minSpend) {
        return new Draft(code, OwnerType.PLATFORM, null, type, value, max, minSpend);
    }

    private Draft shop(String code, Seller seller, DiscountType type, String value, String minSpend) {
        return new Draft(code, OwnerType.SELLER, seller.profileId(), type, value, null, minSpend);
    }

    private Draft insert(Draft d) {
        long id = promotions.insert(d.build());
        if (d.claimed > 0) {
            dsl.update(PROMOTION).set(PROMOTION.CLAIMED_COUNT, d.claimed).where(PROMOTION.ID.eq(id)).execute();
        }
        return d;
    }

    private final class Draft {
        final String code;
        final OwnerType owner;
        final Long shop;
        final DiscountType type;
        final String value;
        final String max;
        final String minSpend;
        OffsetDateTime starts = OffsetDateTime.now(clock).minusDays(1);
        OffsetDateTime ends = OffsetDateTime.now(clock).plusDays(30);
        Integer limit;
        ScopeType scope = ScopeType.ALL;
        List<Long> scopeIds = List.of();
        boolean claimable;
        boolean claimRequired;
        Integer claimLimit;
        int claimed;
        boolean newBuyerOnly;

        Draft(String code, OwnerType owner, Long shop, DiscountType type, String value, String max, String minSpend) {
            this.code = code;
            this.owner = owner;
            this.shop = shop;
            this.type = type;
            this.value = value;
            this.max = max;
            this.minSpend = minSpend;
        }

        Draft endsIn(int days) {
            ends = OffsetDateTime.now(clock).plusDays(days);
            return this;
        }

        Draft window(OffsetDateTime s, OffsetDateTime e) {
            starts = s;
            ends = e;
            return this;
        }

        Draft limit(int total) {
            limit = total;
            return this;
        }

        Draft games(Long... ids) {
            scope = ScopeType.GAME;
            scopeIds = List.of(ids);
            return this;
        }

        Draft claimRequired() {
            claimable = true;
            claimRequired = true;
            return this;
        }

        Draft claimable(int limit, int alreadyClaimed) {
            claimable = true;
            claimLimit = limit;
            claimed = alreadyClaimed;
            return this;
        }

        Draft newBuyerOnly() {
            newBuyerOnly = true;
            return this;
        }

        NewPromotion build() {
            return new NewPromotion(code, code, null, owner, shop, type,
                    type == DiscountType.FREE_SHIPPING ? BigDecimal.ZERO : new BigDecimal(value),
                    max == null ? null : new BigDecimal(max), new BigDecimal(minSpend), scope, scopeIds,
                    Visibility.PUBLIC, newBuyerOnly, limit, 1, claimable, claimRequired, claimLimit, false, null,
                    starts, ends, null);
        }
    }
}
