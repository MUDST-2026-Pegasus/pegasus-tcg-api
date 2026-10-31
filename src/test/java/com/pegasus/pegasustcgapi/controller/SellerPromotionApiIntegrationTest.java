package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class SellerPromotionApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/sellers/me/promotions";
    private static final String ADMIN_PATH = "/api/v1/admin/promotions";

    @Autowired PromotionRepository promotions;
    @Autowired PromotionClaimRepository claims;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private ApiClient api;
    private Seller shop;
    private String seller;

    @BeforeEach
    void signIn() {
        api = new ApiClient(port);
        shop = data.seller();
        seller = data.bearer(shop.principal());
    }

    @Test
    void shopCreatesListsUpdatesAndDeletesItsOwnCode() {
        String code = code("SHOP");
        Response created = api.post(PATH, seller, form(code.toLowerCase(Locale.ROOT)));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        JsonNode body = created.data();
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("discountTotal").decimalValue()).isZero();
        long id = body.path("id").asLong();
        assertThat(promotions.findById(id).orElseThrow().ownerType()).isEqualTo(OwnerType.SELLER);
        assertThat(promotions.findById(id).orElseThrow().sellerProfileId()).isEqualTo(shop.profileId());

        Response list = api.get(PATH + "?status=ACTIVE", seller);
        assertThat(list.data().path("page").path("items")).hasSize(1);
        assertThat(list.data().path("statusCounts").path("ACTIVE").asLong()).isEqualTo(1);

        Map<String, Object> renamed = form(code);
        renamed.put("name", "Shop 50 off");
        renamed.put("discountValue", 40);
        Response updated = api.put(PATH + "/" + id, seller, renamed);
        assertThat(updated.data().path("name").asString()).isEqualTo("Shop 50 off");
        assertThat(updated.data().path("discountValue").decimalValue()).isEqualByComparingTo("40");

        assertThat(api.delete(PATH + "/" + id, seller).status()).isEqualTo(200);
        assertThat(api.get(PATH + "/" + id, seller).errorCode()).isEqualTo("PROMO_NOT_FOUND");
    }

    @Test
    void anotherShopsCodeAndPegasusCodesAreNotFound() {
        long mine = create(form(code("MINE")));
        Seller other = data.seller();
        String otherBearer = data.bearer(other.principal());
        long pegasus = platformCode(code("PEG"));

        for (long id : List.of(mine, pegasus)) {
            String bearer = id == mine ? otherBearer : seller;
            assertThat(api.get(PATH + "/" + id, bearer).status()).isEqualTo(404);
            assertThat(api.put(PATH + "/" + id, bearer, form(code("X"))).errorCode()).isEqualTo("PROMO_NOT_FOUND");
            assertThat(api.delete(PATH + "/" + id, bearer).status()).isEqualTo(404);
            assertThat(api.post(PATH + "/" + id + "/pause", bearer, Map.of()).status()).isEqualTo(404);
            assertThat(api.post(PATH + "/" + id + "/resume", bearer, Map.of()).status()).isEqualTo(404);
            assertThat(api.get(PATH + "/" + id + "/redemptions", bearer).status()).isEqualTo(404);
        }
        assertThat(api.get(PATH, otherBearer).data().path("page").path("totalItems").asLong()).isZero();
    }

    @Test
    void unverifiedShopCannotCreate() {
        AuthPrincipal applicant = data.buyer();
        dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, applicant.userId())
                .set(SELLER_PROFILE.STATUS, "PENDING")
                .execute();

        Response r = api.post(PATH, data.bearer(applicant), form(code("PEND")));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.errorCode()).isEqualTo("SELLER_NOT_VERIFIED");
    }

    @Test
    void pegasusOnlySettingsAndBadValuesAreRefused() {
        Map<String, Object> featured = form(code("FEAT"));
        featured.put("featuredOnHome", true);
        assertThat(api.post(PATH, seller, featured).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> mustClaim = form(code("MUST"));
        mustClaim.put("claimable", true);
        mustClaim.put("claimRequired", true);
        assertThat(api.post(PATH, seller, mustClaim).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> freeShip = form(code("SHIP"));
        freeShip.put("discountType", "FREE_SHIPPING");
        assertThat(api.post(PATH, seller, freeShip).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> overMin = form(code("OVER"));
        overMin.put("discountValue", 600);
        assertThat(api.post(PATH, seller, overMin).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> privateClaimable = form(code("PRIVC"));
        privateClaimable.put("visibility", "PRIVATE");
        privateClaimable.put("claimable", true);
        assertThat(api.post(PATH, seller, privateClaimable).errorCode()).isEqualTo("PROMO_INVALID");

        Map<String, Object> claimable = form(code("CLAIMOK"));
        claimable.put("claimable", true);
        claimable.put("claimLimit", 100);
        claimable.put("featuredOnHome", false);
        Response ok = api.post(PATH, seller, claimable);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.data().path("claimLimit").asInt()).isEqualTo(100);

        String taken = code("TAKEN");
        platformCode(taken);
        assertThat(api.post(PATH, seller, form(taken)).errorCode()).isEqualTo("PROMO_CODE_TAKEN");
    }

    @Test
    void usedAndClaimedCodesFollowTheSameRulesAsPegasusCodes() {
        Map<String, Object> form = form(code("RULES"));
        form.put("claimable", true);
        form.put("claimLimit", 100);
        long id = create(form);
        promotions.takeUse(id);
        claims.insertIfAbsent(id, data.buyer().userId(), PromotionClaimSource.STORE);
        promotions.takeClaimSlot(id);

        Map<String, Object> newValue = new LinkedHashMap<>(form);
        newValue.put("discountValue", 30);
        assertThat(api.put(PATH + "/" + id, seller, newValue).errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> fewerClaims = new LinkedHashMap<>(form);
        fewerClaims.put("claimLimit", 50);
        assertThat(api.put(PATH + "/" + id, seller, fewerClaims).errorCode()).isEqualTo("PROMO_IN_USE");

        Map<String, Object> allowed = new LinkedHashMap<>(form);
        allowed.put("name", "Longer sale");
        allowed.put("endsAt", OffsetDateTime.parse((String) form.get("endsAt")).plusDays(7).toString());
        allowed.put("claimLimit", 300);
        Response ok = api.put(PATH + "/" + id, seller, allowed);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().path("claimLimit").asInt()).isEqualTo(300);

        Response refused = api.delete(PATH + "/" + id, seller);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("PROMO_IN_USE");
    }

    @Test
    void shopResumesItsOwnPauseButNotOnePegasusMade() {
        long id = create(form(code("PAUSE")));

        Response paused = api.post(PATH + "/" + id + "/pause", seller, Map.of());
        assertThat(paused.data().path("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.data().path("pausedBy").asString()).isEqualTo("SELLER");
        assertThat(api.post(PATH + "/" + id + "/resume", seller, Map.of()).data().path("status").asString())
                .isEqualTo("ACTIVE");

        String admin = data.bearer(data.admin());
        api.post(ADMIN_PATH + "/" + id + "/pause", admin, Map.of("reason", "Price looks wrong"));

        JsonNode seen = api.get(PATH + "/" + id, seller).data();
        assertThat(seen.path("pausedBy").asString()).isEqualTo("ADMIN");
        assertThat(seen.path("pauseReason").asString()).isEqualTo("Price looks wrong");

        Response blocked = api.post(PATH + "/" + id + "/resume", seller, Map.of());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.errorCode()).isEqualTo("PROMO_PAUSED");
        // Pausing again changes nothing; the shop still cannot lift Pegasus's pause.
        assertThat(api.post(PATH + "/" + id + "/pause", seller, Map.of()).data().path("pausedBy").asString())
                .isEqualTo("ADMIN");

        api.post(ADMIN_PATH + "/" + id + "/resume", admin, Map.of());
        assertThat(api.get(PATH + "/" + id, seller).data().path("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void pegasusPausingAShopPausedCodeTakesThePauseOver() {
        long id = create(form(code("OVER")));
        api.post(PATH + "/" + id + "/pause", seller, Map.of());

        String admin = data.bearer(data.admin());
        Response taken = api.post(ADMIN_PATH + "/" + id + "/pause", admin,
                Map.of("reason", "Fake\r\npromotion.resume id=1"));
        assertThat(taken.status()).isEqualTo(200);

        JsonNode seen = api.get(PATH + "/" + id, seller).data();
        assertThat(seen.path("pausedBy").asString()).isEqualTo("ADMIN");
        // Control characters do not reach the shop's view or the audit log.
        assertThat(seen.path("pauseReason").asString()).isEqualTo("Fake  promotion.resume id=1");
        Response blocked = api.post(PATH + "/" + id + "/resume", seller, Map.of());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.errorCode()).isEqualTo("PROMO_PAUSED");

        // A second Pegasus pause keeps the first reason.
        api.post(ADMIN_PATH + "/" + id + "/pause", admin, Map.of("reason", "Another"));
        assertThat(api.get(PATH + "/" + id, seller).data().path("pauseReason").asString())
                .isEqualTo("Fake  promotion.resume id=1");
    }

    @Test
    void aSuspendedShopCanPauseButNotEditOrResume() {
        long id = create(form(code("SUSP")));
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "SUSPENDED")
                .where(SELLER_PROFILE.ID.eq(shop.profileId())).execute();

        assertThat(api.post(PATH + "/" + id + "/pause", seller, Map.of()).status()).isEqualTo(200);
        Response resumed = api.post(PATH + "/" + id + "/resume", seller, Map.of());
        assertThat(resumed.status()).isEqualTo(403);
        assertThat(resumed.errorCode()).isEqualTo("SELLER_NOT_VERIFIED");
        Response edited = api.put(PATH + "/" + id, seller, form(code("SUSP")));
        assertThat(edited.status()).isEqualTo(403);
        assertThat(edited.errorCode()).isEqualTo("SELLER_NOT_VERIFIED");
    }

    @Test
    void redemptionsShowOnlyTheBuyersDisplayNameAndFeedTheSummary() {
        Response before = api.get(PATH + "/summary", seller);
        long id = create(form(code("SOLD")));
        AuthPrincipal buyer = data.buyer();
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.DISPLAY_NAME, "Somchai J.")
                .where(USER_ACCOUNT.ID.eq(buyer.userId())).execute();
        long salesOrderId = placeOrder(buyer);
        dsl.insertInto(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.PROMOTION_ID, id)
                .set(PROMOTION_REDEMPTION.BUYER_ID, buyer.userId())
                .set(PROMOTION_REDEMPTION.SALES_ORDER_ID, salesOrderId)
                .set(PROMOTION_REDEMPTION.AMOUNT, new BigDecimal("50.00"))
                .set(PROMOTION_REDEMPTION.STATUS, "CONSUMED")
                .execute();

        Response page = api.get(PATH + "/" + id + "/redemptions", seller);
        JsonNode row = page.data().path("items").get(0);
        assertThat(row.path("sellerOrderNumber").asString()).isNotBlank();
        assertThat(row.path("buyerDisplayName").asString()).isEqualTo("Somchai J.");
        assertThat(row.toString()).doesNotContain(buyer.username()).doesNotContain(buyer.email());
        assertThat(row.path("amount").decimalValue()).isEqualByComparingTo("50.00");

        assertThat(api.get(PATH + "/" + id, seller).data().path("discountTotal").decimalValue())
                .isEqualByComparingTo("50.00");

        Response after = api.get(PATH + "/summary", seller);
        assertThat(delta(before, after, "activeCodes")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "redemptions30d")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "shopDiscount30d")).isEqualByComparingTo("50.00");
        assertThat(delta(before, after, "salesWithCodes30d")).isEqualByComparingTo(dsl
                .select(SELLER_ORDER.GRAND_TOTAL).from(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId))
                .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(shop.profileId()))
                .fetchSingle(SELLER_ORDER.GRAND_TOTAL));
    }

    private long placeOrder(AuthPrincipal buyer) {
        var card = data.card(data.game("Pokemon"), "Shop promo " + data.tag(), "AR");
        var listing = data.onSale(shop, card, CardCondition.NM, 1, "700.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        return checkout.checkout(buyer, "shop-promo-" + data.tag(), null, null).orderId();
    }

    private static BigDecimal delta(Response before, Response after, String field) {
        return after.data().path(field).decimalValue().subtract(before.data().path(field).decimalValue());
    }

    private long create(Map<String, Object> form) {
        Response r = api.post(PATH, seller, form);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().path("id").asLong();
    }

    private Map<String, Object> form(String code) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("name", "Shop 50");
        form.put("discountType", "FIXED");
        form.put("discountValue", 50);
        form.put("minSpend", 500);
        form.put("usageLimitTotal", 100);
        form.put("startsAt", now().minusDays(1).withNano(0).toString());
        form.put("endsAt", now().plusDays(30).withNano(0).toString());
        return form;
    }

    private long platformCode(String code) {
        return promotions.insert(new NewPromotion(code, "Pegasus", null, OwnerType.PLATFORM, null,
                DiscountType.FIXED, new BigDecimal("20"), null, BigDecimal.ZERO, ScopeType.ALL, List.of(),
                Visibility.PUBLIC, false, null, 1, false, false, null, false, null,
                now().minusDays(1), now().plusDays(30), null));
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
