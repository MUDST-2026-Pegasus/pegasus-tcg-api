package com.pegasus.pegasustcgapi.controller.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * /sellers/me/sales against the real schema [TCG-678]. Each test has a shop of
 * its own, so a shop sale never reprices another test's listings.
 */
class SellerSaleApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/sellers/me/sales";
    private static final String ADMIN_PATH = "/api/v1/admin/sales";

    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private ApiClient api;
    private Seller seller;
    private String shop;
    private short game;
    private Card card;
    private Listing a;
    private Listing b;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        seller = data.seller();
        shop = data.bearer(seller.principal());
        game = data.game("Shop sale");
        card = data.card(game, "Shop sale card", "R");
        a = data.onSale(seller, card, CardCondition.NM, 2, "1000");
        b = data.onSale(seller, data.card(game, "Shop sale other", "C"), CardCondition.NM, 1, "200");
    }

    @Test
    void createListGetUpdateAndDeleteAShopSale() {
        Response created = api.post(PATH, shop, shopWide(20));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        JsonNode body = created.data();
        long id = body.path("id").asLong();
        assertThat(body.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("scopeType").asString()).isEqualTo("SHOP");
        assertThat(body.path("listingCount").asLong()).isEqualTo(2);
        assertThat(salePriceOf(a)).isEqualByComparingTo("800");
        assertThat(salePriceOf(b)).isEqualByComparingTo("160");

        Response list = api.get(PATH + "?status=ACTIVE", shop);
        assertThat(list.data().path("page").path("items")).hasSize(1);
        assertThat(list.data().path("statusCounts").path("ACTIVE").asLong()).isEqualTo(1);

        Map<String, Object> deeper = shopWide(30);
        deeper.put("name", "Bigger sale");
        Response updated = api.put(PATH + "/" + id, shop, deeper);
        assertThat(updated.status()).as(updated.body().toString()).isEqualTo(200);
        assertThat(updated.data().path("name").asString()).isEqualTo("Bigger sale");
        assertThat(salePriceOf(a)).isEqualByComparingTo("700");

        Response summary = api.get(PATH + "/summary", shop);
        assertThat(summary.data().path("activeSales").asLong()).isEqualTo(1);
        assertThat(summary.data().path("listingsOnSale").asLong()).isEqualTo(2);

        assertThat(api.delete(PATH + "/" + id, shop).status()).isEqualTo(200);
        assertThat(api.get(PATH + "/" + id, shop).errorCode()).isEqualTo("SALE_NOT_FOUND");
        assertThat(salePriceOf(a)).isNull();
    }

    @Test
    void listingsSaleTakesOwnPricesAndRefusesBadRowsOneByOne() {
        Response created = api.post(PATH, shop, picked(List.of(item(a.id(), "750"), item(b.id(), "150"))));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.data().path("discountType").asString()).isEqualTo("FIXED_PRICE");
        assertThat(created.data().path("items")).hasSize(2);
        assertThat(salePriceOf(a)).isEqualByComparingTo("750");
        assertThat(salePriceOf(b)).isEqualByComparingTo("150");
        api.post(PATH + "/" + created.data().path("id").asLong() + "/end", shop, null);

        Seller other = data.seller();
        Listing theirs = data.onSale(other, card, CardCondition.LP, 1, "500");
        Response bad = api.post(PATH, shop, picked(List.of(
                item(a.id(), "1000"), item(b.id(), "0.50"), item(theirs.id(), "100"))));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.errorCode()).isEqualTo("SALE_INVALID");
        List<String> codes = new ArrayList<>();
        bad.data().path("details").path("rows").forEach(r -> codes.add(r.path("listingId").asLong() + ":"
                + r.path("code").asString()));
        assertThat(codes).containsExactly(
                a.id() + ":SALE_PRICE_NOT_LOWER",
                b.id() + ":SALE_PRICE_TOO_LOW",
                theirs.id() + ":LISTING_NOT_FOUND");

        Map<String, Object> percent = picked(List.of(item(a.id(), null), item(b.id(), null)));
        percent.put("discountType", "PERCENT");
        percent.put("discountValue", 10);
        Response ok = api.post(PATH, shop, percent);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
        assertThat(ok.data().path("items").get(0).path("salePrice").decimalValue()).isEqualByComparingTo("900");
    }

    @Test
    void anOverlappingSaleIsRefusedWithTheListingsItClashesOn() {
        assertThat(api.post(PATH, shop, picked(List.of(item(a.id(), "900")))).status()).isEqualTo(201);

        Response clash = api.post(PATH, shop, shopWide(10));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.errorCode()).isEqualTo("SALE_OVERLAP");
        JsonNode ids = clash.data().path("details").path("listingIds");
        assertThat(ids).hasSize(1);
        assertThat(ids.get(0).asLong()).isEqualTo(a.id());

        // b alone clashes with nothing, and a sale after this one ends is fine.
        assertThat(api.post(PATH, shop, picked(List.of(item(b.id(), "150")))).status()).isEqualTo(201);
        Map<String, Object> later = shopWide(10);
        later.put("startsAt", iso(now().plusDays(2)));
        later.put("endsAt", iso(now().plusDays(3)));
        assertThat(api.post(PATH, shop, later).status()).isEqualTo(201);
    }

    @Test
    void anotherShopCannotReachThisShopsSale() {
        long id = create(shopWide(20));
        Seller other = data.seller();
        String them = data.bearer(other.principal());

        assertThat(api.get(PATH + "/" + id, them).status()).isEqualTo(404);
        assertThat(api.put(PATH + "/" + id, them, shopWide(30)).status()).isEqualTo(404);
        assertThat(api.post(PATH + "/" + id + "/pause", them, null).status()).isEqualTo(404);
        assertThat(api.post(PATH + "/" + id + "/end", them, null).status()).isEqualTo(404);
        assertThat(api.delete(PATH + "/" + id, them).status()).isEqualTo(404);
        assertThat(api.get(PATH, them).data().path("page").path("items")).isEmpty();
    }

    @Test
    void pauseResumeAndEnd() {
        long id = create(shopWide(20));

        Response paused = api.post(PATH + "/" + id + "/pause", shop, null);
        assertThat(paused.data().path("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.data().path("pausedByPegasus").asBoolean()).isFalse();
        assertThat(salePriceOf(a)).isNull();

        Response resumed = api.post(PATH + "/" + id + "/resume", shop, null);
        assertThat(resumed.data().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(salePriceOf(a)).isEqualByComparingTo("800");

        Response ended = api.post(PATH + "/" + id + "/end", shop, null);
        assertThat(ended.data().path("status").asString()).isEqualTo("ENDED");
        assertThat(salePriceOf(a)).isNull();
        assertThat(api.put(PATH + "/" + id, shop, shopWide(30)).errorCode()).isEqualTo("SALE_INVALID");
    }

    @Test
    void aSalePegasusPausedCannotBeResumedByTheShop() {
        long id = create(shopWide(20));
        String admin = data.bearer(data.admin());
        api.post(PATH + "/" + id + "/pause", shop, null);
        api.post(ADMIN_PATH + "/" + id + "/pause", admin, Map.of("reason", "Fake list price"));

        Response view = api.get(PATH + "/" + id, shop);
        assertThat(view.data().path("pausedByPegasus").asBoolean()).isTrue();
        assertThat(view.data().path("pauseReason").asString()).isEqualTo("Fake list price");

        Response resume = api.post(PATH + "/" + id + "/resume", shop, null);
        assertThat(resume.status()).isEqualTo(409);
        assertThat(resume.errorCode()).isEqualTo("SALE_PAUSED");
        assertThat(resume.data().path("details").path("reason").asString()).isEqualTo("Fake list price");
        assertThat(salePriceOf(a)).isNull();
    }

    @Test
    void onlyAVerifiedShopCreatesOrResumes() {
        long id = create(shopWide(20));
        api.post(PATH + "/" + id + "/pause", shop, null);
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "SUSPENDED")
                .where(SELLER_PROFILE.ID.eq(seller.profileId())).execute();

        assertThat(api.post(PATH, shop, shopWide(10)).errorCode()).isEqualTo("SELLER_NOT_VERIFIED");
        assertThat(api.post(PATH + "/" + id + "/resume", shop, null).errorCode()).isEqualTo("SELLER_NOT_VERIFIED");
        assertThat(api.get(PATH + "/" + id, shop).status()).isEqualTo(200);
    }

    @Test
    void itemsShowWhenAPegasusCampaignAlsoCoversThemAndWhichPriceWins() {
        String admin = data.bearer(data.admin());
        Map<String, Object> campaign = new LinkedHashMap<>();
        campaign.put("name", "Game 30%");
        campaign.put("slug", "shop-test-" + data.tag());
        campaign.put("saleType", "CATEGORY_SALE");
        campaign.put("discountType", "PERCENT");
        campaign.put("discountValue", 30);
        campaign.put("scopeType", "GAME");
        campaign.put("scopeIds", List.of(game));
        campaign.put("startsAt", iso(now().minusHours(1)));
        campaign.put("endsAt", iso(now().plusDays(1)));
        Response c = api.post(ADMIN_PATH, admin, campaign);
        assertThat(c.status()).as(c.body().toString()).isEqualTo(201);
        long campaignId = c.data().path("id").asLong();

        Response sale = api.post(PATH, shop, picked(List.of(item(a.id(), "900"), item(b.id(), "100"))));
        JsonNode items = sale.data().path("items");
        assertThat(items.get(0).path("platform").path("saleId").asLong()).isEqualTo(campaignId);
        assertThat(items.get(0).path("platform").path("salePrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(items.get(0).path("winner").asString()).isEqualTo("PLATFORM");
        assertThat(items.get(1).path("winner").asString()).isEqualTo("THIS_SALE");

        JsonNode platform = api.get(PATH + "/platform-listings", shop).data().path("items");
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).path("listingId").asLong()).isEqualTo(a.id());
        assertThat(platform.get(0).path("priceLocked").asBoolean()).isTrue();

        api.post(ADMIN_PATH + "/" + campaignId + "/end", admin, null);
    }

    @Test
    void onceSoldOnlyTheNameAndEndMayChangeAndItCannotBeDeleted() {
        Map<String, Object> form = categorySale();
        long id = create(form);
        markSoldAt(id);

        Map<String, Object> deeper = new LinkedHashMap<>(form);
        deeper.put("discountValue", 30);
        assertThat(api.put(PATH + "/" + id, shop, deeper).errorCode()).isEqualTo("SALE_INVALID");

        Map<String, Object> renamed = new LinkedHashMap<>(form);
        renamed.put("name", "Renamed");
        renamed.put("endsAt", iso(now().plusHours(5)));
        Response ok = api.put(PATH + "/" + id, shop, renamed);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.data().path("soldUnits").asLong()).isEqualTo(1);

        assertThat(api.delete(PATH + "/" + id, shop).errorCode()).isEqualTo("SALE_IN_USE");
    }

    // ---------- helpers ----------

    /** A real order of the shop's whose line is then pointed at the sale, as checkout will from TCG-680. */
    private void markSoldAt(long saleId) {
        AuthPrincipal buyer = data.buyer();
        cart.addItem(buyer, null, new CartItemRequest(b.id(), 1));
        long orderId = checkout.checkout(buyer, "shop-sale-sold-" + data.tag(), null, null).orderId();
        dsl.update(ORDER_ITEM)
                .set(ORDER_ITEM.SALE_ID, saleId)
                .set(ORDER_ITEM.SALE_FUNDED_BY, "SELLER")
                .where(ORDER_ITEM.SELLER_ORDER_ID.in(dsl.select(SELLER_ORDER.ID).from(SELLER_ORDER)
                        .where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))))
                .execute();
    }

    private long create(Map<String, Object> form) {
        Response r = api.post(PATH, shop, form);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().path("id").asLong();
    }

    private BigDecimal salePriceOf(Listing l) {
        return dsl.select(LISTING_SALE_PRICE.SALE_PRICE)
                .from(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.LISTING_ID.eq(l.id()))
                .fetchOne(LISTING_SALE_PRICE.SALE_PRICE);
    }

    private Map<String, Object> shopWide(int percent) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", "Shop " + percent + "%");
        form.put("scopeType", "SHOP");
        form.put("discountType", "PERCENT");
        form.put("discountValue", percent);
        form.put("startsAt", iso(now().minusHours(1)));
        form.put("endsAt", iso(now().plusDays(1)));
        return form;
    }

    private Map<String, Object> categorySale() {
        Map<String, Object> form = shopWide(20);
        form.put("scopeType", "CATEGORY");
        form.put("scopeIds", List.of(dsl.select(CATALOG_PRODUCT.CATEGORY_ID).from(CATALOG_PRODUCT)
                .where(CATALOG_PRODUCT.ID.eq(card.productId())).fetchSingle(CATALOG_PRODUCT.CATEGORY_ID)));
        return form;
    }

    private Map<String, Object> picked(List<Map<String, Object>> items) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", "Picked");
        form.put("scopeType", "LISTINGS");
        form.put("items", items);
        form.put("startsAt", iso(now().minusHours(1)));
        form.put("endsAt", iso(now().plusDays(1)));
        return form;
    }

    private static Map<String, Object> item(long listingId, String salePrice) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("listingId", listingId);
        item.put("salePrice", salePrice == null ? null : new BigDecimal(salePrice));
        return item;
    }

    private String iso(OffsetDateTime at) {
        return at.withNano(0).toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
