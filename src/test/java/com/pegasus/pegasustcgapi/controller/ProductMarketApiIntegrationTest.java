package com.pegasus.pegasustcgapi.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProductMarketApiIntegrationTest extends PostgresIntegrationTest {

    private ApiClient client;
    private Card card;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-10-15T08:00:00Z"));
        client = new ApiClient(port);
        card = data.card(data.game("Market test"), "Market card", "R");
    }

    @Test
    void returnsAllConditionsWithThirtyDayChangeAndLatestFulfilledSaleWithoutLogin() {
        stat("SEALED", LocalDate.of(2026, 10, 15), "3000", 2);
        stat("SEALED", LocalDate.of(2026, 9, 15), "2500", 4);
        stat("SEALED", LocalDate.of(2026, 9, 14), "2400", 4);
        stat("NM", LocalDate.of(2026, 10, 15), "2000", 3);
        sale("PAID", "SHIPPED", "SEALED", "2900", "2026-10-14T08:00:00Z");
        sale("CANCELLED", "SHIPPED", "SEALED", "9999", "2026-10-15T01:00:00Z");
        sale("PENDING_PAYMENT", "SHIPPED", "SEALED", "8888", "2026-10-15T02:00:00Z");

        Response response = client.get(path(), null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().size()).isEqualTo(2);
        JsonNode sealed = find(response.data(), "SEALED");
        assertThat(sealed.path("catalogVariantId").asLong()).isEqualTo(card.variantId());
        assertThat(sealed.path("medianPrice").decimalValue()).isEqualByComparingTo("3000");
        assertThat(sealed.path("statDate").asString()).isEqualTo("2026-10-15");
        assertThat(sealed.path("change30dPercent").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(sealed.path("lowSample").asBoolean()).isTrue();
        assertThat(sealed.path("lastSold").path("price").decimalValue()).isEqualByComparingTo("2900");
        assertThat(OffsetDateTime.parse(sealed.path("lastSold").path("soldAt").asString()).toInstant())
                .isEqualTo(Instant.parse("2026-10-14T08:00:00Z"));
    }

    @Test
    void missingThirtyDayBaselineReturnsNull() {
        stat("NM", LocalDate.of(2026, 10, 15), "1000", 3);
        stat("NM", LocalDate.of(2026, 9, 16), "900", 3);

        JsonNode market = client.get(path(), null).data().get(0);

        assertThat(market.path("change30dPercent").isNull()).isTrue();
    }

    @Test
    void unpaidAndUnshippedOrdersDoNotCountAsLastSold() {
        stat("NM", LocalDate.of(2026, 10, 15), "1000", 3);
        sale("PAID", "PREPARING", "NM", "990", "2026-10-14T08:00:00Z");
        sale("PENDING_PAYMENT", "PENDING_PAYMENT", "NM", "980", "2026-10-15T08:00:00Z");

        JsonNode market = client.get(path(), null).data().get(0);

        assertThat(market.path("lastSold").isNull()).isTrue();
    }

    @Test
    void productWithoutStatisticsReturnsAnEmptyList() {
        String slug = dsl.fetchSingle("SELECT slug FROM catalog_product WHERE id = ?", card.productId())
                .get(0, String.class);
        Response response = client.get("/api/v1/catalog/products/" + slug + "/market", null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().size()).isZero();
    }

    private String path() {
        return "/api/v1/catalog/products/" + card.productId() + "/market";
    }

    private void stat(String condition, LocalDate date, String median, int sample) {
        dsl.execute("""
                INSERT INTO variant_market_stat
                       (catalog_variant_id, condition_code, stat_date, median_price, sample_size)
                VALUES (?, ?, ?, ?, ?)
                """, card.variantId(), condition, date, new BigDecimal(median), sample);
    }

    private void sale(String parentStatus, String sellerStatus, String condition, String price, String at) {
        long buyerId = data.buyer().userId();
        long sellerId = data.seller().profileId();
        BigDecimal amount = new BigDecimal(price);
        String orderNumber = "MKT-" + data.tag();
        long orderId = dsl.fetchSingle("""
                INSERT INTO sales_order
                       (order_number, buyer_id, status, shipping_address_snapshot, items_subtotal, grand_total)
                VALUES (?, ?, ?, '{}'::jsonb, ?, ?) RETURNING id
                """, orderNumber, buyerId, parentStatus, amount, amount).get(0, Long.class);
        long sellerOrderId = dsl.fetchSingle("""
                INSERT INTO seller_order
                       (sales_order_id, seller_profile_id, seller_order_number, status,
                        items_subtotal, grand_total, seller_net_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, orderId, sellerId, orderNumber + "-S", sellerStatus, amount, amount, amount)
                .get(0, Long.class);
        dsl.execute("""
                INSERT INTO order_item
                       (seller_order_id, catalog_variant_id, quantity, unit_price, line_total,
                        product_name_snapshot, variant_label_snapshot, condition_snapshot, created_at)
                VALUES (?, ?, 1, ?, ?, 'Market card', 'EN', ?, CAST(? AS timestamptz))
                """, sellerOrderId, card.variantId(), amount, amount, condition, OffsetDateTime.parse(at));
    }

    private static JsonNode find(JsonNode markets, String condition) {
        for (JsonNode market : markets) {
            if (condition.equals(market.path("condition").asString())) {
                return market;
            }
        }
        throw new AssertionError("No market for " + condition);
    }
}
