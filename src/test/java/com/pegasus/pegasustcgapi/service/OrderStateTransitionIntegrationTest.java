package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CollectionItem.COLLECTION_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static com.pegasus.pegasustcgapi.jooq.tables.Shipment.SHIPMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.job.OrderAutoReleaseScheduler;
import com.pegasus.pegasustcgapi.job.PendingPaymentTimeoutScheduler;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * The seller-order state machine [CR-7 US-40..49], against the real schema.
 *
 * <p>The thirteen cases ST-01..ST-13 come from the testing strategy. They are first
 * checked against {@link SellerOrderStatus#canMoveTo}, the one table the service's
 * compare-and-set guards are built from, and then driven through the service
 * wherever the code offers a way to make that move. Where the code differs from
 * the strategy document the test follows the code and says so:
 * <ul>
 *   <li>an illegal move is 409 ORDER_STATUS_TRANSITION, not 400;</li>
 *   <li>PAID→PREPARING and SHIPPED→DELIVERED are legal but no endpoint makes them;</li>
 *   <li>RETURN_REQUESTED, RETURNED and REFUNDED belong to after-sales, which this
 *       module does not drive, so ST-07 and ST-08 are not moves it allows.</li>
 * </ul>
 * States with no endpoint leading to them are set up by writing the row directly.
 */
@DisplayName("Order state transitions (integration) — ST-01..ST-13, payment timeout, escrow")
class OrderStateTransitionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderLifecycleService orders;

    @Autowired
    private PendingPaymentTimeoutScheduler paymentTimeout;

    @Autowired
    private OrderAutoReleaseScheduler escrowAutoRelease;

    /** One buyer's order for one seller, with {@code quantity} of the listing's cards reserved. */
    private record PlacedOrder(Seller seller, AuthPrincipal buyer, long salesOrderId, long sellerOrderId,
            long listingId, OffsetDateTime placedAt) {
    }

    private PlacedOrder place(int stock, int quantity) {
        Seller seller = data.seller();
        Card card = data.card(data.game("Pokemon"), "Pikachu", "C");
        Listing listing = data.onSale(seller, card, CardCondition.NM, stock, "175.50");
        AuthPrincipal buyer = data.buyer();
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), quantity));

        CheckoutResponse order = checkoutService.checkout(buyer, "key-" + data.tag(), null, null);
        OffsetDateTime placedAt = dsl.select(SALES_ORDER.PLACED_AT).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(order.orderId())).fetchSingle().value1();
        return new PlacedOrder(seller, buyer, order.orderId(), order.sellerOrders().getFirst().id(),
                listing.id(), placedAt);
    }

    private PlacedOrder place() {
        return place(1, 1);
    }

    private PlacedOrder paid() {
        PlacedOrder order = place();
        orders.markOrderPaid(order.buyer(), order.salesOrderId());
        return order;
    }

    private PlacedOrder shipped() {
        PlacedOrder order = paid();
        orders.shipSellerOrder(order.seller().principal(), order.sellerOrderId(), shipment());
        return order;
    }

    private static ShipOrderRequest shipment() {
        return new ShipOrderRequest("Kerry Express", "KE1234567890", "KERRY", "shipments/proof.jpg",
                LocalDate.now().plusDays(2));
    }

    /** For states no endpoint leads to: DELIVERED, PREPARING and the after-sales ones. */
    private void force(PlacedOrder order, SellerOrderStatus status) {
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.STATUS, status.name())
                .where(SELLER_ORDER.ID.eq(order.sellerOrderId())).execute();
    }

    private SellerOrderRecord sellerOrder(PlacedOrder order) {
        return dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(order.sellerOrderId())).fetchSingle();
    }

    private SalesOrderRecord salesOrder(PlacedOrder order) {
        return dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(order.salesOrderId())).fetchSingle();
    }

    private List<SellerOrderStatusHistoryRecord> history(PlacedOrder order) {
        return dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(order.sellerOrderId()))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.ID)
                .fetch();
    }

    private int unitsIn(PlacedOrder order, ListingUnitStatus status) {
        return dsl.fetchCount(LISTING_UNIT,
                LISTING_UNIT.LISTING_ID.eq(order.listingId()).and(LISTING_UNIT.STATUS.eq(status.name())));
    }

    private static void assertRejectedAsIllegalMove(ThrowingCallable move) {
        assertThatThrownBy(move).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION);
            assertThat(e.errorCode().status()).isEqualTo(HttpStatus.CONFLICT);
        });
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}: {1} -> {2} allowed = {3}")
    @CsvSource({
            "ST-01, PENDING_PAYMENT,  PAID,             true",
            "ST-02, PENDING_PAYMENT,  CANCELLED,        true",
            "ST-03, PAID,             PREPARING,        true",
            "ST-04, PREPARING,        SHIPPED,          true",
            "ST-05, SHIPPED,          DELIVERED,        true",
            "ST-06, DELIVERED,        COMPLETED,        true",
            // The strategy document lists ST-07 and ST-08 as valid; the code leaves
            // after-sales statuses to another module and allows neither move.
            "ST-07, DELIVERED,        RETURN_REQUESTED, false",
            "ST-08, RETURN_REQUESTED, REFUNDED,         false",
            "ST-09, COMPLETED,        PENDING_PAYMENT,  false",
            "ST-10, CANCELLED,        PAID,             false",
            "ST-11, PENDING_PAYMENT,  SHIPPED,          false",
            "ST-12, DELIVERED,        PREPARING,        false",
            "ST-13, REFUNDED,         COMPLETED,        false"
    })
    void transitionTable(String id, SellerOrderStatus from, SellerOrderStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).as(id).isEqualTo(allowed);
    }

    // ------------------------------------------------------------------
    // Valid moves, through the service
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Valid moves")
    class ValidMoves {

        @Test
        @DisplayName("ST-01 PENDING_PAYMENT -> PAID: paid_at stamped, money held (escrow not released)")
        void st01_payment() {
            PlacedOrder order = place();

            orders.markOrderPaid(order.buyer(), order.salesOrderId());

            assertThat(sellerOrder(order).getStatus()).isEqualTo("PAID");
            assertThat(salesOrder(order).getStatus()).isEqualTo("PAID");
            assertThat(salesOrder(order).getPaidAt()).isNotNull();
            assertThat(history(order)).last().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo("PENDING_PAYMENT");
                assertThat(h.getToStatus()).isEqualTo("PAID");
                assertThat(h.getChangedBy()).isEqualTo(order.buyer().userId());
            });
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).as("cards stay held").isEqualTo(1);
            verify(ledgerPort, never()).release(anyLong());
        }

        @Test
        @DisplayName("ST-02 PENDING_PAYMENT -> CANCELLED (buyer path; the timeout path is under Payment timeout)")
        void st02_buyerCancelsUnpaidOrder() {
            PlacedOrder order = place();

            orders.cancelBuyerOrder(order.buyer(), order.salesOrderId(), new CancelOrderRequest("Changed my mind"));

            assertThat(sellerOrder(order).getStatus()).isEqualTo("CANCELLED");
            assertThat(sellerOrder(order).getCancelReason()).isEqualTo("Changed my mind");
            assertThat(unitsIn(order, ListingUnitStatus.LISTED)).isEqualTo(1);
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).isZero();
        }

        @Test
        @DisplayName("ST-03 PAID -> PREPARING is legal, and a PREPARING order can still be cancelled")
        void st03_preparing() {
            PlacedOrder order = paid();
            force(order, SellerOrderStatus.PREPARING);

            orders.cancelBuyerOrder(order.buyer(), order.salesOrderId(), null);

            assertThat(sellerOrder(order).getStatus()).isEqualTo("CANCELLED");
            assertThat(unitsIn(order, ListingUnitStatus.LISTED)).isEqualTo(1);
            verify(ledgerPort, never()).release(anyLong());
        }

        @Test
        @DisplayName("ST-04 PREPARING -> SHIPPED: tracking recorded, cards SOLD, auto-complete set 7 days out")
        void st04_shipping() {
            PlacedOrder order = paid();
            force(order, SellerOrderStatus.PREPARING);

            orders.shipSellerOrder(order.seller().principal(), order.sellerOrderId(), shipment());

            SellerOrderRecord shipped = sellerOrder(order);
            assertThat(shipped.getStatus()).isEqualTo("SHIPPED");
            assertThat(shipped.getShippedAt()).isNotNull();
            assertThat(Duration.between(shipped.getShippedAt(), shipped.getAutoCompleteAt()))
                    .isEqualTo(Duration.ofDays(7));
            assertThat(dsl.fetchSingle(SHIPMENT, SHIPMENT.SELLER_ORDER_ID.eq(order.sellerOrderId()))
                    .getTrackingNumber()).isEqualTo("KE1234567890");
            assertThat(unitsIn(order, ListingUnitStatus.SOLD)).isEqualTo(1);
            assertThat(history(order)).last().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo("PREPARING");
                assertThat(h.getToStatus()).isEqualTo("SHIPPED");
                assertThat(h.getChangedBy()).isEqualTo(order.seller().userId());
            });
            verify(ledgerPort, never()).release(anyLong());
        }

        @Test
        @DisplayName("PAID -> SHIPPED directly is also allowed (seller skips PREPARING)")
        void paidCanShipDirectly() {
            PlacedOrder order = shipped();

            assertThat(sellerOrder(order).getStatus()).isEqualTo("SHIPPED");
        }

        @Test
        @DisplayName("ST-06 DELIVERED -> COMPLETED on buyer confirmation: escrow released once, card in the collection")
        void st06_buyerConfirmsReceipt() {
            PlacedOrder order = shipped();
            force(order, SellerOrderStatus.DELIVERED);

            orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId());

            assertThat(sellerOrder(order).getStatus()).isEqualTo("COMPLETED");
            assertThat(sellerOrder(order).getCompletedAt()).isNotNull();
            assertThat(salesOrder(order).getStatus()).isEqualTo("COMPLETED");
            assertThat(history(order)).last().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo("DELIVERED");
                assertThat(h.getToStatus()).isEqualTo("COMPLETED");
                assertThat(h.getChangedBy()).isEqualTo(order.buyer().userId());
            });
            verify(ledgerPort, times(1)).release(order.sellerOrderId());
            assertThat(dsl.fetchCount(COLLECTION_ITEM, COLLECTION_ITEM.USER_ID.eq(order.buyer().userId())))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("SHIPPED -> COMPLETED on confirmation also works (delivery is never recorded today)")
        void shippedOrderCanBeConfirmed() {
            PlacedOrder order = shipped();

            orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId());

            assertThat(sellerOrder(order).getStatus()).isEqualTo("COMPLETED");
            verify(ledgerPort, times(1)).release(order.sellerOrderId());
        }
    }

    // ------------------------------------------------------------------
    // Invalid moves, through the service
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Invalid moves are refused with 409 and change nothing")
    class InvalidMoves {

        @Test
        @DisplayName("ST-09 COMPLETED -> PENDING_PAYMENT: paying again for a finished order is refused")
        void st09_completedOrderCannotBeReopened() {
            PlacedOrder order = shipped();
            orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId());
            int historyBefore = history(order).size();

            assertRejectedAsIllegalMove(() -> orders.markOrderPaid(order.buyer(), order.salesOrderId()));

            assertThat(sellerOrder(order).getStatus()).isEqualTo("COMPLETED");
            assertThat(history(order)).hasSize(historyBefore);
        }

        @Test
        @DisplayName("ST-10 CANCELLED -> PAID: a cancelled order cannot take money")
        void st10_cancelledOrderCannotBePaid() {
            PlacedOrder order = place();
            orders.cancelBuyerOrder(order.buyer(), order.salesOrderId(), null);

            assertRejectedAsIllegalMove(() -> orders.markOrderPaid(order.buyer(), order.salesOrderId()));

            assertThat(sellerOrder(order).getStatus()).isEqualTo("CANCELLED");
            assertThat(salesOrder(order).getPaidAt()).isNull();
        }

        @Test
        @DisplayName("ST-11 PENDING_PAYMENT -> SHIPPED: an unpaid order cannot be posted")
        void st11_unpaidOrderCannotShip() {
            PlacedOrder order = place();

            assertRejectedAsIllegalMove(() ->
                    orders.shipSellerOrder(order.seller().principal(), order.sellerOrderId(), shipment()));

            assertThat(sellerOrder(order).getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(dsl.fetchCount(SHIPMENT, SHIPMENT.SELLER_ORDER_ID.eq(order.sellerOrderId()))).isZero();
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).as("not sold").isEqualTo(1);
        }

        @Test
        @DisplayName("ST-12 DELIVERED -> PREPARING: a delivered order cannot be cancelled back into the seller's hands")
        void st12_deliveredOrderCannotGoBack() {
            PlacedOrder order = shipped();
            force(order, SellerOrderStatus.DELIVERED);

            assertRejectedAsIllegalMove(() -> orders.cancelBuyerOrder(order.buyer(), order.salesOrderId(), null));
            assertRejectedAsIllegalMove(() ->
                    orders.shipSellerOrder(order.seller().principal(), order.sellerOrderId(), shipment()));

            assertThat(sellerOrder(order).getStatus()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("ST-13 REFUNDED -> COMPLETED: a refunded order is final and releases no escrow")
        void st13_refundedOrderIsFinal() {
            PlacedOrder order = shipped();
            force(order, SellerOrderStatus.REFUNDED);

            assertRejectedAsIllegalMove(() -> orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId()));

            assertThat(sellerOrder(order).getStatus()).isEqualTo("REFUNDED");
            verify(ledgerPort, never()).release(anyLong());
        }

        @Test
        @DisplayName("ST-07/ST-08: no after-sales status has any onward move in this module")
        void afterSalesStatusesAreNotDrivenHere() {
            assertThat(SellerOrderStatus.DELIVERED.targets()).doesNotContain(SellerOrderStatus.RETURN_REQUESTED);
            assertThat(SellerOrderStatus.RETURN_REQUESTED.targets()).isEmpty();
            assertThat(SellerOrderStatus.RETURNED.targets()).isEmpty();
            assertThat(SellerOrderStatus.REFUNDED.terminal()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // CR-7 US-45: PromptPay payment timeout and automatic stock release
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Payment timeout (5 minutes) and automatic stock release")
    class PaymentTimeout {

        private void givenAFiveMinuteTimeout() {
            data.setting(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES, "5");
        }

        @Test
        @DisplayName("testTimeoutAutoRelease: past 5 minutes the order is CANCELLED, every card LISTED again, audited as the system")
        void testTimeoutAutoRelease() {
            givenAFiveMinuteTimeout();
            PlacedOrder order = place(3, 2);

            // The sweep compares placed_at with the clock, so the clock is set from the
            // stored placed_at rather than from "now": no dependence on how long checkout took.
            clock.setInstant(order.placedAt().plusMinutes(5).plusSeconds(1).toInstant());
            paymentTimeout.processExpiredOrders();

            SellerOrderRecord cancelled = sellerOrder(order);
            assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
            assertThat(cancelled.getCancelledAt()).isNotNull();
            assertThat(cancelled.getCancelReason()).contains("payment was not received in time");
            assertThat(salesOrder(order).getStatus()).isEqualTo("CANCELLED");

            // Stock: both held cards are back, and the counters agree with the cards.
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).isZero();
            assertThat(unitsIn(order, ListingUnitStatus.LISTED)).isEqualTo(3);
            ListingRecord listing = dsl.selectFrom(LISTING).where(LISTING.ID.eq(order.listingId())).fetchSingle();
            assertThat(listing.getQuantityAvailable()).isEqualTo(3);
            assertThat(listing.getQuantityReserved()).isZero();

            // Audit: seller_order_status_history, changed_by NULL = the system did it.
            assertThat(history(order)).last().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo("PENDING_PAYMENT");
                assertThat(h.getToStatus()).isEqualTo("CANCELLED");
                assertThat(h.getChangedBy()).isNull();
            });
        }

        @Test
        @DisplayName("at 4:59 the order is still waiting and its cards are still held")
        void notYetExpired() {
            givenAFiveMinuteTimeout();
            PlacedOrder order = place();

            clock.setInstant(order.placedAt().plusMinutes(4).plusSeconds(59).toInstant());
            paymentTimeout.processExpiredOrders();

            assertThat(sellerOrder(order).getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).isEqualTo(1);
        }

        @Test
        @DisplayName("a paid order older than the timeout is left alone")
        void paidOrderIsNotExpired() {
            givenAFiveMinuteTimeout();
            PlacedOrder order = paid();

            clock.setInstant(order.placedAt().plusMinutes(30).toInstant());
            paymentTimeout.processExpiredOrders();

            assertThat(sellerOrder(order).getStatus()).isEqualTo("PAID");
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).isEqualTo(1);
        }

        @Test
        @DisplayName("running the sweep twice cancels once and writes one audit row")
        void sweepIsIdempotent() {
            givenAFiveMinuteTimeout();
            PlacedOrder order = place();
            clock.setInstant(order.placedAt().plusMinutes(6).toInstant());

            paymentTimeout.processExpiredOrders();
            paymentTimeout.processExpiredOrders();

            assertThat(history(order)).filteredOn(h -> "CANCELLED".equals(h.getToStatus())).hasSize(1);
            assertThat(unitsIn(order, ListingUnitStatus.LISTED)).isEqualTo(1);
        }

        @Test
        @DisplayName("atomic: if releasing the cards fails, the cancellation rolls back too and the next sweep retries")
        void cancellationAndStockReleaseAreAtomic() {
            givenAFiveMinuteTimeout();
            PlacedOrder order = place();
            clock.setInstant(order.placedAt().plusMinutes(6).toInstant());
            doThrow(new IllegalStateException("simulated inventory outage"))
                    .when(inventoryPort).release(anyCollection());

            paymentTimeout.processExpiredOrders();

            assertThat(sellerOrder(order).getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).isEqualTo(1);
            assertThat(history(order)).noneMatch(h -> "CANCELLED".equals(h.getToStatus()));

            reset(inventoryPort);
            paymentTimeout.processExpiredOrders();

            assertThat(sellerOrder(order).getStatus()).isEqualTo("CANCELLED");
            assertThat(unitsIn(order, ListingUnitStatus.LISTED)).isEqualTo(1);
        }

        @Test
        @DisplayName("a released card can be bought by the next buyer straight away")
        void releasedCardIsPurchasableAgain() {
            givenAFiveMinuteTimeout();
            PlacedOrder order = place();
            clock.setInstant(order.placedAt().plusMinutes(6).toInstant());
            paymentTimeout.processExpiredOrders();
            clock.reset();

            AuthPrincipal nextBuyer = data.buyer();
            cartService.addItem(nextBuyer, null, new CartItemRequest(order.listingId(), 1));
            CheckoutResponse second = checkoutService.checkout(nextBuyer, "key-" + data.tag(), null, null);

            assertThat(second.status()).isEqualTo("PENDING_PAYMENT");
            assertThat(unitsIn(order, ListingUnitStatus.RESERVED)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // CR-6/CR-7 US-46, 47: escrow holding and payout release
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Escrow: the seller's money is held until the buyer confirms")
    class Escrow {

        @Test
        @DisplayName("testEscrowRelease: nothing is released while PAID or SHIPPED; confirming releases exactly once")
        void testEscrowRelease() {
            PlacedOrder order = paid();
            verify(ledgerPort, never()).release(anyLong());

            orders.shipSellerOrder(order.seller().principal(), order.sellerOrderId(), shipment());
            verify(ledgerPort, never()).release(anyLong());

            orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId());
            verify(ledgerPort, times(1)).release(order.sellerOrderId());

            // A second confirmation is an illegal move, not a second payout.
            assertRejectedAsIllegalMove(() -> orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId()));
            verify(ledgerPort, times(1)).release(order.sellerOrderId());
        }

        @Test
        @DisplayName("an unpaid order cannot be confirmed, so escrow cannot be released early")
        void unpaidOrderCannotBeConfirmed() {
            PlacedOrder order = place();

            assertRejectedAsIllegalMove(() -> orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId()));
            verify(ledgerPort, never()).release(anyLong());
        }

        @Test
        @DisplayName("a paid order cancelled before shipping never releases escrow to the seller")
        void cancelledPaidOrderReleasesNothing() {
            PlacedOrder order = paid();

            orders.cancelBuyerOrder(order.buyer(), order.salesOrderId(), null);

            assertThat(sellerOrder(order).getStatus()).isEqualTo("CANCELLED");
            verify(ledgerPort, never()).release(anyLong());
        }

        @Test
        @DisplayName("seller net = grand total - commission is fixed at checkout and survives to completion")
        void sellerNetIsStableThroughTheLifecycle() {
            data.setting(PlatformSettingService.COMMISSION_DEFAULT_RATE, "3");
            PlacedOrder order = shipped();

            orders.confirmBuyerOrderReceived(order.buyer(), order.salesOrderId());

            SellerOrderRecord done = sellerOrder(order);
            assertThat(done.getCommissionAmount()).isEqualByComparingTo("5.27");
            assertThat(done.getSellerNetAmount()).isEqualByComparingTo("170.23");
        }

        @Test
        @DisplayName("if the buyer never confirms, the sweep completes the order after the escrow window, as the system")
        void autoReleaseAfterEscrowWindow() {
            PlacedOrder order = shipped();
            OffsetDateTime autoCompleteAt = sellerOrder(order).getAutoCompleteAt();

            clock.setInstant(autoCompleteAt.minusMinutes(1).toInstant());
            escrowAutoRelease.processAutoReleases();
            assertThat(sellerOrder(order).getStatus()).isEqualTo("SHIPPED");
            // The sweep also completes older shipments left by other tests, so only this
            // order's payout is counted.
            verify(ledgerPort, never()).release(order.sellerOrderId());

            clock.setInstant(autoCompleteAt.plusMinutes(1).toInstant());
            escrowAutoRelease.processAutoReleases();

            assertThat(sellerOrder(order).getStatus()).isEqualTo("COMPLETED");
            verify(ledgerPort, times(1)).release(order.sellerOrderId());
            assertThat(history(order)).last().satisfies(h -> assertThat(h.getChangedBy()).isNull());
        }
    }
}
