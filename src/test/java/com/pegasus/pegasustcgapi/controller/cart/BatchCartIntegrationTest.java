package com.pegasus.pegasustcgapi.controller.cart;

import static com.pegasus.pegasustcgapi.jooq.tables.Cart.CART;
import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

@DisplayName("Batch Add-to-Cart Integration Test")
class BatchCartIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CartService cartService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
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
            assertThat(res.data().path("details").path("listingId").asLong()).isEqualTo(outOfStockListing.id());

            // Verify item 1 was rolled back and is NOT in cart_item
            Long cartId = dsl.select(CART.ID).from(CART).where(CART.USER_ID.eq(buyer.userId())).fetchOne(CART.ID);
            if (cartId != null) {
                int count = dsl.fetchCount(CART_ITEM, CART_ITEM.CART_ID.eq(cartId).and(CART_ITEM.LISTING_ID.eq(validListing.id())));
                assertThat(count).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Case 3: Batch size exceeding limit (> 50 items) returns 400 Bad Request")
        void case3_BatchSizeLimitExceeded() {
            AuthPrincipal buyer = data.buyer();
            String bearer = data.bearer(buyer);

            List<Map<String, Object>> largeBatch = java.util.stream.IntStream.rangeClosed(1, 51)
                    .mapToObj(i -> Map.<String, Object>of("listingId", (long) i, "quantity", 1))
                    .toList();

            Response res = api.post("/api/v1/cart/items/batch", bearer, largeBatch);
            assertThat(res.status()).isEqualTo(400);
        }
    }
}
