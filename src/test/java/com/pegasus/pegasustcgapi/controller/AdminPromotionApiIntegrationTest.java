package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AdminPromotionApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/admin/promotions";

    @Autowired PromotionRepository promotions;
    @Autowired PromotionClaimRepository claims;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private ApiClient api;
    private AuthPrincipal adminUser;
    private String admin;

    @BeforeEach
    void signIn() {
        api = new ApiClient(port);
        adminUser = data.admin();
        admin = data.bearer(adminUser);
    }

    @Test
    void adminCreatesListsAndReadsAPegasusCode() {
        String code = code("NEW");
        Map<String, Object> form = form(code);
        form.put("code", code.toLowerCase(Locale.ROOT));

        Response created = api.post(PATH, admin, form);
        assertThat(created.status()).isEqualTo(201);
        JsonNode body = created.data();
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("ownerType").asString()).isEqualTo("PLATFORM");
        assertThat(body.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("owner").isNull()).isTrue();
        long id = body.path("id").asLong();

        Response list = api.get(PATH + "?owner=PLATFORM&status=ACTIVE&q=" + code.toLowerCase(Locale.ROOT), admin);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data().path("page").path("items")).hasSize(1);
        assertThat(list.data().path("page").path("items").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(list.data().path("statusCounts").path("ACTIVE").asLong()).isEqualTo(1);
        assertThat(list.data().path("statusCounts").path("PAUSED").asLong()).isZero();

        assertThat(api.get(PATH + "/" + id, admin).data().path("name").asString()).isEqualTo("Pegasus 10%");
        assertThat(api.get(PATH + "/999999999", admin).errorCode()).isEqualTo("PROMO_NOT_FOUND");
    }

    @Test
    void onlyAdminsReachTheDesk() {
        String buyer = data.bearer(data.buyer());

        assertThat(api.get(PATH, buyer).status()).isEqualTo(403);
        assertThat(api.post(PATH, buyer, form(code("NOPE"))).status()).isEqualTo(403);
        assertThat(api.get(PATH + "/summary", null).status()).isEqualTo(401);
    }

    @Test
    void createRejectsWhatTheSchemaWouldAndTakenCodes() {
        Map<String, Object> tooMuch = form(code("PCT"));
        tooMuch.put("discountValue", 95);
        assertThat(api.post(PATH, admin, tooMuch).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> privateFeatured = form(code("PRIV"));
        privateFeatured.put("visibility", "PRIVATE");
        privateFeatured.put("claimable", true);
        privateFeatured.put("featuredOnHome", true);
        Response priv = api.post(PATH, admin, privateFeatured);
        assertThat(priv.status()).isEqualTo(400);
        assertThat(priv.errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> unclaimableFeatured = form(code("UNCL"));
        unclaimableFeatured.put("featuredOnHome", true);
        assertThat(api.post(PATH, admin, unclaimableFeatured).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> unknownGame = form(code("GAME"));
        unknownGame.put("scopeType", "GAME");
        unknownGame.put("scopeIds", List.of(32000));
        assertThat(api.post(PATH, admin, unknownGame).errorCode()).isEqualTo("GAME_NOT_FOUND");

        String taken = code("TAKEN");
        assertThat(api.post(PATH, admin, form(taken)).status()).isEqualTo(201);
        Response again = api.post(PATH, admin, form(taken.toLowerCase(Locale.ROOT)));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.errorCode()).isEqualTo("PROMO_CODE_TAKEN");
    }

    @Test
    void aUsedCodeOnlyTakesTheEditsThatCannotChangePastOrders() {
        String code = code("USED");
        long id = create(form(code));
        promotions.takeUse(id);

        Map<String, Object> newValue = form(code);
        newValue.put("discountValue", 20);
        Response refused = api.put(PATH + "/" + id, admin, newValue);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> shorter = form(code);
        shorter.put("endsAt", now().plusDays(5).toString());
        assertThat(api.put(PATH + "/" + id, admin, shorter).errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> allowed = form(code);
        allowed.put("name", "Renamed");
        allowed.put("endsAt", OffsetDateTime.parse((String) allowed.get("endsAt")).plusDays(10).toString());
        allowed.put("usageLimitTotal", 200);
        Response ok = api.put(PATH + "/" + id, admin, allowed);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().path("name").asString()).isEqualTo("Renamed");
        assertThat(ok.data().path("usageLimitTotal").asInt()).isEqualTo(200);
        assertThat(ok.data().path("usedCount").asInt()).isEqualTo(1);

        Map<String, Object> lowerLimit = new LinkedHashMap<>(allowed);
        lowerLimit.put("usageLimitTotal", 150);
        assertThat(api.put(PATH + "/" + id, admin, lowerLimit).errorCode()).isEqualTo("PROMO_IN_USE");
    }

    @Test
    void afterClaimsTheClaimRuleIsFixedButTheStripCanChange() {
        String code = code("CLAIMED");
        Map<String, Object> form = form(code);
        form.put("claimable", true);
        form.put("claimLimit", 100);
        long id = create(form);
        claims.insertIfAbsent(id, data.buyer().userId(), PromotionClaimSource.HOME);
        promotions.takeClaimSlot(id);

        Map<String, Object> mustClaim = new LinkedHashMap<>(form);
        mustClaim.put("claimRequired", true);
        assertThat(api.put(PATH + "/" + id, admin, mustClaim).errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> fewer = new LinkedHashMap<>(form);
        fewer.put("claimLimit", 50);
        assertThat(api.put(PATH + "/" + id, admin, fewer).errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> featured = new LinkedHashMap<>(form);
        featured.put("featuredOnHome", true);
        featured.put("claimLimit", 500);
        Response ok = api.put(PATH + "/" + id, admin, featured);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().path("featuredOnHome").asBoolean()).isTrue();
        assertThat(ok.data().path("claimedCount").asInt()).isEqualTo(1);

        Map<String, Object> closed = new LinkedHashMap<>(form);
        closed.put("claimable", false);
        closed.put("claimLimit", 500);
        assertThat(api.put(PATH + "/" + id, admin, closed).data().path("claimable").asBoolean()).isFalse();
    }

    @Test
    void deleteOnlyTakesPegasusCodesNobodyUsedOrClaimed() {
        long fresh = create(form(code("DEL")));
        assertThat(api.delete(PATH + "/" + fresh, admin).status()).isEqualTo(200);
        assertThat(api.get(PATH + "/" + fresh, admin).status()).isEqualTo(404);

        long used = create(form(code("DELUSED")));
        promotions.takeUse(used);
        Response inUse = api.delete(PATH + "/" + used, admin);
        assertThat(inUse.status()).isEqualTo(409);
        assertThat(inUse.errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> claimable = form(code("DELCLM"));
        claimable.put("claimable", true);
        long claimed = create(claimable);
        claims.insertIfAbsent(claimed, data.buyer().userId(), PromotionClaimSource.WALLET_INPUT);
        promotions.takeClaimSlot(claimed);
        assertThat(api.delete(PATH + "/" + claimed, admin).errorCode()).isEqualTo("PROMO_IN_USE");

        long shop = shopCode(code("DELSHOP"));
        assertThat(api.delete(PATH + "/" + shop, admin).status()).isEqualTo(403);
    }

    @Test
    void adminPausesAShopCodeWithAReasonAndResumesIt() {
        String code = code("SHOP");
        long id = shopCode(code);

        assertThat(api.put(PATH + "/" + id, admin, form(code)).status()).isEqualTo(403);
        assertThat(api.post(PATH + "/" + id + "/pause", admin, Map.of("notifySeller", true)).errorCode())
                .isEqualTo("PROMO_INVALID");

        Response paused = api.post(PATH + "/" + id + "/pause", admin,
                Map.of("reason", "Misleading discount", "notifySeller", true));
        assertThat(paused.status()).isEqualTo(200);
        assertThat(paused.data().path("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.data().path("pauseReason").asString()).isEqualTo("Misleading discount");
        assertThat(paused.data().path("owner").path("username").asString()).isNotBlank();
        assertThat(paused.data().path("pausedBy").path("id").asLong()).isEqualTo(adminUser.userId());
        assertThat(promotions.findById(id).orElseThrow().pauseReason()).isEqualTo("Misleading discount");

        Response resumed = api.post(PATH + "/" + id + "/resume", admin, Map.of("notifySeller", false));
        assertThat(resumed.data().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(resumed.data().path("pauseReason").isNull()).isTrue();
        assertThat(resumed.data().path("pausedBy").isNull()).isTrue();

        long platform = create(form(code("PAUSEPEG")));
        assertThat(api.post(PATH + "/" + platform + "/pause", admin, Map.of()).data().path("status").asString())
                .isEqualTo("PAUSED");
    }

    @Test
    void endStopsAUsedCodeNowAndTakesItOffTheHomePage() {
        long id = create(featurable(code("END"), true));
        promotions.takeUse(id);

        Response ended = api.post(PATH + "/" + id + "/end", admin, Map.of());
        assertThat(ended.status()).isEqualTo(200);
        assertThat(ended.data().path("status").asString()).isEqualTo("EXPIRED");
        assertThat(ended.data().path("featuredOnHome").asBoolean()).isFalse();
        assertThat(ended.data().path("usedCount").asInt()).isEqualTo(1);
        assertThat(promotions.findById(id).orElseThrow().endsAt()).isBefore(now().plusMinutes(1));

        // Ending again leaves the code as it is.
        String endsAt = ended.data().path("endsAt").asString();
        assertThat(api.post(PATH + "/" + id + "/end", admin, Map.of()).data().path("endsAt").asString())
                .isEqualTo(endsAt);

        long later = insertPlatform(code("ENDLATER"), now().plusDays(2), now().plusDays(9), false);
        assertThat(api.post(PATH + "/" + later + "/end", admin, Map.of()).errorCode()).isEqualTo("PROMO_INVALID");

        long shop = shopCode(code("ENDSHOP"));
        assertThat(api.post(PATH + "/" + shop + "/end", admin, Map.of()).status()).isEqualTo(403);
        assertThat(api.post(PATH + "/" + id + "/end", data.bearer(data.buyer()), Map.of()).status()).isEqualTo(403);
    }

    @Test
    void homeOrderReplacesTheWholeStrip() {
        long a = create(featurable(code("HOMEA"), true));
        long b = create(featurable(code("HOMEB"), true));
        long c = create(featurable(code("HOMEC"), false));

        Response saved = api.put(PATH + "/home-order", admin, Map.of("promotionIds", List.of(c, a)));
        assertThat(saved.status()).isEqualTo(200);
        List<Long> strip = new ArrayList<>();
        saved.data().forEach(n -> strip.add(n.path("id").asLong()));
        assertThat(strip).containsExactly(c, a);
        assertThat(promotions.findById(c).orElseThrow().homeSort()).isZero();
        assertThat(promotions.findById(a).orElseThrow().homeSort()).isEqualTo(1);
        assertThat(promotions.findById(b).orElseThrow().featuredOnHome()).isFalse();
        assertThat(promotions.findHomeOrder()).containsExactly(c, a);

        List<Long> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(create(featurable(code("NINE" + i), false)));
        assertThat(api.put(PATH + "/home-order", admin, Map.of("promotionIds", nine)).status()).isEqualTo(400);

        // The form cannot grow the strip past 8 either.
        api.put(PATH + "/home-order", admin, Map.of("promotionIds", nine.subList(0, 8)));
        Map<String, Object> ninth = featurable(code("NINTH"), true);
        assertThat(api.post(PATH, admin, ninth).errorCode()).isEqualTo("PROMO_INVALID");
        api.put(PATH + "/home-order", admin, Map.of("promotionIds", List.of(c, a)));

        long shop = shopCode(code("HOMESHOP"));
        assertThat(api.put(PATH + "/home-order", admin, Map.of("promotionIds", List.of(a, shop))).errorCode())
                .isEqualTo("PROMO_INVALID");

        long expired = insertPlatform(code("HOMEOLD"), now().minusDays(10), now().minusDays(1), true);
        assertThat(api.put(PATH + "/home-order", admin, Map.of("promotionIds", List.of(expired))).errorCode())
                .isEqualTo("PROMO_INVALID");

        Map<String, Object> hidden = featurable(code("HOMEPRIV"), false);
        hidden.put("visibility", "PRIVATE");
        long priv = create(hidden);
        assertThat(api.put(PATH + "/home-order", admin, Map.of("promotionIds", List.of(priv))).errorCode())
                .isEqualTo("PROMO_INVALID");
        assertThat(promotions.findHomeOrder()).containsExactly(c, a);
    }

    @Test
    void redemptionsAndSummaryCountPaidUseOfCodes() {
        Response before = api.get(PATH + "/summary", admin);
        long id = create(form(code("SUM")));
        AuthPrincipal buyer = data.buyer();
        long salesOrderId = placeOrder(buyer);
        dsl.insertInto(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.PROMOTION_ID, id)
                .set(PROMOTION_REDEMPTION.BUYER_ID, buyer.userId())
                .set(PROMOTION_REDEMPTION.SALES_ORDER_ID, salesOrderId)
                .set(PROMOTION_REDEMPTION.AMOUNT, new BigDecimal("10.00"))
                .set(PROMOTION_REDEMPTION.STATUS, "CONSUMED")
                .execute();

        Response page = api.get(PATH + "/" + id + "/redemptions", admin);
        assertThat(page.data().path("totalItems").asLong()).isEqualTo(1);
        JsonNode row = page.data().path("items").get(0);
        assertThat(row.path("salesOrderId").asLong()).isEqualTo(salesOrderId);
        assertThat(row.path("orderNumber").asString()).isNotBlank();
        assertThat(row.path("buyer").path("id").asLong()).isEqualTo(buyer.userId());
        assertThat(row.path("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(row.path("status").asString()).isEqualTo("CONSUMED");

        Response view = api.get(PATH + "/" + id, admin);
        BigDecimal grandTotal = dsl.select(SALES_ORDER.GRAND_TOTAL).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(salesOrderId)).fetchSingle(SALES_ORDER.GRAND_TOTAL);
        assertThat(view.data().path("discountTotal").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(view.data().path("salesTotal").decimalValue()).isEqualByComparingTo(grandTotal);
        assertThat(view.data().path("reservedOrders").asLong()).isZero();
        assertThat(view.data().path("createdBy").path("id").asLong()).isEqualTo(adminUser.userId());
        assertThat(view.data().path("pausedBy").isNull()).isTrue();

        dsl.update(SALES_ORDER)
                .set(SALES_ORDER.STATUS, "PAID")
                .set(SALES_ORDER.PAID_AT, now())
                .where(SALES_ORDER.ID.eq(salesOrderId))
                .execute();

        Response after = api.get(PATH + "/summary", admin);
        assertThat(delta(before, after, "gmv30d")).isEqualByComparingTo(grandTotal);
        assertThat(delta(before, after, "paidOrders30d")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "ordersWithCodes30d")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "redemptions30d")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "activePlatformCodes")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "platformDiscountCost30d")).isEqualByComparingTo("10.00");
        assertThat(delta(before, after, "salesWithCodes30d")).isEqualByComparingTo(grandTotal);
    }

    private long placeOrder(AuthPrincipal buyer) {
        var seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Promo summary " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        return checkout.checkout(buyer, "promo-summary-" + data.tag(), null, null).orderId();
    }

    private static BigDecimal delta(Response before, Response after, String field) {
        return after.data().path(field).decimalValue().subtract(before.data().path(field).decimalValue());
    }

    private long create(Map<String, Object> form) {
        Response r = api.post(PATH, admin, form);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().path("id").asLong();
    }

    private Map<String, Object> featurable(String code, boolean featured) {
        Map<String, Object> form = form(code);
        form.put("claimable", true);
        form.put("featuredOnHome", featured);
        return form;
    }

    private Map<String, Object> form(String code) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("name", "Pegasus 10%");
        form.put("discountType", "PERCENT");
        form.put("discountValue", 10);
        form.put("maxDiscount", 200);
        form.put("minSpend", 0);
        form.put("usageLimitTotal", 100);
        form.put("startsAt", now().minusDays(1).withNano(0).toString());
        form.put("endsAt", now().plusDays(30).withNano(0).toString());
        return form;
    }

    private long shopCode(String code) {
        return promotions.insert(new NewPromotion(code, "Shop 50", null, OwnerType.SELLER, data.seller().profileId(),
                DiscountType.FIXED, new BigDecimal("50"), null, BigDecimal.ZERO, ScopeType.ALL, List.of(),
                Visibility.PUBLIC, false, null, 1, false, false, null, false, null,
                now().minusDays(1), now().plusDays(30), null));
    }

    private long insertPlatform(String code, OffsetDateTime starts, OffsetDateTime ends, boolean claimable) {
        return promotions.insert(new NewPromotion(code, "Old", null, OwnerType.PLATFORM, null,
                DiscountType.FIXED, new BigDecimal("20"), null, BigDecimal.ZERO, ScopeType.ALL, List.of(),
                Visibility.PUBLIC, false, null, 1, claimable, false, null, false, null, starts, ends, null));
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
