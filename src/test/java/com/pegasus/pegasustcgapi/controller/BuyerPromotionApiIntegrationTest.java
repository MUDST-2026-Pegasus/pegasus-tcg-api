package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
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
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class BuyerPromotionApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PROMOTIONS = "/api/v1/promotions";
    private static final String WALLET = "/api/v1/me/promotions";

    @Autowired PromotionRepository promotions;
    @Autowired PromotionClaimRepository claims;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private ApiClient api;
    private AuthPrincipal buyer;
    private String bearer;

    @BeforeEach
    void signIn() {
        api = new ApiClient(port);
        buyer = data.buyer();
        bearer = data.bearer(buyer);
    }

    @Test
    void collectingTwiceReturnsTheFirstClaimAndCountsOnce() {
        long id = insert(code("TWICE"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, 10);

        Response first = api.post(PROMOTIONS + "/" + id + "/claim", bearer, Map.of());
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.data().path("alreadyClaimed").asBoolean()).isFalse();
        assertThat(first.data().path("claim").path("source").asString()).isEqualTo("HOME");

        Response second = api.post(PROMOTIONS + "/" + id + "/claim", bearer, Map.of("source", "CART"));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.data().path("alreadyClaimed").asBoolean()).isTrue();
        assertThat(second.data().path("claim").path("id").asLong())
                .isEqualTo(first.data().path("claim").path("id").asLong());
        assertThat(second.data().path("claim").path("source").asString()).isEqualTo("HOME");
        assertThat(promotions.findById(id).orElseThrow().claimedCount()).isEqualTo(1);
    }

    @Test
    void guestCannotCollect() {
        long id = insert(code("GUEST"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null);

        assertThat(api.post(PROMOTIONS + "/" + id + "/claim", null, Map.of()).status()).isEqualTo(401);
        assertThat(api.post(WALLET + "/claim-by-code", null, Map.of("code", "ANY1")).status()).isEqualTo(401);
        assertThat(api.get(WALLET, null).status()).isEqualTo(401);
    }

    @Test
    void twoBuyersRacingForTheLastCopyLeaveOneWithoutIt() throws Exception {
        long id = insert(code("RACE"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, 1);
        List<String> bearers = List.of(data.bearer(data.buyer()), data.bearer(data.buyer()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Response>> calls = new ArrayList<>();
            for (String b : bearers) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return api.post(PROMOTIONS + "/" + id + "/claim", b, Map.of());
                }));
            }
            start.countDown();
            List<Response> results = new ArrayList<>();
            for (Future<Response> f : calls) results.add(f.get());

            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(results).filteredOn(r -> r.status() == 409)
                    .extracting(Response::errorCode).containsExactly("PROMO_CLAIM_FULL");
        } finally {
            pool.shutdownNow();
        }
        assertThat(promotions.findById(id).orElseThrow().claimedCount()).isEqualTo(1);
    }

    @Test
    void codesThatCannotBeCollectedAreRefused() {
        long notClaimable = insert(code("NOCL"), OwnerType.PLATFORM, null, Visibility.PUBLIC, false, null);
        long hidden = insert(code("HIDE"), OwnerType.PLATFORM, null, Visibility.PRIVATE, false, null);
        long paused = insert(code("PAUS"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null);
        promotions.pause(paused, data.admin().userId(), null, now());
        long ended = insert(code("ENDD"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null);
        endedYesterday(ended);

        assertThat(claim(notClaimable).errorCode()).isEqualTo("PROMO_NOT_FOUND");
        assertThat(claim(hidden).errorCode()).isEqualTo("PROMO_NOT_FOUND");
        assertThat(claim(paused).errorCode()).isEqualTo("PROMO_PAUSED");
        assertThat(claim(ended).errorCode()).isEqualTo("PROMO_EXPIRED");
        assertThat(api.post(PROMOTIONS + "/999999999/claim", bearer, Map.of()).status()).isEqualTo(404);
    }

    @Test
    void typedCodesIncludingPrivateOnesGoIntoTheWallet() {
        String hiddenCode = code("PRIV");
        long hidden = insert(hiddenCode, OwnerType.PLATFORM, null, Visibility.PRIVATE, false, null);

        assertThat(api.get(PROMOTIONS + "/" + hiddenCode + "/terms", bearer).status()).isEqualTo(404);

        Response r = api.post(WALLET + "/claim-by-code", bearer, Map.of("code", hiddenCode.toLowerCase(Locale.ROOT)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.data().path("claim").path("promotionId").asLong()).isEqualTo(hidden);
        assertThat(r.data().path("claim").path("source").asString()).isEqualTo("WALLET_INPUT");

        assertThat(api.get(PROMOTIONS + "/" + hiddenCode + "/terms", bearer).data().path("claimed").asBoolean())
                .isTrue();
        assertThat(api.get(PROMOTIONS + "/" + hiddenCode + "/terms", null).status()).isEqualTo(404);
        assertThat(api.post(WALLET + "/claim-by-code", bearer, Map.of("code", "NOPE" + data.tag().toUpperCase(Locale.ROOT)))
                .errorCode()).isEqualTo("PROMO_NOT_FOUND");
    }

    @Test
    void termsNameTheGamesACodeCovers() {
        short game = data.game("Terms game " + data.tag());
        String c = code("TERM");
        promotions.insert(new NewPromotion(c, "Game code", "For one game", OwnerType.PLATFORM, null,
                DiscountType.PERCENT, new BigDecimal("10"), new BigDecimal("200"), new BigDecimal("500"),
                ScopeType.GAME, List.of((long) game), Visibility.PUBLIC, true, null, 2, true, false, 100, false,
                null, now().minusDays(1), now().plusDays(30), null));

        JsonNode terms = api.get(PROMOTIONS + "/" + c + "/terms", null).data();
        assertThat(terms.path("scopeType").asString()).isEqualTo("GAME");
        assertThat(terms.path("scopeTargets").get(0).path("id").asLong()).isEqualTo(game);
        assertThat(terms.path("scopeTargets").get(0).path("name").asString()).startsWith("Terms game");
        assertThat(terms.path("usageLimitPerUser").asInt()).isEqualTo(2);
        assertThat(terms.path("newBuyerOnly").asBoolean()).isTrue();
        assertThat(terms.path("stacksWithShopCodes").asBoolean()).isTrue();
        assertThat(terms.path("remainingClaims").asInt()).isEqualTo(100);
        assertThat(terms.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(terms.path("claimed").asBoolean()).isFalse();
    }

    @Test
    void homeStripFollowsHomeSortAndLeavesShopCodesOut() {
        long a = insertFeatured(code("HOMEA"));
        long b = insertFeatured(code("HOMEB"));
        long paused = insertFeatured(code("HOMEP"));
        promotions.pause(paused, data.admin().userId(), null, now());
        promotions.replaceHomeOrder(List.of(b, paused, a));
        Seller shop = data.seller();
        insert(code("SHOPH"), OwnerType.SELLER, shop.profileId(), Visibility.PUBLIC, true, null);
        api.post(PROMOTIONS + "/" + a + "/claim", bearer, Map.of());

        Response guest = api.get(PROMOTIONS + "/featured", null);
        assertThat(ids(guest.data())).containsExactly(b, a);
        assertThat(guest.data().get(1).path("claimed").asBoolean()).isFalse();
        assertThat(guest.header("Cache-Control").orElseThrow()).contains("max-age=60").contains("public");

        Response signedIn = api.get(PROMOTIONS + "/featured", bearer);
        assertThat(ids(signedIn.data())).containsExactly(b, a);
        assertThat(signedIn.data().get(0).path("claimed").asBoolean()).isFalse();
        assertThat(signedIn.data().get(1).path("claimed").asBoolean()).isTrue();
        assertThat(signedIn.header("Cache-Control").orElseThrow()).contains("no-store");
    }

    @Test
    void storeStripShowsTheShopsLivePublicCollectableCodes() {
        Seller shop = data.seller();
        long open = insert(code("STOA"), OwnerType.SELLER, shop.profileId(), Visibility.PUBLIC, true, 5);
        insert(code("STOB"), OwnerType.SELLER, shop.profileId(), Visibility.PUBLIC, false, null);
        long ended = insert(code("STOC"), OwnerType.SELLER, shop.profileId(), Visibility.PUBLIC, true, null);
        endedYesterday(ended);
        insert(code("STOD"), OwnerType.SELLER, data.seller().profileId(), Visibility.PUBLIC, true, null);
        String username = shop.principal().username();

        Response r = api.get("/api/v1/u/" + username + "/promotions", null);
        assertThat(ids(r.data())).containsExactly(open);
        JsonNode ticket = r.data().get(0);
        assertThat(ticket.path("remainingClaims").asInt()).isEqualTo(5);
        assertThat(ticket.path("shop").path("username").asString()).isEqualTo(username);

        Response claimed = api.post(PROMOTIONS + "/" + open + "/claim", bearer, Map.of());
        assertThat(claimed.data().path("claim").path("source").asString()).isEqualTo("STORE");
        JsonNode after = api.get("/api/v1/u/" + username + "/promotions", bearer).data().get(0);
        assertThat(after.path("claimed").asBoolean()).isTrue();
        assertThat(after.path("remainingClaims").asInt()).isEqualTo(4);

        assertThat(api.get("/api/v1/u/" + data.buyer().username() + "/promotions", null).data()).isEmpty();
    }

    @Test
    void walletSortsCodesIntoAvailableUsedAndExpired() {
        Seller shop = data.seller();
        long available = collected(insert(code("WAVA"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null));
        long shopCode = collected(insert(code("WSHP"), OwnerType.SELLER, shop.profileId(), Visibility.PUBLIC, true, null));
        long used = collected(insert(code("WUSE"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null));
        long salesOrderId = placeOrder(shop);
        dsl.insertInto(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.PROMOTION_ID, used)
                .set(PROMOTION_REDEMPTION.BUYER_ID, buyer.userId())
                .set(PROMOTION_REDEMPTION.SALES_ORDER_ID, salesOrderId)
                .set(PROMOTION_REDEMPTION.AMOUNT, new BigDecimal("20.00"))
                .set(PROMOTION_REDEMPTION.STATUS, "CONSUMED")
                .execute();
        long expired = collected(insert(code("WEXP"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null));
        endedYesterday(expired);
        long longGone = collected(insert(code("WOLD"), OwnerType.PLATFORM, null, Visibility.PUBLIC, true, null));
        dsl.update(PROMOTION)
                .set(PROMOTION.STARTS_AT, now().minusDays(90))
                .set(PROMOTION.ENDS_AT, now().minusDays(31))
                .where(PROMOTION.ID.eq(longGone))
                .execute();

        JsonNode availableTab = api.get(WALLET + "?tab=AVAILABLE", bearer).data();
        assertThat(ids(availableTab.path("items"))).containsExactlyInAnyOrder(available, shopCode);
        assertThat(availableTab.path("counts").path("AVAILABLE").asInt()).isEqualTo(2);
        assertThat(availableTab.path("counts").path("USED").asInt()).isEqualTo(1);
        assertThat(availableTab.path("counts").path("EXPIRED").asInt()).isEqualTo(1);

        JsonNode usedTab = api.get(WALLET + "?tab=USED", bearer).data();
        assertThat(ids(usedTab.path("items"))).containsExactly(used);
        JsonNode use = usedTab.path("items").get(0).path("uses").get(0);
        assertThat(use.path("salesOrderId").asLong()).isEqualTo(salesOrderId);
        assertThat(use.path("orderNumber").asString()).isNotBlank();
        assertThat(usedTab.path("items").get(0).path("usesLeft").asInt()).isZero();

        assertThat(ids(api.get(WALLET + "?tab=EXPIRED", bearer).data().path("items"))).containsExactly(expired);

        JsonNode shopsOnly = api.get(WALLET + "?tab=AVAILABLE&owner=SELLER", bearer).data();
        assertThat(ids(shopsOnly.path("items"))).containsExactly(shopCode);
        assertThat(shopsOnly.path("items").get(0).path("shop").path("username").asString())
                .isEqualTo(shop.principal().username());
        assertThat(shopsOnly.path("counts").path("USED").asInt()).isZero();
    }

    private Response claim(long id) {
        return api.post(PROMOTIONS + "/" + id + "/claim", bearer, Map.of());
    }

    private long collected(long id) {
        claims.insertIfAbsent(id, buyer.userId(), PromotionClaimSource.HOME);
        return id;
    }

    private long placeOrder(Seller shop) {
        var card = data.card(data.game("Pokemon"), "Wallet promo " + data.tag(), "AR");
        var listing = data.onSale(shop, card, CardCondition.NM, 1, "700.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        return checkout.checkout(buyer, "wallet-promo-" + data.tag(), null, null).orderId();
    }

    private long insert(String code, OwnerType owner, Long sellerProfileId, Visibility visibility,
            boolean claimable, Integer claimLimit) {
        return promotions.insert(new NewPromotion(code, code, null, owner, sellerProfileId,
                DiscountType.FIXED, new BigDecimal("20"), null, BigDecimal.ZERO, ScopeType.ALL, List.of(),
                visibility, false, null, 1, claimable, false, claimLimit, false, null,
                now().minusDays(1), now().plusDays(30), null));
    }

    private long insertFeatured(String code) {
        return promotions.insert(new NewPromotion(code, code, null, OwnerType.PLATFORM, null,
                DiscountType.FIXED, new BigDecimal("20"), null, BigDecimal.ZERO, ScopeType.ALL, List.of(),
                Visibility.PUBLIC, false, null, 1, true, false, null, true, promotions.nextHomeSort(),
                now().minusDays(1), now().plusDays(30), null));
    }

    private void endedYesterday(long id) {
        dsl.update(PROMOTION)
                .set(PROMOTION.STARTS_AT, now().minusDays(10))
                .set(PROMOTION.ENDS_AT, now().minusDays(1))
                .where(PROMOTION.ID.eq(id))
                .execute();
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
