package com.pegasus.pegasustcgapi.controller.order;

import static com.pegasus.pegasustcgapi.jooq.tables.InventoryMovement.INVENTORY_MOVEMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TCG-669: cancellation must update every shop atomically through the real HTTP/security stack. */
class UnpaidMultiShopCancellationApiIntegrationTest extends PostgresIntegrationTest {
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired PaymentService payments;

    private ApiClient api;
    private String ownerToken;
    private String strangerToken;
    private CheckoutResponse order;
    private List<Long> listingIds;
    private long paymentId;

    @BeforeEach
    void twoShopsWithAPendingPayment() {
        api = new ApiClient(port);
        var buyer = data.buyer();
        // JWT validation uses wall time; mint tokens before fixing the business clock.
        ownerToken = data.bearer(buyer);
        strangerToken = data.bearer(data.buyer());
        clock.setInstant(Instant.parse("2026-01-15T08:00:00Z"));
        short game = data.game("Pokemon");
        Listing first = data.onSale(data.seller(), data.card(game, "First", "C"), CardCondition.NM, 2, "100.00");
        Listing second = data.onSale(data.seller(), data.card(game, "Second", "C"), CardCondition.NM, 1, "40.00");
        listingIds = List.of(first.id(), second.id());
        cart.addItem(buyer, null, new CartItemRequest(first.id(), 2));
        cart.addItem(buyer, null, new CartItemRequest(second.id(), 1));
        order = checkout.checkout(buyer, "cancel-" + data.tag(), null, null);
        paymentId = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "pay-" + data.tag()).id();
        assertThat(order.sellerOrders()).hasSize(2);
        assertThat(stockStatuses()).containsOnly("RESERVED").hasSize(3);
    }

    @Test
    void cancellationReleasesBothShopsAndReplayAddsNoHistoryOrSale() {
        var cancelled = api.post(path(order.orderId()), ownerToken, Map.of("reason", "Changed my mind"));
        assertThat(cancelled.status()).as(cancelled.body().toString()).isEqualTo(200);
        assertThat(dsl.select(SALES_ORDER.STATUS).from(SALES_ORDER).where(SALES_ORDER.ID.eq(order.orderId()))
                .fetchSingle(SALES_ORDER.STATUS)).isEqualTo("CANCELLED");
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(order.orderId())).fetch(SELLER_ORDER.STATUS))
                .containsOnly("CANCELLED").hasSize(2);
        assertThat(stockStatuses()).containsOnly("LISTED").hasSize(3);
        assertThat(paymentStatus()).isEqualTo("CANCELLED");
        int history = cancellationHistory();
        assertThat(history).isEqualTo(2);

        var replay = api.post(path(order.orderId()), ownerToken, Map.of("reason", "Retry"));
        assertThat(replay.status()).as(replay.body().toString()).isEqualTo(200);
        assertThat(cancellationHistory()).isEqualTo(history);
        assertThat(stockStatuses()).containsOnly("LISTED").hasSize(3);
        assertThat(dsl.fetchCount(INVENTORY_MOVEMENT, INVENTORY_MOVEMENT.LISTING_ID.in(listingIds)
                .and(INVENTORY_MOVEMENT.MOVEMENT_TYPE.eq("SALE")))).isZero();
    }

    @Test
    void strangerGuestMissingOrderAndInvalidReasonCannotReleaseStock() {
        var stranger = api.post(path(order.orderId()), strangerToken, Map.of());
        assertThat(stranger.status()).isEqualTo(404);
        assertThat(stranger.errorCode()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(api.post(path(order.orderId()), null, Map.of()).status()).isEqualTo(401);
        var missing = api.post(path(Long.MAX_VALUE), ownerToken, Map.of());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.errorCode()).isEqualTo("ORDER_NOT_FOUND");
        var invalid = api.post(path(order.orderId()), ownerToken, Map.of("reason", "x".repeat(501)));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(stockStatuses()).containsOnly("RESERVED").hasSize(3);
        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(cancellationHistory()).isZero();
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(order.orderId())).fetch(SELLER_ORDER.STATUS))
                .containsOnly("PENDING_PAYMENT").hasSize(2);
    }

    private String path(long id) { return "/api/v1/orders/" + id + "/cancel"; }

    private List<String> stockStatuses() {
        return dsl.select(LISTING_UNIT.STATUS).from(LISTING_UNIT)
                .where(LISTING_UNIT.LISTING_ID.in(listingIds)).fetch(LISTING_UNIT.STATUS);
    }

    private String paymentStatus() {
        return dsl.select(PAYMENT.STATUS).from(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchSingle(PAYMENT.STATUS);
    }

    private int cancellationHistory() {
        return dsl.fetchCount(SELLER_ORDER_STATUS_HISTORY,
                SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.in(order.sellerOrders().stream().map(s -> s.id()).toList())
                        .and(SELLER_ORDER_STATUS_HISTORY.TO_STATUS.eq("CANCELLED")));
    }
}
