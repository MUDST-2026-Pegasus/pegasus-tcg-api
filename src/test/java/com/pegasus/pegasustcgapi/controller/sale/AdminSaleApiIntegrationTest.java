package com.pegasus.pegasustcgapi.controller.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.NewPriceSale;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * /admin/sales against the real schema [TCG-677]. Every sale is scoped to a game
 * of the test's own, so it never reprices another test's listings.
 */
class AdminSaleApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/admin/sales";

    @Autowired PriceSaleRepository sales;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private ApiClient api;
    private String admin;
    private Seller seller;
    private short game;
    private Card card;
    private Listing listing;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = data.bearer(data.admin());
        seller = data.seller();
        game = data.game("Sale desk");
        card = data.card(game, "Sale desk card", "R");
        listing = data.onSale(seller, card, CardCondition.NM, 2, "1000");
    }

    @Test
    void adminCreatesAListsAndReadsACampaignAndItsListingsGoOnSaleAtOnce() {
        String slug = slug("game");
        Response created = api.post(PATH, admin, form(slug));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        JsonNode body = created.data();
        assertThat(body.path("ownerType").asString()).isEqualTo("PLATFORM");
        assertThat(body.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("listingCount").asLong()).isEqualTo(1);
        assertThat(body.path("soldUnits").asLong()).isZero();
        long id = body.path("id").asLong();
        assertThat(salePriceOf(listing)).isEqualByComparingTo("900");

        Response list = api.get(PATH + "?owner=PLATFORM&type=CATEGORY_SALE&status=ACTIVE&q=" + slug, admin);
        assertThat(list.data().path("page").path("items")).hasSize(1);
        assertThat(list.data().path("statusCounts").path("ACTIVE").asLong()).isEqualTo(1);
        assertThat(list.data().path("statusCounts").path("SOLD_OUT").asLong()).isZero();

        JsonNode rows = api.get(PATH + "/" + id + "/listings", admin).data().path("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("listingId").asLong()).isEqualTo(listing.id());
        assertThat(rows.get(0).path("salePrice").decimalValue()).isEqualByComparingTo("900");
        assertThat(rows.get(0).path("seller").path("id").asLong()).isEqualTo(seller.profileId());

        assertThat(api.get(PATH + "/999999999", admin).errorCode()).isEqualTo("SALE_NOT_FOUND");
    }

    @Test
    void onlyAdminsReachTheDesk() {
        String buyer = data.bearer(data.buyer());

        assertThat(api.get(PATH, buyer).status()).isEqualTo(403);
        assertThat(api.post(PATH, buyer, form(slug("nope"))).status()).isEqualTo(403);
        assertThat(api.get(PATH + "/summary", null).status()).isEqualTo(401);
    }

    @Test
    void createRefusesWhatBreaksTheRules() {
        Map<String, Object> tooMuch = form(slug("pct"));
        tooMuch.put("discountValue", 95);
        assertThat(api.post(PATH, admin, tooMuch).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> longFlash = flash(slug("long"));
        longFlash.put("endsAt", iso(now().minusHours(1).plusHours(73)));
        assertThat(api.post(PATH, admin, longFlash).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> quotaOnCategory = form(slug("quota"));
        quotaOnCategory.put("perListingQuota", 5);
        assertThat(api.post(PATH, admin, quotaOnCategory).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> shopType = form(slug("shop"));
        shopType.put("saleType", "SHOP_SALE");
        assertThat(api.post(PATH, admin, shopType).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> backwards = form(slug("back"));
        backwards.put("endsAt", iso(now().minusHours(2)));
        assertThat(api.post(PATH, admin, backwards).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> unknownGame = form(slug("game"));
        unknownGame.put("scopeIds", List.of(32000));
        assertThat(api.post(PATH, admin, unknownGame).errorCode()).isEqualTo("GAME_NOT_FOUND");

        Map<String, Object> badSlug = form("Not A Slug");
        assertThat(api.post(PATH, admin, badSlug).status()).isEqualTo(400);

        String taken = slug("taken");
        assertThat(api.post(PATH, admin, form(taken)).status()).isEqualTo(201);
        Response again = api.post(PATH, admin, form(taken));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.errorCode()).isEqualTo("SALE_INVALID");
    }

    @Test
    void previewScopeCountsWhatThenGoesOnSale() {
        data.onSale(seller, card, CardCondition.HP, 1, "500");
        Seller other = data.seller();
        data.onSale(other, data.card(game, "Other card", "C"), CardCondition.NM, 3, "200");

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scopeType", "GAME");
        scope.put("scopeIds", List.of(game));
        scope.put("conditions", List.of("NM"));
        Response preview = api.post(PATH + "/preview-scope", admin, scope);
        assertThat(preview.status()).as(preview.body().toString()).isEqualTo(200);
        assertThat(preview.data().path("listingCount").asLong()).isEqualTo(2);
        assertThat(preview.data().path("shopCount").asLong()).isEqualTo(2);
        assertThat(preview.data().path("listTotal").decimalValue()).isEqualByComparingTo("2600");

        Map<String, Object> form = form(slug("preview"));
        form.put("conditions", List.of("NM"));
        Response created = api.post(PATH, admin, form);
        assertThat(created.data().path("listingCount").asLong())
                .isEqualTo(preview.data().path("listingCount").asLong());
    }

    @Test
    void pauseResumeAndEndPutListingsBackAtOnce() {
        long id = create(form(slug("pause")));
        assertThat(salePriceOf(listing)).isNotNull();

        Response paused = api.post(PATH + "/" + id + "/pause", admin, Map.of());
        assertThat(paused.data().path("status").asString()).isEqualTo("PAUSED");
        assertThat(salePriceOf(listing)).isNull();

        api.post(PATH + "/" + id + "/resume", admin, Map.of());
        assertThat(salePriceOf(listing)).isEqualByComparingTo("900");

        Response ended = api.post(PATH + "/" + id + "/end", admin, null);
        assertThat(ended.data().path("status").asString()).isEqualTo("ENDED");
        assertThat(ended.data().path("endedAt").isNull()).isFalse();
        assertThat(salePriceOf(listing)).isNull();
        assertThat(api.post(PATH + "/" + id + "/end", admin, null).data().path("status").asString())
                .isEqualTo("ENDED");

        Map<String, Object> later = form(slug("later"));
        later.put("startsAt", iso(now().plusDays(1)));
        later.put("endsAt", iso(now().plusDays(2)));
        long scheduled = create(later);
        assertThat(api.post(PATH + "/" + scheduled + "/end", admin, null).errorCode()).isEqualTo("SALE_INVALID");
    }

    @Test
    void onceSoldOnlyNameEndBudgetAndQuotaMayChange() {
        Map<String, Object> form = flash(slug("sold"));
        form.put("perListingQuota", 5);
        form.put("budgetCap", 1000);
        long id = create(form);
        markSoldAt(id);

        Map<String, Object> cheaper = new LinkedHashMap<>(form);
        cheaper.put("discountValue", 20);
        assertThat(api.put(PATH + "/" + id, admin, cheaper).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> smallerBudget = new LinkedHashMap<>(form);
        smallerBudget.put("budgetCap", 500);
        assertThat(api.put(PATH + "/" + id, admin, smallerBudget).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> allowed = new LinkedHashMap<>(form);
        allowed.put("name", "Renamed");
        allowed.put("budgetCap", 2000);
        allowed.put("perListingQuota", 8);
        allowed.put("endsAt", iso(now().plusHours(1)));
        Response updated = api.put(PATH + "/" + id, admin, allowed);
        assertThat(updated.status()).as(updated.body().toString()).isEqualTo(200);
        assertThat(updated.data().path("name").asString()).isEqualTo("Renamed");
        assertThat(updated.data().path("budgetCap").decimalValue()).isEqualByComparingTo("2000");
        assertThat(updated.data().path("soldUnits").asLong()).isEqualTo(1);

        Response delete = api.delete(PATH + "/" + id, admin);
        assertThat(delete.status()).isEqualTo(409);
        assertThat(delete.errorCode()).isEqualTo("SALE_IN_USE");

        api.post(PATH + "/" + id + "/end", admin, null);
    }

    @Test
    void anUnsoldSaleCanBeDeletedAndItsListingsGoBack() {
        long id = create(form(slug("gone")));

        assertThat(api.delete(PATH + "/" + id, admin).status()).isEqualTo(200);

        assertThat(api.get(PATH + "/" + id, admin).errorCode()).isEqualTo("SALE_NOT_FOUND");
        assertThat(salePriceOf(listing)).isNull();
    }

    @Test
    void aShopSaleCanOnlyBePausedWithAReasonOrResumed() {
        OffsetDateTime now = now();
        long id = sales.insert(new NewPriceSale("Shop 20", slug("shop"), OwnerType.SELLER, seller.profileId(),
                SaleType.SHOP_SALE, DiscountType.PERCENT, new BigDecimal("20"), null, ScopeType.SHOP, List.of(),
                List.of(), null, null, null, null, null, false, null, now.minusHours(1), now.plusDays(1), null));

        assertThat(api.put(PATH + "/" + id, admin, form(slug("x"))).status()).isEqualTo(403);
        assertThat(api.delete(PATH + "/" + id, admin).status()).isEqualTo(403);
        assertThat(api.post(PATH + "/" + id + "/end", admin, null).status()).isEqualTo(403);
        assertThat(api.post(PATH + "/" + id + "/pause", admin, Map.of()).errorCode()).isEqualTo("SALE_INVALID");

        // The shop paused it itself; Pegasus's pause takes over so the shop cannot lift it.
        sales.pause(id, seller.userId(), null, now);
        Response paused = api.post(PATH + "/" + id + "/pause", admin, Map.of("reason", "Fake list price"));
        assertThat(paused.data().path("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.data().path("pauseReason").asString()).isEqualTo("Fake list price");
        assertThat(paused.data().path("owner").path("id").asLong()).isEqualTo(seller.profileId());

        assertThat(api.get(PATH + "?owner=SELLER&q=" + paused.data().path("slug").asString(), admin)
                .data().path("page").path("items")).hasSize(1);
    }

    @Test
    void theHomePageShowsOneFlashSaleAtATime() {
        long flashA = create(flash(slug("flash-a")));
        long flashB = create(flash(slug("flash-b")));
        long banner = create(form(slug("banner")));

        assertThat(api.put(PATH + "/home-order", admin, Map.of("saleIds", List.of(flashA, flashB))).errorCode())
                .isEqualTo("SALE_INVALID");

        Response home = api.put(PATH + "/home-order", admin, Map.of("saleIds", List.of(flashA, banner)));
        assertThat(home.status()).as(home.body().toString()).isEqualTo(200);
        assertThat(home.data()).hasSize(2);
        assertThat(home.data().get(0).path("id").asLong()).isEqualTo(flashA);
        assertThat(home.data().get(1).path("id").asLong()).isEqualTo(banner);
        assertThat(home.data().get(1).path("homeSort").asInt()).isEqualTo(1);

        api.put(PATH + "/home-order", admin, Map.of("saleIds", List.of()));
        assertThat(api.get(PATH + "/" + flashA, admin).data().path("featuredOnHome").asBoolean()).isFalse();
    }

    @Test
    void summaryCountsRunningSalesAndListingsOnSale() {
        Response before = api.get(PATH + "/summary", admin);

        long id = create(form(slug("kpi")));

        Response after = api.get(PATH + "/summary", admin);
        assertThat(delta(before, after, "activePlatformSales")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "listingsOnSale")).isEqualByComparingTo("1");
        assertThat(after.data().path("saleSubsidy30d").decimalValue()).isNotNull();

        api.post(PATH + "/" + id + "/end", admin, null);
    }

    // ---------- helpers ----------

    /** A real order whose line is then pointed at the sale, as checkout will from TCG-680. */
    private void markSoldAt(long saleId) {
        AuthPrincipal buyer = data.buyer();
        Seller other = data.seller();
        Listing sold = data.onSale(other, data.card(data.game("Sold"), "Sold " + data.tag(), "C"),
                CardCondition.NM, 1, "100");
        cart.addItem(buyer, null, new CartItemRequest(sold.id(), 1));
        long orderId = checkout.checkout(buyer, "sale-sold-" + data.tag(), null, null).orderId();
        dsl.update(ORDER_ITEM)
                .set(ORDER_ITEM.SALE_ID, saleId)
                .set(ORDER_ITEM.SALE_FUNDED_BY, "PLATFORM")
                .where(ORDER_ITEM.SELLER_ORDER_ID.in(dsl.select(SELLER_ORDER.ID).from(SELLER_ORDER)
                        .where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))))
                .execute();
    }

    private BigDecimal salePriceOf(Listing l) {
        return dsl.select(LISTING_SALE_PRICE.SALE_PRICE)
                .from(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.LISTING_ID.eq(l.id()))
                .fetchOne(LISTING_SALE_PRICE.SALE_PRICE);
    }

    private long create(Map<String, Object> form) {
        Response r = api.post(PATH, admin, form);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().path("id").asLong();
    }

    private Map<String, Object> form(String slug) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", "Game 10%");
        form.put("slug", slug);
        form.put("saleType", "CATEGORY_SALE");
        form.put("discountType", "PERCENT");
        form.put("discountValue", 10);
        form.put("scopeType", "GAME");
        form.put("scopeIds", List.of(game));
        form.put("startsAt", iso(now().minusHours(1)));
        form.put("endsAt", iso(now().plusDays(1)));
        return form;
    }

    private Map<String, Object> flash(String slug) {
        Map<String, Object> form = form(slug);
        form.put("name", "Flash 30%");
        form.put("saleType", "FLASH_SALE");
        form.put("discountValue", 30);
        form.put("endsAt", iso(now().plusHours(2)));
        return form;
    }

    private static BigDecimal delta(Response before, Response after, String field) {
        return after.data().path(field).decimalValue().subtract(before.data().path(field).decimalValue());
    }

    private String slug(String prefix) {
        return prefix + "-" + data.tag();
    }

    private String iso(OffsetDateTime at) {
        return at.withNano(0).toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
