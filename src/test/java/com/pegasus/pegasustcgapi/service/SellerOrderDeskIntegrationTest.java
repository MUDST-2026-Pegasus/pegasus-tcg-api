package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Summary;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The seller's order desk against the real schema. Each test makes its own shop,
 * so lists and counts only ever hold that test's orders.
 */
class SellerOrderDeskIntegrationTest extends PostgresIntegrationTest {

    @Autowired SellerOrderDeskService desk;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired PaymentService payments;

    private record Placed(Seller seller, AuthPrincipal buyer, long salesOrderId, long sellerOrderId,
            long listingId) {
    }

    private Placed place(Seller seller, int cards, String price) {
        var card = data.card(data.game("Pokemon"), "Desk " + data.tag(), "SR");
        Listing listing = data.onSale(seller, card, CardCondition.NM, cards, price);
        AuthPrincipal buyer = data.buyer();
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), cards));
        var order = checkout.checkout(buyer, "desk-" + data.tag(), null,
                new CheckoutRequest(data.address(buyer), "Please use a top loader"));
        return new Placed(seller, buyer, order.orderId(), order.sellerOrders().getFirst().id(), listing.id());
    }

    private Placed paid(Seller seller) {
        Placed placed = place(seller, 1, "200.00");
        lifecycle.markOrderPaid(placed.buyer(), placed.salesOrderId());
        return placed;
    }

    private static ShipOrderRequest shipment() {
        return new ShipOrderRequest("Kerry Express", "KE1234567890", "KERRY", null, null);
    }

    private SellerOrderStatusHistoryRecord latestHistory(long sellerOrderId) {
        return dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.ID.desc())
                .limit(1)
                .fetchSingle();
    }

    @Test
    void listShowsOnlyTheShopsOwnOrdersWithBuyerAddressAndShipBy() {
        Seller seller = data.seller();
        Placed mine = paid(seller);
        paid(data.seller());

        List<Summary> rows = desk.list(seller.principal(), List.of(), null, null, 0, 20).items();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.order().id()).isEqualTo(mine.sellerOrderId());
            assertThat(row.order().items()).hasSize(1);
            assertThat(row.orderNumber()).startsWith("PGS-");
            assertThat(row.buyer().id()).isEqualTo(mine.buyer().userId());
            assertThat(row.buyer().username()).isEqualTo(mine.buyer().username());
            assertThat(row.shippingAddressSnapshot()).isNotBlank();
            assertThat(row.buyerNote()).isEqualTo("Please use a top loader");
            assertThat(row.paidAt()).isNotNull();
            assertThat(Duration.between(row.paidAt(), row.shipBy()).toDays()).isPositive();
        });
    }

    @Test
    void filtersBySeveralStatusesSearchesAndCounts() {
        Seller seller = data.seller();
        Placed unpaid = place(seller, 1, "90.00");
        Placed toPack = paid(seller);

        assertThat(desk.list(seller.principal(), List.of("PAID,PREPARING"), null, null, 0, 20).items())
                .extracting(row -> row.order().id()).containsExactly(toPack.sellerOrderId());
        assertThat(desk.list(seller.principal(), List.of(), unpaid.buyer().username(), null, 0, 20).items())
                .extracting(row -> row.order().id()).containsExactly(unpaid.sellerOrderId());
        assertThat(desk.list(seller.principal(), List.of(), null, "TOTAL_DESC", 0, 20).items())
                .extracting(row -> row.order().id())
                .containsExactly(toPack.sellerOrderId(), unpaid.sellerOrderId());

        assertThat(desk.counts(seller.principal()))
                .containsEntry("PAID", 1L)
                .containsEntry("PENDING_PAYMENT", 1L)
                .doesNotContainKey("SHIPPED");
    }

    @Test
    void unknownStatusOrSortIsABadRequest() {
        Seller seller = data.seller();
        assertThatThrownBy(() -> desk.list(seller.principal(), List.of("PACKING"), null, null, 0, 20))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> desk.list(seller.principal(), List.of(), null, "CHEAPEST", 0, 20))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anotherShopsOrderIsNotFoundEverywhere() {
        Placed theirs = paid(data.seller());
        AuthPrincipal stranger = data.seller().principal();

        assertThatThrownBy(() -> desk.detail(stranger, theirs.sellerOrderId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> desk.accept(stranger, theirs.sellerOrderId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> desk.cancel(stranger, theirs.sellerOrderId(), "Not mine"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void detailCarriesCostAndHowOftenTheBuyerBoughtHere() {
        Seller seller = data.seller();
        Placed placed = paid(seller);

        Detail detail = desk.detail(seller.principal(), placed.sellerOrderId());

        assertThat(detail.costTotal()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(detail.buyerOrderCount()).isEqualTo(1);
        assertThat(detail.summary().order().status()).isEqualTo("PAID");
    }

    @Test
    void acceptMovesPaidToPreparingOnceAndRecordsIt() {
        Seller seller = data.seller();
        Placed placed = paid(seller);

        Detail accepted = desk.accept(seller.principal(), placed.sellerOrderId());

        assertThat(accepted.summary().order().status()).isEqualTo("PREPARING");
        assertThat(accepted.summary().order().acceptedAt()).isNotNull();
        SellerOrderStatusHistoryRecord history = latestHistory(placed.sellerOrderId());
        assertThat(history.getFromStatus()).isEqualTo("PAID");
        assertThat(history.getToStatus()).isEqualTo("PREPARING");
        assertThat(history.getChangedBy()).isEqualTo(seller.userId());

        assertThatThrownBy(() -> desk.accept(seller.principal(), placed.sellerOrderId()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION));
    }

    @Test
    void anUnpaidOrderCannotBeAccepted() {
        Seller seller = data.seller();
        Placed unpaid = place(seller, 1, "90.00");

        assertThatThrownBy(() -> desk.accept(seller.principal(), unpaid.sellerOrderId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anAcceptedOrderShipsAndComesBackWithTracking() {
        Seller seller = data.seller();
        Placed placed = paid(seller);
        desk.accept(seller.principal(), placed.sellerOrderId());

        Detail shipped = desk.ship(seller.principal(), placed.sellerOrderId(), shipment());

        assertThat(shipped.summary().order().status()).isEqualTo("SHIPPED");
        assertThat(shipped.summary().order().shipments())
                .singleElement()
                .satisfies(s -> assertThat(s.trackingNumber()).isEqualTo("KE1234567890"));
    }

    @Test
    void sellerCancelReleasesTheCardsAndKeepsTheReason() {
        Seller seller = data.seller();
        Placed placed = paid(seller);
        desk.accept(seller.principal(), placed.sellerOrderId());

        Detail cancelled = desk.cancel(seller.principal(), placed.sellerOrderId(), "  Card was damaged  ");

        assertThat(cancelled.summary().order().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.summary().order().cancelReason()).isEqualTo("Card was damaged");
        assertThat(dsl.select(LISTING.QUANTITY_AVAILABLE).from(LISTING)
                .where(LISTING.ID.eq(placed.listingId())).fetchSingle().value1()).isEqualTo(1);
        SellerOrderStatusHistoryRecord history = latestHistory(placed.sellerOrderId());
        assertThat(history.getFromStatus()).isEqualTo("PREPARING");
        assertThat(history.getToStatus()).isEqualTo("CANCELLED");
        assertThat(history.getNote()).contains("Card was damaged");
    }

    @Test
    void aShippedOrderCannotBeCancelledBySeller() {
        Seller seller = data.seller();
        Placed placed = paid(seller);
        desk.ship(seller.principal(), placed.sellerOrderId(), shipment());

        assertThatThrownBy(() -> desk.cancel(seller.principal(), placed.sellerOrderId(), "Too late"))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * Paid through the mock gateway, so there is escrow to give back and the refund
     * settles on its own with no admin — the path that once wrote processed_by = 0
     * and rolled the whole cancel back on the user_account foreign key.
     */
    @Test
    void sellerCancelOfAGatewayPaidOrderRefundsTheBuyer() {
        Seller seller = data.seller();
        Placed placed = place(seller, 1, "300.00");
        payments.payMock(placed.buyer(), placed.salesOrderId());

        Detail cancelled = desk.cancel(seller.principal(), placed.sellerOrderId(), "Out of stock");

        assertThat(cancelled.summary().order().status()).isEqualTo("CANCELLED");
        var refund = dsl.selectFrom(REFUND)
                .where(REFUND.SELLER_ORDER_ID.eq(placed.sellerOrderId()))
                .fetchSingle();
        assertThat(refund.getStatus()).isEqualTo("PROCESSED");
        assertThat(refund.getProcessedBy()).as("settled by the system, not a person").isNull();
        assertThat(refund.getReason()).isEqualTo("Out of stock");
    }
}
