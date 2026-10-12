package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.AdminOrderViews.Board;
import com.pegasus.pegasustcgapi.dto.AdminOrderViews.Column;
import com.pegasus.pegasustcgapi.dto.AdminOrderViews.Summary;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The board and list read every seller's data, and other tests leave orders behind
 * in the shared database. So counts are compared before and after, and lists are
 * narrowed to this test's buyer by searching their unique username.
 */
class AdminOrderIntegrationTest extends PostgresIntegrationTest {

    @Autowired AdminOrderService adminOrders;
    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired ReviewService reviews;

    private record Placed(Seller seller, AuthPrincipal buyer, long sellerOrderId) {
    }

    private Placed placeOrder(int cards, String price) {
        Seller seller = data.seller();
        AuthPrincipal buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Admin Board " + data.tag(), "SAR");
        var listing = data.onSale(seller, card, CardCondition.NM, cards, price);
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), cards));
        var order = checkout.checkout(buyer, "admin-order-" + data.tag(), null,
                new CheckoutRequest(data.address(buyer), "Leave at the door"));
        return new Placed(seller, buyer, order.sellerOrders().getFirst().id());
    }

    private List<Summary> ofBuyer(AuthPrincipal buyer, List<String> statuses) {
        return adminOrders.list(statuses, buyer.username(), null, null, null, null, null, 0, 20).items();
    }

    private static Column column(Board board, String key) {
        return board.columns().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void listShowsOneRowPerSubOrderWithBothParties() {
        Placed placed = placeOrder(2, "150.00");

        List<Summary> rows = ofBuyer(placed.buyer(), List.of());

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.sellerOrderId()).isEqualTo(placed.sellerOrderId());
            assertThat(row.status()).isEqualTo("PENDING_PAYMENT");
            assertThat(row.itemCount()).isEqualTo(2);
            assertThat(row.grandTotal()).isPositive();
            assertThat(row.orderNumber()).startsWith("PGS-");
            assertThat(row.seller().id()).isEqualTo(placed.seller().profileId());
            assertThat(row.seller().username()).isEqualTo(placed.seller().principal().username());
            assertThat(row.buyer().id()).isEqualTo(placed.buyer().userId());
            assertThat(row.paidAt()).isNull();
            assertThat(row.tracking()).isNull();
            assertThat(row.reviewRating()).isNull();
        });
    }

    @Test
    void searchMatchesTheSellerAndTheOrderNumberToo() {
        Placed placed = placeOrder(1, "90.00");
        Summary row = ofBuyer(placed.buyer(), List.of()).getFirst();

        assertThat(adminOrders.list(List.of(), placed.seller().principal().username(),
                null, null, null, null, null, 0, 20).items())
                .extracting(Summary::sellerOrderId).containsExactly(placed.sellerOrderId());
        assertThat(adminOrders.list(List.of(), row.orderNumber().toLowerCase(),
                null, null, null, null, null, 0, 20).items())
                .extracting(Summary::sellerOrderId).contains(placed.sellerOrderId());
    }

    @Test
    void statusFilterAcceptsSeveralAndCommaSeparatedValues() {
        Placed placed = placeOrder(1, "90.00");

        assertThat(ofBuyer(placed.buyer(), List.of("PAID", "COMPLETED"))).isEmpty();
        assertThat(ofBuyer(placed.buyer(), List.of("paid,pending_payment"))).hasSize(1);
    }

    @Test
    void unknownStatusOrSortIsABadRequest() {
        assertThatThrownBy(() -> adminOrders.list(List.of("SHIPPING"), null, null, null,
                null, null, null, 0, 20))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> adminOrders.list(List.of(), null, null, null,
                null, null, "CHEAPEST", 0, 20))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void boardCountsEveryOrderInAColumnAndFlagsOverduePayment() {
        Board before = adminOrders.board(3);
        placeOrder(1, "120.00");

        Board after = adminOrders.board(3);
        assertThat(after.columns()).extracting(Column::key).containsExactly(
                "PENDING_PAYMENT", "AWAITING_SHIPMENT", "IN_TRANSIT", "COMPLETED", "DISPUTED");
        assertThat(column(after, "PENDING_PAYMENT").count())
                .isEqualTo(column(before, "PENDING_PAYMENT").count() + 1);
        assertThat(column(after, "PENDING_PAYMENT").items()).hasSizeLessThanOrEqualTo(3);
        assertThat(after.alerts().paymentTimeoutMinutes()).isEqualTo(60);

        // The sweep is off in tests, so moving past the deadline leaves it unpaid and overdue.
        long overdueNow = after.alerts().overduePaymentCount();
        clock.advance(Duration.ofHours(2));
        assertThat(adminOrders.board(3).alerts().overduePaymentCount()).isGreaterThan(overdueNow);
    }

    @Test
    void completedOrderShowsItsReviewAndMovesColumn() {
        Placed placed = placeOrder(1, "100.00");
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.STATUS, "COMPLETED")
                .where(SELLER_ORDER.ID.eq(placed.sellerOrderId())).execute();
        reviews.create(placed.buyer().userId(), placed.sellerOrderId(), (short) 4, "Good", "Fine");

        assertThat(ofBuyer(placed.buyer(), List.of("COMPLETED")))
                .singleElement()
                .extracting(Summary::reviewRating)
                .isEqualTo((short) 4);
    }

    @Test
    void detailCarriesItemsHistoryAndAddress() {
        Placed placed = placeOrder(1, "100.00");

        var detail = adminOrders.detail(placed.sellerOrderId());

        assertThat(detail.summary().sellerOrderId()).isEqualTo(placed.sellerOrderId());
        assertThat(detail.order().items()).hasSize(1);
        assertThat(detail.order().statusHistory()).isNotEmpty();
        assertThat(detail.shippingAddressSnapshot()).contains("Sukhumvit");
        assertThat(detail.buyerNote()).isEqualTo("Leave at the door");
    }

    @Test
    void unknownOrderIs404() {
        assertThatThrownBy(() -> adminOrders.detail(Long.MAX_VALUE))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void onlyAnAdminMayReadTheBoard() {
        ApiClient client = new ApiClient(port);

        assertThat(client.get("/api/v1/admin/orders/board", data.bearer(data.buyer())).status())
                .isEqualTo(403);
        assertThat(client.get("/api/v1/admin/orders/board", null).status()).isEqualTo(401);

        ApiClient.Response ok = client.get("/api/v1/admin/orders/board", data.bearer(data.admin()));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().get("columns")).hasSize(5);
    }
}
