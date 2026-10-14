package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Dashboard;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.MonthlySales;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One shop's dashboard against the real schema. Every test makes its own shops,
 * so the numbers are exactly what the test placed.
 */
class SellerDashboardIntegrationTest extends PostgresIntegrationTest {

    @Autowired SellerDashboardService dashboards;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired PaymentService payments;

    private record Placed(AuthPrincipal buyer, CheckoutResponse order, Listing listing) {
    }

    /** {@code buying} of a listing that holds {@code stock} cards. */
    private Placed place(Seller seller, int stock, int buying, String price) {
        var card = data.card(data.game("Pokemon"), "Dash " + data.tag(), "R");
        Listing listing = data.onSale(seller, card, CardCondition.NM, stock, price);
        AuthPrincipal buyer = data.buyer();
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), buying));
        CheckoutResponse order = checkout.checkout(buyer, "dash-" + data.tag(), null,
                new CheckoutRequest(data.address(buyer), null));
        return new Placed(buyer, order, listing);
    }

    private Placed paid(Seller seller, int stock, int buying, String price) {
        Placed placed = place(seller, stock, buying, price);
        lifecycle.markOrderPaid(placed.buyer(), placed.order().orderId());
        return placed;
    }

    @Test
    void countsOnlyThisShopsPaidOrders() {
        Seller seller = data.seller();
        paid(seller, 5, 2, "150.00");
        place(seller, 1, 1, "90.00");
        paid(data.seller(), 1, 1, "999.00");

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);

        assertThat(dashboard.sales().value()).isEqualByComparingTo("300.00");
        assertThat(dashboard.sales().orderCount()).isEqualTo(1);
        assertThat(dashboard.sales().previous()).isEqualByComparingTo("0");
        assertThat(dashboard.orders())
                .containsEntry("PAID", 1L)
                .containsEntry("PENDING_PAYMENT", 1L)
                .hasSize(2);
        assertThat(dashboard.recentOrders()).hasSize(2)
                .allSatisfy(order -> assertThat(order.firstItemName()).startsWith("Dash "));
    }

    @Test
    void monthSummaryNetsCommissionAndCostOffTheGrandTotal() {
        Seller seller = data.seller();
        paid(seller, 3, 1, "200.00");

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);
        var month = dashboard.month();

        assertThat(month.revenue()).isGreaterThanOrEqualTo(dashboard.sales().value());
        assertThat(month.net()).isEqualByComparingTo(
                month.revenue().subtract(month.commission()).subtract(month.cost()));
    }

    @Test
    void trendHasTwelveMonthsEndingWithTheSelectedOne() {
        Seller seller = data.seller();
        paid(seller, 1, 1, "120.00");
        YearMonth now = YearMonth.now(clock.withZone(AdminMetricsService.ZONE));

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);

        assertThat(dashboard.period().month()).isEqualTo(now.toString());
        assertThat(dashboard.sales().monthly()).hasSize(12);
        assertThat(dashboard.sales().monthly().getFirst().month()).isEqualTo(now.minusMonths(11).toString());
        MonthlySales last = dashboard.sales().monthly().getLast();
        assertThat(last.month()).isEqualTo(now.toString());
        assertThat(last.amount()).isEqualByComparingTo("120.00");
        assertThat(dashboard.sales().monthly().subList(0, 11))
                .allSatisfy(month -> assertThat(month.amount()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void anEarlierMonthHasNoneOfThisMonthsSales() {
        Seller seller = data.seller();
        paid(seller, 1, 1, "120.00");
        YearMonth earlier = YearMonth.now(clock.withZone(AdminMetricsService.ZONE)).minusMonths(2);

        Dashboard dashboard = dashboards.dashboard(seller.principal(), earlier.toString());

        assertThat(dashboard.sales().value()).isEqualByComparingTo("0");
        assertThat(dashboard.month().revenue()).isEqualByComparingTo("0");
    }

    @Test
    void aCancelledOrderIsNotASale() {
        Seller seller = data.seller();
        Placed placed = paid(seller, 1, 1, "150.00");
        lifecycle.cancelBuyerOrder(placed.buyer(), placed.order().orderId(), new CancelOrderRequest("Changed my mind"));

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);

        assertThat(dashboard.sales().value()).isEqualByComparingTo("0");
        assertThat(dashboard.orders()).containsEntry("CANCELLED", 1L);
    }

    @Test
    void lowStockIsActiveWithOneOrTwoCardsLeftFewestFirst() {
        Seller seller = data.seller();
        Placed twoLeft = place(seller, 3, 1, "100.00");
        Placed oneLeft = place(seller, 2, 1, "100.00");
        data.onSale(seller, data.card(data.game("Pokemon"), "Plenty " + data.tag(), "C"),
                CardCondition.NM, 10, "50.00");

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);

        assertThat(dashboard.listings().active()).isEqualTo(3);
        assertThat(dashboard.listings().publishedThisMonth()).isEqualTo(3);
        assertThat(dashboard.listings().lowStockThreshold()).isEqualTo(SellerDashboardService.LOW_STOCK_THRESHOLD);
        assertThat(dashboard.listings().lowStockCount()).isEqualTo(2);
        assertThat(dashboard.listings().lowStock())
                .extracting(row -> row.listingId())
                .containsExactly(oneLeft.listing().id(), twoLeft.listing().id());
        assertThat(dashboard.listings().lowStock().getFirst().quantityAvailable()).isEqualTo(1);
    }

    @Test
    void moneyStillInEscrowIsPendingNetOfCommissionUntilItIsReleased() {
        Seller seller = data.seller();
        Placed held = place(seller, 3, 1, "200.00");
        payments.payMock(held.buyer(), held.order().orderId());
        Placed released = place(seller, 3, 1, "300.00");
        payments.payMock(released.buyer(), released.order().orderId());
        SellerOrderResponse releasedOrder = released.order().sellerOrders().getFirst();
        lifecycle.shipSellerOrder(seller.principal(), releasedOrder.id(),
                new ShipOrderRequest("Thailand Post", "DASH-" + data.tag(), "TH_POST", null, null));
        lifecycle.autoCompleteSellerOrder(releasedOrder.id());
        place(seller, 3, 1, "90.00");
        paid(data.seller(), 1, 1, "999.00");

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);

        // Held money sits in the PLATFORM wallet, so the seller wallet alone would say zero
        assertThat(dashboard.wallet().pending())
                .isEqualByComparingTo(held.order().sellerOrders().getFirst().sellerNetAmount());
        // On release the same net moves across — nothing counted twice, nothing lost
        assertThat(dashboard.wallet().withdrawable()).isEqualByComparingTo(releasedOrder.sellerNetAmount());
    }

    @Test
    void aShopWithNothingYetIsAllZeroes() {
        Seller seller = data.seller();

        Dashboard dashboard = dashboards.dashboard(seller.principal(), null);

        assertThat(dashboard.sales().value()).isEqualByComparingTo("0");
        assertThat(dashboard.orders()).isEmpty();
        assertThat(dashboard.listings().lowStock()).isEmpty();
        assertThat(dashboard.reviews().average()).isNull();
        assertThat(dashboard.reviews().count()).isZero();
        assertThat(dashboard.wallet().withdrawable()).isEqualByComparingTo("0");
        assertThat(dashboard.wallet().pending()).isEqualByComparingTo("0");
        assertThat(dashboard.recentOrders()).isEmpty();
    }

    @Test
    void aBadMonthOrAnAccountThatNeverSoldIsRefused() {
        Seller seller = data.seller();
        assertThatThrownBy(() -> dashboards.dashboard(seller.principal(), "October"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> dashboards.dashboard(data.buyer(), null))
                .isInstanceOf(NotFoundException.class);
    }
}
