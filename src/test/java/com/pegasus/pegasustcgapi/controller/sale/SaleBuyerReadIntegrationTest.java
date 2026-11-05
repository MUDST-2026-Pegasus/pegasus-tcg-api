package com.pegasus.pegasustcgapi.controller.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSaleListing.PRICE_SALE_LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.service.sale.SalePriceRefresher;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The buyer's side of direct price discounts [TCG-679]: the same price on every
 * page, search on the price paid, the sale pages and the cart noticing a sale end.
 * Each test scopes its campaign to a game of its own so it reprices nothing else.
 */
class SaleBuyerReadIntegrationTest extends PostgresIntegrationTest {

    private static final String ADMIN_SALES = "/api/v1/admin/sales";

    @Autowired SalePriceRefresher refresher;

    private ApiClient api;
    private String admin;
    private Seller seller;
    private short game;
    private Card card;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = data.bearer(data.admin());
        seller = data.seller();
        game = data.game("Buyer sale");
        card = data.card(game, "Buyer sale card " + data.tag(), "R");
    }

    @Test
    void aListingShowsTheSamePriceOnEveryPage() {
        Listing a = data.onSale(seller, card, CardCondition.NM, 3, "1000");
        long saleId = create(campaign("same-" + data.tag(), 20));

        JsonNode detail = api.get("/api/v1/listings/" + a.id(), null).data().path("listing");
        assertPricing(detail.path("pricing"), "1000", "800", 20);
        assertThat(detail.path("price").decimalValue()).isEqualByComparingTo("1000");
        assertThat(detail.path("pricing").path("sale").path("id").asLong()).isEqualTo(saleId);
        assertThat(detail.path("pricing").path("sale").path("type").asString()).isEqualTo("CATEGORY_SALE");
        assertThat(detail.path("pricing").path("sale").has("fundedBy")).isFalse();

        JsonNode market = only(api.get("/api/v1/listings?productId=" + card.productId(), null));
        assertPricing(market.path("pricing"), "1000", "800", 20);

        String username = dsl.select(USER_ACCOUNT.USERNAME).from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(seller.userId())).fetchSingle(USER_ACCOUNT.USERNAME);
        JsonNode shop = only(api.get("/api/v1/u/" + username + "/listings", null));
        assertPricing(shop.path("pricing"), "1000", "800", 20);

        Response added = api.post("/api/v1/cart/items", null, Map.of("listingId", a.id(), "quantity", 1));
        assertThat(added.status()).as(added.body().toString()).isIn(200, 201);
        assertThat(added.data().path("currentPrice").decimalValue()).isEqualByComparingTo("800");
        assertThat(added.data().path("unitPriceAtAdd").decimalValue()).isEqualByComparingTo("800");
        assertPricing(added.data().path("pricing"), "1000", "800", 20);

        // The catalogue tile and its price filter use the same price, not the asking one.
        String catalog = "/api/v1/catalog/products?gameId=" + game;
        JsonNode tile = only(api.get(catalog, null));
        assertThat(tile.path("lowestPrice").decimalValue()).isEqualByComparingTo("800");
        assertThat(api.get(catalog + "&maxPrice=850", null).data().path("items")).hasSize(1);
        assertThat(api.get(catalog + "&minPrice=850", null).data().path("items")).isEmpty();
    }

    @Test
    void searchFiltersAndSortsOnThePriceABuyerPays() {
        // NM only, so the LP cards stay at their own price.
        Map<String, Object> form = campaign("search-" + data.tag(), 50);
        form.put("conditions", List.of("NM"));
        long saleId = create(form);

        Listing onSale = data.onSale(seller, card, CardCondition.NM, 1, "1000");  // 500 now
        Listing dear = data.onSale(seller, card, CardCondition.LP, 1, "700");
        Listing cheap = data.onSale(seller, card, CardCondition.LP, 1, "600");
        String base = "/api/v1/listings?productId=" + card.productId();

        assertThat(ids(api.get(base, null))).containsExactly(onSale.id(), cheap.id(), dear.id());
        assertThat(ids(api.get(base + "&sort=price_desc", null))).containsExactly(dear.id(), cheap.id(), onSale.id());
        assertThat(ids(api.get(base + "&onSale=true", null))).containsExactly(onSale.id());
        assertThat(ids(api.get(base + "&onSale=false", null))).containsExactly(cheap.id(), dear.id());
        assertThat(ids(api.get(base + "&saleId=" + saleId, null))).containsExactly(onSale.id());
        assertThat(ids(api.get(base + "&minPrice=550", null))).containsExactly(cheap.id(), dear.id());
        assertThat(ids(api.get(base + "&maxPrice=550", null))).containsExactly(onSale.id());
        assertThat(ids(api.get(base + "&minDiscount=50", null))).containsExactly(onSale.id());
        assertThat(ids(api.get(base + "&minDiscount=51", null))).isEmpty();
        assertThat(ids(api.get(base + "&sort=discount_desc", null))).containsExactly(onSale.id(), cheap.id(), dear.id());
        assertThat(ids(api.get(base + "&sort=ending_soon", null))).containsExactly(onSale.id(), cheap.id(), dear.id());
        assertThat(api.get(base + "&onSale=true", null).data().path("totalItems").asLong()).isEqualTo(1);
        assertThat(api.get(base + "&minDiscount=0", null).errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void salePageListsItsCardsAndSaysWhenItHasEnded() {
        Listing a = data.onSale(seller, card, CardCondition.NM, 1, "1000");
        String slug = "page-" + data.tag();
        long saleId = create(campaign(slug, 10));

        Response page = api.get("/api/v1/sales/" + slug, null);
        assertThat(page.status()).as(page.body().toString()).isEqualTo(200);
        assertThat(page.data().path("sale").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(page.data().path("sale").path("listingCount").asLong()).isEqualTo(1);
        assertThat(page.data().path("sale").has("budgetCap")).isFalse();
        JsonNode items = page.data().path("listings").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("id").asLong()).isEqualTo(a.id());
        assertPricing(items.get(0).path("pricing"), "1000", "900", 10);

        List<Long> open = new ArrayList<>();
        api.get("/api/v1/sales", null).data().forEach(s -> open.add(s.path("id").asLong()));
        assertThat(open).contains(saleId);

        assertThat(api.post(ADMIN_SALES + "/" + saleId + "/end", admin, null).status()).isEqualTo(200);
        Response ended = api.get("/api/v1/sales/" + slug, null);
        assertThat(ended.data().path("sale").path("status").asString()).isEqualTo("ENDED");
        assertThat(ended.data().path("listings").path("items")).isEmpty();

        assertThat(api.get("/api/v1/sales/no-such-sale-" + data.tag(), null).errorCode()).isEqualTo("SALE_NOT_FOUND");
    }

    @Test
    void featuredIsEmptyWithoutAFlashSaleAndShowsOneComingSoon() {
        // Past the end of every sale other tests left behind.
        OffsetDateTime lastEnd = dsl.select(DSL.max(PRICE_SALE.ENDS_AT)).from(PRICE_SALE).fetchOne(0, OffsetDateTime.class);
        Instant after = lastEnd == null ? clock.instant() : lastEnd.toInstant().plus(Duration.ofDays(1));
        clock.setInstant(after.isAfter(clock.instant()) ? after : clock.instant());
        refresher.refreshAll();
        assertThat(api.get("/api/v1/sales/featured", null).body().path("data").isNull()).isTrue();

        Listing a = data.onSale(seller, card, CardCondition.NM, 5, "1000");
        Map<String, Object> flash = campaign("flash-" + data.tag(), 30);
        flash.put("saleType", "FLASH_SALE");
        flash.put("perListingQuota", 3);
        flash.put("perBuyerLimit", 1);
        flash.put("featuredOnHome", true);
        flash.put("startsAt", iso(now().plusHours(2)));
        flash.put("endsAt", iso(now().plusHours(5)));
        long flashId = create(flash);

        JsonNode soon = api.get("/api/v1/sales/featured", null).data().path("flashSale");
        assertThat(soon.path("sale").path("id").asLong()).isEqualTo(flashId);
        assertThat(soon.path("sale").path("status").asString()).isEqualTo("SCHEDULED");
        assertThat(soon.path("listings")).isEmpty();

        clock.advance(Duration.ofHours(3));
        refresher.refreshAll();
        JsonNode running = api.get("/api/v1/sales/featured", null).data().path("flashSale");
        assertThat(running.path("sale").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(running.path("listings")).hasSize(1);
        JsonNode pricing = running.path("listings").get(0).path("pricing");
        assertPricing(pricing, "1000", "700", 30);
        assertThat(pricing.path("sale").path("quotaLeft").asInt()).isEqualTo(3);
        assertThat(pricing.path("sale").path("perBuyerLimit").asInt()).isEqualTo(1);
        assertThat(running.path("listings").get(0).path("id").asLong()).isEqualTo(a.id());
        assertThat(running.path("soldOut")).isEmpty();

        // Its quota sold: the card leaves the flash price but stays on the strip, faded.
        dsl.insertInto(PRICE_SALE_LISTING)
                .set(PRICE_SALE_LISTING.SALE_ID, flashId)
                .set(PRICE_SALE_LISTING.LISTING_ID, a.id())
                .set(PRICE_SALE_LISTING.SOLD_COUNT, 3)
                .onConflict(PRICE_SALE_LISTING.SALE_ID, PRICE_SALE_LISTING.LISTING_ID)
                .doUpdate().set(PRICE_SALE_LISTING.SOLD_COUNT, 3)
                .execute();
        refresher.refreshAll();
        JsonNode soldOut = api.get("/api/v1/sales/featured", null).data().path("flashSale");
        assertThat(soldOut.path("sale").path("status").asString()).isEqualTo("SOLD_OUT");
        assertThat(soldOut.path("listings")).isEmpty();
        assertThat(soldOut.path("soldOut")).hasSize(1);
        JsonNode faded = soldOut.path("soldOut").get(0);
        assertThat(faded.path("listing").path("id").asLong()).isEqualTo(a.id());
        assertThat(faded.path("flashPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(faded.path("discountPercent").asInt()).isEqualTo(30);
        assertPricing(faded.path("listing").path("pricing"), "1000", "1000", 0);
    }

    @Test
    void cartSaysThePriceChangedWhenTheSaleEnds() {
        Listing a = data.onSale(seller, card, CardCondition.NM, 2, "1000");
        long saleId = create(campaign("cart-" + data.tag(), 25));
        String buyer = data.bearer(data.buyer());

        Response added = api.post("/api/v1/cart/items", buyer, Map.of("listingId", a.id(), "quantity", 1));
        assertThat(added.data().path("unitPriceAtAdd").decimalValue()).isEqualByComparingTo("750");
        JsonNode before = api.get("/api/v1/cart", buyer).data().path("items").get(0);
        assertThat(before.path("priceChanged").asBoolean()).isFalse();

        api.post(ADMIN_SALES + "/" + saleId + "/end", admin, null);
        JsonNode after = api.get("/api/v1/cart", buyer).data().path("items").get(0);
        assertThat(after.path("priceChanged").asBoolean()).isTrue();
        assertThat(after.path("currentPrice").decimalValue()).isEqualByComparingTo("1000");
        assertPricing(after.path("pricing"), "1000", "1000", 0);
        assertThat(after.path("pricing").path("sale").isNull()).isTrue();
    }

    // ---------- helpers ----------

    private static void assertPricing(JsonNode p, String list, String sale, int percent) {
        assertThat(p.path("listPrice").decimalValue()).isEqualByComparingTo(list);
        assertThat(p.path("salePrice").decimalValue()).isEqualByComparingTo(sale);
        assertThat(p.path("discountPercent").asInt()).isEqualTo(percent);
    }

    private static JsonNode only(Response r) {
        JsonNode items = r.data().path("items");
        assertThat(items).as(r.body().toString()).hasSize(1);
        return items.get(0);
    }

    private static List<Long> ids(Response r) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        r.data().path("items").forEach(i -> ids.add(i.path("id").asLong()));
        return ids;
    }

    private long create(Map<String, Object> form) {
        Response r = api.post(ADMIN_SALES, admin, form);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().path("id").asLong();
    }

    private Map<String, Object> campaign(String slug, int percent) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", percent + "% off");
        form.put("slug", slug);
        form.put("saleType", "CATEGORY_SALE");
        form.put("discountType", "PERCENT");
        form.put("discountValue", percent);
        form.put("scopeType", "GAME");
        form.put("scopeIds", List.of(game));
        form.put("startsAt", iso(now().minusHours(1)));
        form.put("endsAt", iso(now().plusDays(1)));
        return form;
    }

    private static String iso(OffsetDateTime at) {
        return at.withNano(0).toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
