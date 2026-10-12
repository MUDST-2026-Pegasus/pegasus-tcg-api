package com.pegasus.pegasustcgapi.acceptance.steps;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.job.PendingPaymentTimeoutScheduler;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PlatformSettingService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.MutableClock;
import com.pegasus.pegasustcgapi.support.TestData;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Glue for {@code browse_and_cart.feature} and {@code place_order.feature}.
 *
 * <p>The steps act the way a client does — over HTTP, with a real signed token — and
 * check the outcome in the response first and in the database only where the API has
 * no way to show it (stock counters, the audit trail, escrow). Arranging the world
 * (people, catalogue, stock, settings) goes through {@link TestData} instead.
 *
 * <p>Cucumber makes one instance per scenario, so the fields below never leak from
 * one scenario into the next.
 */
public class MarketplaceSteps {

    @Autowired
    private TestData data;

    @Autowired
    private MutableClock clock;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private LedgerPort ledgerPort;

    @Autowired
    private InventoryPort inventoryPort;

    @Autowired
    private PendingPaymentTimeoutScheduler paymentTimeout;

    @LocalServerPort
    private int port;

    private ApiClient api;

    private final Map<String, Short> games = new HashMap<>();
    private final Map<String, Seller> sellers = new HashMap<>();
    private final Map<String, AuthPrincipal> buyers = new HashMap<>();
    private final Map<String, Long> addresses = new HashMap<>();
    private final Map<String, Long> listingByCard = new HashMap<>();

    private Response last;
    private String orderBuyer;
    private long salesOrderId;
    private long sellerOrderId;

    @Before
    public void resetSharedState() {
        api = new ApiClient(port);
        clock.reset();
        reset(ledgerPort, inventoryPort);
    }

    @After
    public void restoreSettings() {
        data.restoreDefaultSettings();
    }

    private String bearer(String person) {
        AuthPrincipal who = buyers.containsKey(person) ? buyers.get(person) : sellers.get(person).principal();
        return data.bearer(who);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<String> names(JsonNode items, String... path) {
        return StreamSupport.stream(items.spliterator(), false)
                .map(item -> {
                    JsonNode node = item;
                    for (String field : path) {
                        node = node.path(field);
                    }
                    return node.asString();
                })
                .toList();
    }

    // ---------- arranging the world ----------

    @Given("seller {string} has these cards on sale:")
    public void sellerHasTheseCardsOnSale(String name, DataTable table) {
        Seller seller = sellers.computeIfAbsent(name, ignored -> data.seller());
        for (Map<String, String> row : table.asMaps()) {
            short gameId = games.computeIfAbsent(row.get("game"), data::game);
            Card card = data.card(gameId, row.get("card"), row.get("rarity"));
            long listingId = data.onSale(seller, card, CardCondition.valueOf(row.get("condition")),
                    Integer.parseInt(row.get("stock")), row.get("price")).id();
            listingByCard.put(row.get("card"), listingId);
        }
    }

    @Given("buyer {string} is signed in")
    public void buyerIsSignedIn(String name) {
        buyers.put(name, data.buyer());
    }

    @Given("buyer {string} is signed in with a saved address")
    public void buyerIsSignedInWithASavedAddress(String name) {
        buyerIsSignedIn(name);
        addresses.put(name, data.address(buyers.get(name)));
    }

    @Given("the platform commission is {int}%")
    public void thePlatformCommissionIs(int percent) {
        data.setting(PlatformSettingService.COMMISSION_DEFAULT_RATE, String.valueOf(percent));
    }

    @Given("the payment timeout is {int} minutes")
    public void thePaymentTimeoutIs(int minutes) {
        data.setting(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES, String.valueOf(minutes));
    }

    @Given("{string} has {int} {string} in the cart")
    public void hasInTheCart(String buyer, int quantity, String card) {
        addsToTheCart(buyer, quantity, card);
        assertThat(last.status()).as("adding to the cart").isEqualTo(201);
    }

    @Given("{string} has checked out")
    public void hasCheckedOut(String buyer) {
        checksOut(buyer);
        assertThat(last.status()).as("checkout: %s", last.body()).isEqualTo(201);
    }

    @Given("{string} has paid for the order")
    public void hasPaidForTheOrder(String buyer) {
        paysForTheOrder(buyer);
        assertThat(last.status()).as("payment: %s", last.body()).isEqualTo(200);
    }

    @Given("{string} has cancelled the order")
    public void hasCancelledTheOrder(String buyer) {
        last = api.post(ApiPaths.ORDERS + "/" + salesOrderId + "/cancel", bearer(buyer),
                Map.of("reason", "Changed my mind"));
        assertThat(last.status()).as("cancel: %s", last.body()).isEqualTo(200);
    }

    // ---------- browsing ----------

    @When("a guest searches the {string} catalogue for {string}")
    public void aGuestSearchesTheCatalogue(String game, String q) {
        last = api.get(ApiPaths.CATALOG + "/products?gameId=" + games.get(game) + "&q=" + encode(q), null);
    }

    @When("a guest browses the market for {string}")
    public void aGuestBrowsesTheMarket(String game) {
        last = api.get(ApiPaths.LISTINGS + "?gameId=" + games.get(game), null);
    }

    @When("a guest browses the market for {string} in condition {string}")
    public void aGuestBrowsesTheMarketInCondition(String game, String condition) {
        last = api.get(ApiPaths.LISTINGS + "?gameId=" + games.get(game) + "&condition=" + condition, null);
    }

    @Then("the catalogue results are exactly {string}")
    public void theCatalogueResultsAreExactly(String name) {
        assertThat(names(last.data().path("items"), "name")).containsExactly(name);
    }

    @Then("the market shows exactly {string}")
    public void theMarketShowsExactly(String name) {
        assertThat(last.status()).isEqualTo(200);
        assertThat(names(last.data().path("items"), "card", "productName")).containsExactly(name);
    }

    @Then("the market is empty")
    public void theMarketIsEmpty() {
        assertThat(last.data().path("items").size()).isZero();
        assertThat(last.data().path("totalItems").asLong()).isZero();
    }

    // ---------- the cart ----------

    @When("a guest adds {int} {string} to the cart")
    public void aGuestAddsToTheCart(int quantity, String card) {
        last = api.post(ApiPaths.CART_ITEMS, null, cartItem(card, quantity));
    }

    @When("{string} adds {int} {string} to the cart")
    public void addsToTheCart(String buyer, int quantity, String card) {
        last = api.post(ApiPaths.CART_ITEMS, bearer(buyer), cartItem(card, quantity));
    }

    private Map<String, Object> cartItem(String card, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingByCard.get(card));
        body.put("quantity", quantity);
        return body;
    }

    @Then("the response carries a cart session key")
    public void theResponseCarriesACartSessionKey() {
        String key = last.header("X-Cart-Session").orElseThrow();
        assertThat(UUID.fromString(key)).isNotNull();
    }

    @Then("{string}'s cart holds {int} {string} for {bigdecimal}")
    public void cartHolds(String buyer, int quantity, String card, BigDecimal subtotal) {
        Response cart = api.get(ApiPaths.CART, bearer(buyer));
        assertThat(cart.status()).isEqualTo(200);
        JsonNode items = cart.data().path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.path(0).path("listingId").asLong()).isEqualTo(listingByCard.get(card));
        assertThat(items.path(0).path("quantity").asInt()).isEqualTo(quantity);
        assertThat(cart.data().path("itemsSubtotal").asDecimal()).isEqualByComparingTo(subtotal);
    }

    @Then("{string}'s cart is empty")
    public void cartIsEmpty(String buyer) {
        Response cart = api.get(ApiPaths.CART, bearer(buyer));
        assertThat(cart.status()).isEqualTo(200);
        assertThat(cart.data().path("items").size()).isZero();
    }

    // ---------- ordering ----------

    @When("{string} checks out")
    public void checksOut(String buyer) {
        checkout(buyer, Map.of("Idempotency-Key", "cucumber-" + data.tag()));
    }

    @When("{string} checks out without an Idempotency-Key")
    public void checksOutWithoutAnIdempotencyKey(String buyer) {
        checkout(buyer, Map.of());
    }

    private void checkout(String buyer, Map<String, String> headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shippingAddressId", addresses.get(buyer));
        last = api.post(ApiPaths.CHECKOUT, bearer(buyer), body, headers);
        if (last.status() == 201) {
            orderBuyer = buyer;
            salesOrderId = last.data().path("orderId").asLong();
            sellerOrderId = last.data().path("sellerOrders").path(0).path("id").asLong();
        }
    }

    @When("{string} pays for the order")
    public void paysForTheOrder(String buyer) {
        last = api.post(ApiPaths.ORDERS + "/" + salesOrderId + "/pay", bearer(buyer), null);
    }

    @When("{string} ships the order with tracking {string}")
    public void shipsTheOrder(String seller, String tracking) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("carrierName", "Thailand Post");
        body.put("carrierCode", "THAILAND_POST");
        body.put("trackingNumber", tracking);
        last = api.post(ApiPaths.SELLERS_ME_ORDERS + "/" + sellerOrderId + "/ship", bearer(seller), body);
    }

    @When("{string} confirms the order was received")
    public void confirmsTheOrderWasReceived(String buyer) {
        last = api.post(ApiPaths.ORDERS + "/" + salesOrderId + "/confirm-received", bearer(buyer), null);
    }

    @When("{int} minutes and {int} second(s) pass and the payment timeout sweep runs")
    public void timePassesAndTheSweepRuns(int minutes, int seconds) {
        OffsetDateTime placedAt = dsl.select(SALES_ORDER.PLACED_AT).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(salesOrderId)).fetchSingle().value1();
        clock.setInstant(placedAt.plus(Duration.ofMinutes(minutes).plusSeconds(seconds)).toInstant());
        paymentTimeout.processExpiredOrders();
        clock.reset();
    }

    // ---------- outcomes ----------

    @Then("the response status is {int}")
    public void theResponseStatusIs(int status) {
        assertThat(last.status()).as("response body: %s", last.body()).isEqualTo(status);
    }

    @Then("the error code is {string}")
    public void theErrorCodeIs(String code) {
        assertThat(last.body().path("success").asBoolean()).isFalse();
        assertThat(last.errorCode()).isEqualTo(code);
    }

    @Then("the order is {string}")
    public void theOrderIs(String status) {
        Response order = api.get(ApiPaths.ORDERS + "/" + salesOrderId, bearer(orderBuyer));
        assertThat(order.status()).isEqualTo(200);
        assertThat(order.data().path("status").asString()).isEqualTo(status);
        assertThat(order.data().path("sellerOrders").path(0).path("status").asString()).isEqualTo(status);
    }

    @Then("{string} has {int} cards available and {int} reserved")
    public void cardsAvailableAndReserved(String card, int available, int reserved) {
        ListingRecord listing = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingByCard.get(card))).fetchSingle();
        assertThat(listing.getQuantityAvailable()).as("available").isEqualTo(available);
        assertThat(listing.getQuantityReserved()).as("reserved").isEqualTo(reserved);
    }

    @Then("the cancellation was recorded by the system")
    public void theCancellationWasRecordedByTheSystem() {
        SellerOrderStatusHistoryRecord latest = dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.ID.desc())
                .limit(1)
                .fetchSingle();
        assertThat(latest.getFromStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(latest.getToStatus()).isEqualTo("CANCELLED");
        assertThat(latest.getChangedBy()).as("NULL means the system, not a person").isNull();
    }

    @Then("the commission is {bigdecimal} and {string} nets {bigdecimal}")
    public void theCommissionAndSellerNet(BigDecimal commission, String seller, BigDecimal net) {
        Response sellerView = api.get(ApiPaths.SELLERS_ME_ORDERS + "/" + sellerOrderId, bearer(seller));
        assertThat(sellerView.status()).isEqualTo(200);
        assertThat(sellerView.data().path("commissionAmount").asDecimal()).isEqualByComparingTo(commission);
        assertThat(sellerView.data().path("sellerNetAmount").asDecimal()).isEqualByComparingTo(net);
    }

    @Then("escrow has not been released")
    public void escrowHasNotBeenReleased() {
        verify(ledgerPort, never()).release(sellerOrderId);
    }

    @Then("escrow was released to {string} once")
    public void escrowWasReleasedOnce(String seller) {
        assertThat(sellers).containsKey(seller);
        verify(ledgerPort, times(1)).release(sellerOrderId);
    }
}
