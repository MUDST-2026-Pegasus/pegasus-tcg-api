package com.pegasus.pegasustcgapi.controller.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnItem.RETURN_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class SellerStockForecastApiIntegrationTest extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/sellers/me/listings/stock-forecast";
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private ApiClient api;
    private Seller seller;
    private Listing listing;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        api = new ApiClient(port);
        seller = data.seller();
        var card = data.card(data.game("Forecast"), "Forecast card " + data.tag(), "R");
        listing = data.onSale(seller, card, CardCondition.NM, 12, "100.00");
    }

    @Test
    void onlyOwnerCanReadListAndDetail() {
        Seller other = data.seller();
        String ownerToken = data.bearer(seller.principal());
        String otherToken = data.bearer(other.principal());
        String buyerToken = data.bearer(data.buyer());
        String detail = "/api/v1/sellers/me/listings/" + listing.id() + "/stock-forecast";

        assertThat(api.get(PATH, ownerToken).status()).isEqualTo(200);
        assertThat(api.get(detail, ownerToken).status()).isEqualTo(200);
        assertThat(api.get(PATH, otherToken).data()).isEmpty();
        assertThat(api.get(detail, otherToken).errorCode()).isEqualTo("LISTING_NOT_FOUND");
        assertThat(api.get(PATH, buyerToken).status()).isIn(403, 404);
        assertThat(api.get(detail, buyerToken).status()).isIn(403, 404);
        assertThat(api.get(PATH, null).status()).isEqualTo(401);
    }

    @Test
    void completedSalesUseInclusiveStartExclusiveEnd() {
        AuthPrincipal buyer = data.buyer();
        OffsetDateTime end = NOW.atOffset(ZoneOffset.UTC);
        completedOrder(buyer, 2, end.minusDays(30));
        completedOrder(buyer, 3, end.minusDays(30).minusSeconds(1));
        completedOrder(buyer, 4, end);

        JsonNode row = ownForecast();
        assertThat(row.path("completedSales30Days").asInt()).isEqualTo(2);
        assertThat(row.path("status").asString()).isEqualTo("FORECAST_AVAILABLE");
        assertThat(row.path("periodStart").asString()).startsWith("2026-10-06T12:00");
        assertThat(row.path("periodEnd").asString()).startsWith("2026-11-05T12:00");
    }

    @Test
    void closedPartialReturnSubtractsOnlyReturnedUnits() {
        AuthPrincipal buyer = data.buyer();
        long orderId = completedOrder(buyer, 3, NOW.atOffset(ZoneOffset.UTC).minusDays(1));
        long itemId = dsl.select(ORDER_ITEM.ID).from(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(orderId)).fetchSingle(ORDER_ITEM.ID);
        long returnId = dsl.insertInto(RETURN_REQUEST)
                .set(RETURN_REQUEST.SELLER_ORDER_ID, orderId)
                .set(RETURN_REQUEST.BUYER_ID, buyer.userId())
                .set(RETURN_REQUEST.REASON_CODE, "DAMAGED")
                .set(RETURN_REQUEST.STATUS, "REQUESTED")
                .set(RETURN_REQUEST.RESOLUTION, "PARTIAL_REFUND")
                .returningResult(RETURN_REQUEST.ID).fetchSingle(RETURN_REQUEST.ID);
        dsl.insertInto(RETURN_ITEM)
                .set(RETURN_ITEM.RETURN_REQUEST_ID, returnId)
                .set(RETURN_ITEM.ORDER_ITEM_ID, itemId)
                .set(RETURN_ITEM.QUANTITY, 1).execute();

        assertThat(ownForecast().path("completedSales30Days").asInt()).isEqualTo(3);
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, "CLOSED")
                .where(RETURN_REQUEST.ID.eq(returnId)).execute();

        JsonNode row = ownForecast();
        assertThat(row.path("completedSales30Days").asInt()).isEqualTo(2);
        assertThat(row.path("status").asString()).isEqualTo("FORECAST_AVAILABLE");
    }

    private JsonNode ownForecast() {
        var response = api.get("/api/v1/sellers/me/listings/" + listing.id() + "/stock-forecast",
                data.bearer(seller.principal()));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(200);
        return response.data();
    }

    private long completedOrder(AuthPrincipal buyer, int quantity, OffsetDateTime completedAt) {
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), quantity));
        var order = checkout.checkout(buyer, "forecast-" + data.tag(), null, null);
        long sellerOrderId = order.sellerOrders().getFirst().id();
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.STATUS, "COMPLETED")
                .set(SELLER_ORDER.COMPLETED_AT, completedAt)
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).execute();
        return sellerOrderId;
    }
}
