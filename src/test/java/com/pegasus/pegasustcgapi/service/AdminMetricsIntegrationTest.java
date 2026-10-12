package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.GameShare;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.MonthlySales;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.Overview;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.TopSeller;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Each test files its orders under a month long past that no other test touches,
 * so the figures for that month are exactly what the test put there.
 */
class AdminMetricsIntegrationTest extends PostgresIntegrationTest {

    @Autowired AdminMetricsService metrics;
    @Autowired CheckoutService checkout;
    @Autowired CartService cart;

    private record Sold(Seller seller, long sellerOrderId, long salesOrderId, BigDecimal itemsSubtotal,
            short gameId) {
    }

    /** A real checkout, then moved by hand to the status and paid time the test needs. */
    private Sold sell(String price, String status, OffsetDateTime paidAt, String productType) {
        Seller seller = data.seller();
        var buyer = data.buyer();
        short game = data.game("Metrics");
        var card = data.card(game, "Metrics " + data.tag(), "SAR");
        dsl.update(CATALOG_PRODUCT).set(CATALOG_PRODUCT.PRODUCT_TYPE, productType)
                .where(CATALOG_PRODUCT.ID.eq(card.productId())).execute();
        var listing = data.onSale(seller, card, CardCondition.NM, 1, price);
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "metrics-" + data.tag(), null, null);
        long sellerOrderId = order.sellerOrders().getFirst().id();

        dsl.update(SALES_ORDER).set(SALES_ORDER.PAID_AT, paidAt)
                .where(SALES_ORDER.ID.eq(order.orderId())).execute();
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.STATUS, status)
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).execute();

        BigDecimal subtotal = dsl.select(SELLER_ORDER.ITEMS_SUBTOTAL).from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle().value1();
        return new Sold(seller, sellerOrderId, order.orderId(), subtotal, game);
    }

    @Test
    void salesCountFromThePaidTimeInBangkokNotUtc() {
        // 17:30 UTC on 31 March is 00:30 on 1 April in Bangkok.
        Sold sold = sell("120.00", "PAID", OffsetDateTime.parse("2019-03-31T17:30:00Z"), "SINGLE_CARD");

        Overview april = metrics.overview("2019-04");
        Overview march = metrics.overview("2019-03");

        assertThat(april.stats().gmv().value()).isEqualByComparingTo(sold.itemsSubtotal());
        assertThat(april.stats().gmv().previous()).isEqualByComparingTo("0");
        assertThat(march.stats().gmv().value()).isEqualByComparingTo("0");
        assertThat(april.period().month()).isEqualTo("2019-04");
        assertThat(april.period().from()).isEqualTo(OffsetDateTime.parse("2019-04-01T00:00:00+07:00"));
    }

    @Test
    void cancelledAndUnpaidOrdersAreNotSales() {
        OffsetDateTime paid = OffsetDateTime.parse("2019-06-10T05:00:00Z");
        Sold kept = sell("100.00", "SHIPPED", paid, "SINGLE_CARD");
        sell("500.00", "CANCELLED", paid, "SINGLE_CARD");
        sell("700.00", "RETURN_REQUESTED", paid, "SINGLE_CARD");

        assertThat(metrics.overview("2019-06").stats().gmv().value())
                .isEqualByComparingTo(kept.itemsSubtotal());
    }

    @Test
    void trendSplitsSinglesFromSealedAndCoversSixMonths() {
        OffsetDateTime paid = OffsetDateTime.parse("2019-08-15T05:00:00Z");
        Sold single = sell("100.00", "PAID", paid, "SINGLE_CARD");
        Sold sealed = sell("900.00", "COMPLETED", paid, "BOOSTER_BOX");
        sell("50.00", "PAID", paid, "ACCESSORY");

        Overview august = metrics.overview("2019-08");

        assertThat(august.monthlySales()).extracting(MonthlySales::month)
                .containsExactly("2019-03", "2019-04", "2019-05", "2019-06", "2019-07", "2019-08");
        MonthlySales last = august.monthlySales().getLast();
        assertThat(last.singles()).isEqualByComparingTo(single.itemsSubtotal());
        assertThat(last.sealed()).isEqualByComparingTo(sealed.itemsSubtotal());
    }

    @Test
    void topSellersAndGameShareRankByItemsSold() {
        OffsetDateTime paid = OffsetDateTime.parse("2019-10-03T05:00:00Z");
        Sold big = sell("800.00", "PAID", paid, "SINGLE_CARD");
        Sold small = sell("200.00", "PAID", paid, "SINGLE_CARD");

        Overview october = metrics.overview("2019-10");

        assertThat(october.topSellers()).extracting(TopSeller::sellerProfileId)
                .containsExactly(big.seller().profileId(), small.seller().profileId());
        assertThat(october.gameShare()).extracting(GameShare::gameId)
                .containsExactly(big.gameId(), small.gameId());
        assertThat(october.gameShare()).extracting(GameShare::percent)
                .containsExactly(new BigDecimal("80.0"), new BigDecimal("20.0"));
    }

    @Test
    void commissionIsWhatCompletedOrdersFroze() {
        Sold done = sell("1000.00", "COMPLETED", OffsetDateTime.parse("2019-12-01T05:00:00Z"), "SINGLE_CARD");
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.COMPLETED_AT, OffsetDateTime.parse("2019-12-20T05:00:00Z"))
                .where(SELLER_ORDER.ID.eq(done.sellerOrderId())).execute();
        BigDecimal frozen = dsl.select(SELLER_ORDER.COMMISSION_AMOUNT).from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(done.sellerOrderId())).fetchSingle().value1();

        var commission = metrics.overview("2019-12").stats().commission();

        assertThat(commission.value()).isEqualByComparingTo(frozen);
        assertThat(commission.averageRatePercent())
                .isEqualByComparingTo(frozen.multiply(new BigDecimal("100"))
                        .divide(done.itemsSubtotal(), 2, java.math.RoundingMode.HALF_UP));
        assertThat(commission.sparkline()).hasSize(8);
        assertThat(commission.sparkline().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(frozen);
    }

    @Test
    void anEmptyMonthIsZerosNotAnError() {
        Overview empty = metrics.overview("2001-01");

        assertThat(empty.stats().gmv().value()).isEqualByComparingTo("0");
        assertThat(empty.stats().gmv().sparkline()).hasSize(8).allMatch(v -> v.signum() == 0);
        assertThat(empty.stats().commission().averageRatePercent()).isNull();
        assertThat(empty.topSellers()).isEmpty();
        assertThat(empty.gameShare()).isEmpty();
    }

    @Test
    void monthMustBeYearDashMonth() {
        assertThatThrownBy(() -> metrics.overview("October"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyAnAdminMayReadTheOverview() {
        ApiClient client = new ApiClient(port);

        assertThat(client.get("/api/v1/admin/metrics/overview", data.bearer(data.buyer())).status())
                .isEqualTo(403);
        ApiClient.Response ok = client.get("/api/v1/admin/metrics/overview?month=2001-01",
                data.bearer(data.admin()));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().get("monthlySales")).hasSize(6);
    }
}
