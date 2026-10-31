package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.Cart.CART;
import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

@DisplayName("Reorder Preview API & Batch Add-to-Cart Integration Test")
class ReorderAndBatchCartIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private OrderLifecycleService orderLifecycleService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    private record CompletedOrder(
            Seller seller,
            AuthPrincipal buyer,
            String buyerBearer,
            TestData.Card card,
            TestData.Listing originalListing,
            long salesOrderId,
            long sellerOrderId
    ) {}

    private CompletedOrder createCompletedOrder(CardCondition condition, String price, int stock) {
        Seller seller = data.seller();
        TestData.Card card = data.card(data.game("Pokemon"), "Pikachu", "C");
        TestData.Listing listing = data.onSale(seller, card, condition, stock, price);
        AuthPrincipal buyer = data.buyer();
        String buyerBearer = data.bearer(buyer);

        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        CheckoutResponse order = checkoutService.checkout(buyer, "key-" + data.tag(), null, null);

        orderLifecycleService.markOrderPaid(buyer, order.orderId());

        long sellerOrderId = order.sellerOrders().getFirst().id();
        orderLifecycleService.shipSellerOrder(seller.principal(), sellerOrderId,
                new ShipOrderRequest("Kerry Express", "KE" + data.tag(), "KERRY", "proof.jpg", LocalDate.now().plusDays(2)));

        orderLifecycleService.confirmBuyerOrderReceived(buyer, order.orderId());

        return new CompletedOrder(seller, buyer, buyerBearer, card, listing, order.orderId(), sellerOrderId);
    }

    @Nested
    @DisplayName("GET /api/v1/orders/{orderId}/reorder-preview")
    class ReorderPreviewTests {

        @Test
        @DisplayName("Case 1: Original condition is available (with/without price change) + alternative conditions; no seller leakage")
        void case1_AvailableWithPriceChangeAndAlternatives_NoLeakage() {
            // Completed order with DMG listing at 8000.00
            CompletedOrder order = createCompletedOrder(CardCondition.DMG, "8000.00", 5);

            // Change DMG listing price to 8200.00
            dsl.update(LISTING)
                    .set(LISTING.PRICE, new BigDecimal("8200.00"))
                    .where(LISTING.ID.eq(order.originalListing().id()))
                    .execute();

            // Seller also has an alternative condition (NM at 9500.00)
            TestData.Listing altListing = data.onSale(order.seller(), order.card(), CardCondition.NM, 2, "9500.00");

            // Another seller sells the SAME card variant (should NOT leak into response!)
            Seller otherSeller = data.seller();
            TestData.Listing leakedListing = data.onSale(otherSeller, order.card(), CardCondition.NM, 10, "5000.00");

            Response res = api.get("/api/v1/orders/" + order.salesOrderId() + "/reorder-preview", order.buyerBearer());

            assertThat(res.status()).isEqualTo(200);

            JsonNode dataNode = res.data();
            assertThat(dataNode.path("orderId").asLong()).isEqualTo(order.salesOrderId());

            JsonNode sellerOrders = dataNode.path("sellerOrders");
            assertThat(sellerOrders.size()).isEqualTo(1);

            JsonNode so = sellerOrders.get(0);
            assertThat(so.path("sellerProfileId").asLong()).isEqualTo(order.seller().profileId());
            assertThat(so.path("sellerAvailable").asBoolean()).isTrue();
            assertThat(so.path("vacationMode").asBoolean()).isFalse();

            JsonNode items = so.path("items");
            assertThat(items.size()).isEqualTo(1);

            JsonNode item = items.get(0);
            assertThat(item.path("catalogVariantId").asLong()).isEqualTo(order.card().variantId());
            assertThat(item.path("originalCondition").asText()).isEqualTo("DMG");
            assertThat(item.path("originalPrice").asDouble()).isEqualTo(8000.00);
            assertThat(item.path("status").asText()).isEqualTo("AVAILABLE_PRICE_CHANGED");

            JsonNode currentListing = item.path("currentListing");
            assertThat(currentListing.isMissingNode() || currentListing.isNull()).isFalse();
            assertThat(currentListing.path("listingId").asLong()).isEqualTo(order.originalListing().id());
            assertThat(currentListing.path("condition").asText()).isEqualTo("DMG");
            assertThat(currentListing.path("price").asDouble()).isEqualTo(8200.00);
            assertThat(currentListing.path("priceChanged").asBoolean()).isTrue();
            assertThat(currentListing.path("isAvailable").asBoolean()).isTrue();

            JsonNode alternatives = item.path("alternativeConditions");
            assertThat(alternatives.size()).isEqualTo(1);
            assertThat(alternatives.get(0).path("listingId").asLong()).isEqualTo(altListing.id());
            assertThat(alternatives.get(0).path("condition").asText()).isEqualTo("NM");
            assertThat(alternatives.get(0).path("price").asDouble()).isEqualTo(9500.00);

            // Verify other seller's listing does not exist anywhere in the payload
            String rawJson = res.body().toString();
            assertThat(rawJson).doesNotContain("\"listingId\":" + leakedListing.id());
        }

        @Test
        @DisplayName("Case 2: Original condition is sold out (quantityAvailable = 0), returns alternativeConditions")
        void case2_OriginalConditionSoldOut_ReturnsAlternativeAvailable() {
            CompletedOrder order = createCompletedOrder(CardCondition.DMG, "8000.00", 1);

            // Buying the only card in stock leaves original listing sold out (quantityAvailable = 0)
            int qtyAvailable = dsl.select(LISTING.QUANTITY_AVAILABLE).from(LISTING).where(LISTING.ID.eq(order.originalListing().id())).fetchSingle(LISTING.QUANTITY_AVAILABLE);
            assertThat(qtyAvailable).isEqualTo(0);

            // Alternative condition is available
            TestData.Listing altListing = data.onSale(order.seller(), order.card(), CardCondition.NM, 3, "9500.00");

            Response res = api.get("/api/v1/orders/" + order.salesOrderId() + "/reorder-preview", order.buyerBearer());

            assertThat(res.status()).isEqualTo(200);

            JsonNode item = res.data().path("sellerOrders").get(0).path("items").get(0);
            assertThat(item.path("currentListing").isNull()).isTrue();
            assertThat(item.path("status").asText()).isEqualTo("ALTERNATIVE_AVAILABLE");

            JsonNode alternatives = item.path("alternativeConditions");
            assertThat(alternatives.size()).isEqualTo(1);
            assertThat(alternatives.get(0).path("listingId").asLong()).isEqualTo(altListing.id());
            assertThat(alternatives.get(0).path("condition").asText()).isEqualTo("NM");
        }

        @Test
        @DisplayName("Case 3: Seller has vacation_mode = true, returns vacationMode: true and sellerAvailable: false")
        void case3_SellerOnVacation_ReturnsUnavailableAndOutOfStock() {
            CompletedOrder order = createCompletedOrder(CardCondition.NM, "100.00", 5);

            // Put seller on vacation
            dsl.update(SELLER_PROFILE)
                    .set(SELLER_PROFILE.VACATION_MODE, true)
                    .where(SELLER_PROFILE.ID.eq(order.seller().profileId()))
                    .execute();

            Response res = api.get("/api/v1/orders/" + order.salesOrderId() + "/reorder-preview", order.buyerBearer());

            assertThat(res.status()).isEqualTo(200);

            JsonNode so = res.data().path("sellerOrders").get(0);
            assertThat(so.path("vacationMode").asBoolean()).isTrue();
            assertThat(so.path("sellerAvailable").asBoolean()).isFalse();

            JsonNode item = so.path("items").get(0);
            assertThat(item.path("currentListing").isNull()).isTrue();
            assertThat(item.path("alternativeConditions").size()).isEqualTo(0);
            assertThat(item.path("status").asText()).isEqualTo("OUT_OF_STOCK");
        }

        @Test
        @DisplayName("Case 4: Accessing another user's order or non-COMPLETED order returns 403/404/400")
        void case4_OwnershipAndStatusValidation() {
            CompletedOrder order = createCompletedOrder(CardCondition.NM, "100.00", 5);

            // Another buyer attempts to access Buyer 1's completed order -> 403 Forbidden
            AuthPrincipal otherBuyer = data.buyer();
            String otherBearer = data.bearer(otherBuyer);
            Response forbiddenRes = api.get("/api/v1/orders/" + order.salesOrderId() + "/reorder-preview", otherBearer);
            assertThat(forbiddenRes.status()).isEqualTo(403);

            // Accessing non-existent order -> 404 Not Found
            Response notFoundRes = api.get("/api/v1/orders/999999999/reorder-preview", order.buyerBearer());
            assertThat(notFoundRes.status()).isEqualTo(404);

            // Accessing non-COMPLETED order (e.g. newly placed order in PENDING_PAYMENT) -> 400 Bad Request
            Seller seller = data.seller();
            TestData.Card card = data.card(data.game("Pokemon"), "Charmander", "C");
            TestData.Listing listing = data.onSale(seller, card, CardCondition.NM, 5, "50.00");
            AuthPrincipal buyer = data.buyer();
            String buyerBearer = data.bearer(buyer);
            cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
            CheckoutResponse pendingOrder = checkoutService.checkout(buyer, "key-" + data.tag(), null, null);

            Response nonCompletedRes = api.get("/api/v1/orders/" + pendingOrder.orderId() + "/reorder-preview", buyerBearer);
            assertThat(nonCompletedRes.status()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/cart/items/batch")
    class BatchAddToCartTests {

        @Test
        @DisplayName("Case 1: Successfully adds multiple valid listings to the cart in one request")
        void case1_SuccessfullyAddsMultipleValidListings() {
            Seller seller = data.seller();
            TestData.Card card1 = data.card(data.game("Pokemon"), "Bulbasaur", "C");
            TestData.Card card2 = data.card(data.game("Pokemon"), "Squirtle", "C");

            TestData.Listing listing1 = data.onSale(seller, card1, CardCondition.NM, 10, "150.00");
            TestData.Listing listing2 = data.onSale(seller, card2, CardCondition.LP, 5, "250.00");

            AuthPrincipal buyer = data.buyer();
            String bearer = data.bearer(buyer);

            List<Map<String, Object>> batchPayload = List.of(
                    Map.of("listingId", listing1.id(), "quantity", 2),
                    Map.of("listingId", listing2.id(), "quantity", 1)
            );

            Response res = api.post("/api/v1/cart/items/batch", bearer, batchPayload);

            assertThat(res.status()).isEqualTo(200);

            JsonNode dataNode = res.data();
            assertThat(dataNode.path("totalQuantity").asInt()).isEqualTo(3);
            assertThat(dataNode.path("items").size()).isEqualTo(2);

            // Verify in database
            long cartId = dsl.select(CART.ID).from(CART).where(CART.USER_ID.eq(buyer.userId())).fetchSingle(CART.ID);
            int count = dsl.fetchCount(CART_ITEM, CART_ITEM.CART_ID.eq(cartId));
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("Case 2: Atomic rollback — when item 1 is valid and item 2 fails (409 Conflict), item 1 is NOT saved")
        void case2_AtomicRollbackOnConflict() {
            Seller seller = data.seller();
            TestData.Card card1 = data.card(data.game("Pokemon"), "Gengar", "R");
            TestData.Card card2 = data.card(data.game("Pokemon"), "Alakazam", "R");

            TestData.Listing validListing = data.onSale(seller, card1, CardCondition.NM, 5, "300.00");
            TestData.Listing outOfStockListing = data.onSale(seller, card2, CardCondition.NM, 1, "500.00");

            // Make the second listing out of stock
            data.sellOut(seller, outOfStockListing);

            AuthPrincipal buyer = data.buyer();
            String bearer = data.bearer(buyer);

            List<Map<String, Object>> batchPayload = List.of(
                    Map.of("listingId", validListing.id(), "quantity", 1),
                    Map.of("listingId", outOfStockListing.id(), "quantity", 1)
            );

            Response res = api.post("/api/v1/cart/items/batch", bearer, batchPayload);

            assertThat(res.status()).isEqualTo(409);

            // Verify item 1 was rolled back and is NOT in cart_item
            Long cartId = dsl.select(CART.ID).from(CART).where(CART.USER_ID.eq(buyer.userId())).fetchOne(CART.ID);
            if (cartId != null) {
                int count = dsl.fetchCount(CART_ITEM, CART_ITEM.CART_ID.eq(cartId).and(CART_ITEM.LISTING_ID.eq(validListing.id())));
                assertThat(count).isEqualTo(0);
            }
        }
    }
}
