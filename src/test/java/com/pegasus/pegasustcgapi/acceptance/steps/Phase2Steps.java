package com.pegasus.pegasustcgapi.acceptance.steps;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.BinaryResponse;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.MutableClock;
import com.pegasus.pegasustcgapi.support.TestData;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Glue for the Phase 2 features (CR10-CR13, CR16).
 *
 * <p>People, cards and orders are arranged through {@link TestData} and the services;
 * every check is made over HTTP with a real signed token. Paths and bodies may carry
 * placeholders that are resolved per scenario:
 * {@code {order}}, {@code {listing:<card>}}, {@code {product:<card>}}, {@code {game:<name>}},
 * {@code {shop:<seller>}}, {@code {code:<name>}}, {@code {saved:<name>}} and {@code {now+3d}} / {@code {now-1h}}.
 */
public class Phase2Steps {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)(?::([^}]+))?\\}");
    private static final Pattern NOW = Pattern.compile("\\{now([+-]\\d+)([dh])\\}");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TestData data;
    @Autowired private MutableClock clock;
    @Autowired private DSLContext dsl;
    @Autowired private CartService cart;
    @Autowired private CheckoutService checkout;

    @LocalServerPort private int port;

    private ApiClient api;
    private final Map<String, AuthPrincipal> people = new HashMap<>();
    private final Map<String, Seller> sellers = new HashMap<>();
    private final Map<String, Short> games = new HashMap<>();
    private final Map<String, Card> cards = new HashMap<>();
    private final Map<String, Long> listings = new HashMap<>();
    private final Map<String, String> codes = new HashMap<>();
    private final Map<String, String> saved = new HashMap<>();
    private Long orderId;
    private Response last;
    private BinaryResponse file;

    @Before
    public void reset() {
        api = new ApiClient(port);
        // Tokens are checked against the system clock, so the app clock starts at real time.
        clock.setInstant(Instant.now());
    }

    // ---------- arranging ----------

    @Given("a buyer {string}")
    public void aBuyer(String name) {
        people.put(name, data.buyer());
    }

    @Given("a seller {string}")
    public void aSeller(String name) {
        Seller seller = data.seller();
        sellers.put(name, seller);
        people.put(name, seller.principal());
    }

    @Given("an admin {string}")
    public void anAdmin(String name) {
        people.put(name, data.admin());
    }

    @Given("seller {string} lists {int} {string} from {string} at {string}")
    public void sellerLists(String seller, int stock, String card, String game, String price) {
        short gameId = games.computeIfAbsent(game, data::game);
        Card made = data.card(gameId, card + " " + data.tag(), "R");
        cards.put(card, made);
        listings.put(card, data.onSale(sellers.get(seller), made, CardCondition.NM, stock, price).id());
    }

    @Given("the product {string} also has the Thai name {string}")
    public void thaiName(String card, String thai) {
        dsl.update(CATALOG_PRODUCT).set(CATALOG_PRODUCT.NAME_LOCAL, thai)
                .where(CATALOG_PRODUCT.ID.eq(cards.get(card).productId())).execute();
    }

    @Given("{string} has {int} {string} in the basket")
    public void inBasket(String buyer, int quantity, String card) {
        cart.addItem(people.get(buyer), null, new CartItemRequest(listings.get(card), quantity));
    }

    @Given("{string} has placed an order for {int} {string}")
    public void placedOrder(String buyer, int quantity, String card) {
        inBasket(buyer, quantity, card);
        orderId = checkout.checkout(people.get(buyer), "phase2-" + data.tag(), null, null).orderId();
    }

    @Given("{string} has a completed order for {int} {string}")
    public void completedOrder(String buyer, int quantity, String card) {
        placedOrder(buyer, quantity, card);
        // The test clock stands still and sales windows end exclusively at "now", so complete it a minute earlier.
        OffsetDateTime completedAt = OffsetDateTime.now(clock).minusMinutes(1).withOffsetSameInstant(ZoneOffset.UTC);
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.STATUS, "COMPLETED")
                .set(SELLER_ORDER.COMPLETED_AT, completedAt)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId)).execute();
        dsl.update(SALES_ORDER).set(SALES_ORDER.STATUS, "COMPLETED")
                .where(SALES_ORDER.ID.eq(orderId)).execute();
    }

    @When("the product {string} is renamed to {string}")
    public void renamed(String card, String name) {
        dsl.update(CATALOG_PRODUCT).set(CATALOG_PRODUCT.NAME, name)
                .where(CATALOG_PRODUCT.ID.eq(cards.get(card).productId())).execute();
    }

    // ---------- acting ----------

    @When("{string} calls GET {string}")
    public void get(String who, String path) {
        last = api.get(encodeQuery(resolve(path)), bearer(who));
    }

    @When("{string} calls DELETE {string}")
    public void delete(String who, String path) {
        last = api.delete(resolve(path), bearer(who));
    }

    @When("{string} calls POST {string} with:")
    public void post(String who, String path, String body) {
        last = api.post(resolve(path), bearer(who), json(body));
    }

    @When("{string} calls POST {string}")
    public void postEmpty(String who, String path) {
        last = api.post(resolve(path), bearer(who), Map.of());
    }

    @When("{string} calls PUT {string} with:")
    public void put(String who, String path, String body) {
        last = api.put(resolve(path), bearer(who), json(body));
    }

    @When("{string} downloads {string}")
    public void download(String who, String path) {
        file = api.getBytes(resolve(path), bearer(who), "application/pdf");
    }

    @When("the answer's {string} is saved as {string}")
    public void save(String field, String name) {
        saved.put(name, at(last.data(), field).asString());
    }

    // ---------- checking ----------

    @Then("the API answers {int}")
    public void answers(int status) {
        assertThat(last.status()).as(last.body().toString()).isEqualTo(status);
    }

    @Then("the API error code is {string}")
    public void errorCode(String code) {
        assertThat(last.errorCode()).as(last.body().toString()).isEqualTo(code);
    }

    @Then("the answer's {string} is {string}")
    public void fieldIs(String field, String expected) {
        assertThat(at(last.data(), field).asString()).as(last.body().toString()).isEqualTo(resolve(expected));
    }

    @Then("the answer's {string} is the number {bigdecimal}")
    public void fieldIsNumber(String field, java.math.BigDecimal expected) {
        assertThat(at(last.data(), field).decimalValue()).as(last.body().toString()).isEqualByComparingTo(expected);
    }

    @Then("the answer's {string} contains {string}")
    public void fieldContains(String field, String expected) {
        assertThat(at(last.data(), field).asString()).as(last.body().toString()).contains(resolve(expected));
    }

    @Then("the answer's {string} include the name {string}")
    public void namesInclude(String field, String expected) {
        assertThat(at(last.data(), field).valueStream().map(item -> item.path("name").asString()).toList())
                .as(last.body().toString()).contains(resolve(expected));
    }

    @Then("the answer's {string} is absent")
    public void fieldAbsent(String field) {
        JsonNode node = at(last.data(), field);
        assertThat(node.isMissingNode() || node.isNull()).as(last.body().toString()).isTrue();
    }

    @Then("the answer's {string} has {int} item(s)")
    public void fieldSize(String field, int size) {
        assertThat(at(last.data(), field).size()).as(last.body().toString()).isEqualTo(size);
    }

    @Then("the download answers {int}")
    public void downloadAnswers(int status) {
        assertThat(file.status()).isEqualTo(status);
    }

    @Then("the download is a PDF that mentions {string}")
    public void pdfMentions(String text) throws IOException {
        assertThat(file.status()).isEqualTo(200);
        assertThat(new String(file.body(), 0, 5)).isEqualTo("%PDF-");
        try (var document = Loader.loadPDF(file.body())) {
            assertThat(new PDFTextStripper().getText(document)).contains(resolve(text));
        }
    }

    // ---------- helpers ----------

    private String bearer(String who) {
        return "a guest".equals(who) ? null
                : "an expired session".equals(who) ? "not.a.valid-token"
                : data.bearer(people.get(who));
    }

    /** Encodes each query value, so names with spaces or Thai letters reach the API intact. */
    private static String encodeQuery(String path) {
        int q = path.indexOf('?');
        if (q < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder(path.substring(0, q + 1));
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            out.append(out.charAt(out.length() - 1) == '?' ? "" : "&").append(pair, 0, eq + 1)
                    .append(java.net.URLEncoder.encode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private Object json(String body) {
        return JSON.readValue(resolve(body), Object.class);
    }

    /** Dotted path; numbers index arrays: {@code items.0.productName}. */
    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = part.matches("\\d+") ? node.path(Integer.parseInt(part)) : node.path(part);
        }
        return node;
    }

    private String resolve(String text) {
        Matcher now = NOW.matcher(text);
        StringBuilder timed = new StringBuilder();
        while (now.find()) {
            long amount = Long.parseLong(now.group(1));
            OffsetDateTime at = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.ofHours(7));
            at = "d".equals(now.group(2)) ? at.plusDays(amount) : at.plusHours(amount);
            now.appendReplacement(timed, at.toString());
        }
        now.appendTail(timed);

        Matcher m = PLACEHOLDER.matcher(timed.toString());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String arg = m.group(2);
            String value = switch (m.group(1)) {
                case "order" -> String.valueOf(orderId);
                case "listing" -> String.valueOf(listings.get(arg));
                case "product" -> cards.get(arg).name();
                case "game" -> String.valueOf(games.get(arg));
                case "shop" -> String.valueOf(sellers.get(arg).profileId());
                case "code" -> codes.computeIfAbsent(arg, c -> (c + data.tag()).toUpperCase(Locale.ROOT));
                case "saved" -> saved.get(arg);
                default -> m.group();
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
