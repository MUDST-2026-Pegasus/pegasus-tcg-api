package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.CollectionItem.COLLECTION_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.jooq.impl.DSL;

class ReturnServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired OrderLifecycleService orders;
    @Autowired ReturnService returns;
    @Autowired RefundService refunds;

    @Test
    void rejectingAClaimResumesTheOriginalReleaseClock() {
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Rejected return " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "reject-return-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        long itemId = order.sellerOrders().getFirst().items().getFirst().id();
        payments.payMock(buyer, order.orderId());
        orders.shipSellerOrder(seller.principal(), suborderId,
                new ShipOrderRequest("Thailand Post", "REJECT-123", "TH_POST", null, null));
        var dueAt = dsl.select(SELLER_ORDER.AUTO_COMPLETE_AT).from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(suborderId)).fetchSingle(SELLER_ORDER.AUTO_COMPLETE_AT);

        var claim = returns.request(buyer.userId(), suborderId, "WRONG_ITEM",
                "Wrong card", List.of(), List.of(new ReturnService.Item(itemId, 1)));
        returns.reject(seller.userId(), claim.getId());

        var sellerOrder = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(suborderId)).fetchSingle();
        assertThat(sellerOrder.getStatus()).isEqualTo("SHIPPED");
        assertThat(sellerOrder.getAutoCompleteAt()).isEqualTo(dueAt);
        assertThat(dsl.select(ESCROW_HOLD.STATUS).from(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId))
                .fetchSingle(ESCROW_HOLD.STATUS)).isEqualTo("HELD");
    }

    @Test
    void partialRefundWaitsForAdminBeforeRemainingEscrowIsReleased() {
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Mewtwo", "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "return-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        long itemId = order.sellerOrders().getFirst().items().getFirst().id();
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "payment-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/test-" + data.tag() + ".png", "BANK-123");
        long adminId = data.admin().userId();
        payments.verify(adminId, payment.id(), true, null);
        orders.shipSellerOrder(seller.principal(), suborderId,
                new ShipOrderRequest("Thailand Post", "TEST-123", "TH_POST", null, null));

        dsl.update(SELLER_ORDER).set(SELLER_ORDER.AUTO_COMPLETE_AT, OffsetDateTime.now().minusMinutes(1))
                .where(SELLER_ORDER.ID.eq(suborderId)).execute();
        assertThatThrownBy(() -> returns.request(buyer.userId(), suborderId, "NOT_AS_DESCRIBED",
                "Too late", List.of(), List.of(new ReturnService.Item(itemId, 1))))
                .isInstanceOf(ConflictException.class);
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.AUTO_COMPLETE_AT, OffsetDateTime.now().plusDays(1))
                .where(SELLER_ORDER.ID.eq(suborderId)).execute();

        var request = returns.request(buyer.userId(), suborderId, "NOT_AS_DESCRIBED",
                "Damage", List.of(), List.of(new ReturnService.Item(itemId, 1)));
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(suborderId)).fetchSingle(SELLER_ORDER.STATUS))
                .isEqualTo("RETURN_REQUESTED");
        returns.approve(seller.userId(), request.getId(), "PARTIAL_REFUND",
                new BigDecimal("40.00"), false);
        assertThat(returns.buyerReturns(buyer.userId(), suborderId).getFirst().getStatus())
                .isEqualTo("APPROVED");
        var refund = dsl.selectFrom(REFUND).where(REFUND.RETURN_REQUEST_ID.eq(request.getId())).fetchSingle();
        assertThat(refund.getStatus()).isEqualTo("APPROVED");
        assertThat(dsl.select(ESCROW_HOLD.RELEASED_AMOUNT).from(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId))
                .fetchSingle(ESCROW_HOLD.RELEASED_AMOUNT)).isEqualByComparingTo("0.00");

        refunds.setDestination(refund.getId(), buyer.userId(), "Test Bank", "Test Buyer", "1234567890");
        refunds.startTransfer(refund.getId(), adminId, "1234567890");
        refunds.processed(refund.getId(), adminId, "REF-TEST");
        var hold = dsl.selectFrom(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId)).fetchSingle();
        assertThat(hold.getRefundedAmount()).isEqualByComparingTo("40.00");
        assertThat(hold.getReleasedAmount()).isEqualByComparingTo("60.00");
        assertThat(hold.getStatus()).isEqualTo("PARTIALLY_RELEASED");
    }

    @Test
    void receivedPartialReturnRestocksOnlyReturnedUnitAndGrantsOnlyKeptUnit() {
        BigDecimal startingDifference = walletTotal().subtract(externalNet());
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Returnable " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 2, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 2));
        var order = checkout.checkout(buyer, "return-units-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        long itemId = order.sellerOrders().getFirst().items().getFirst().id();
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "payment-units-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/test-" + data.tag() + ".png", "BANK-456");
        long adminId = data.admin().userId();
        payments.verify(adminId, payment.id(), true, null);
        orders.shipSellerOrder(seller.principal(), suborderId,
                new ShipOrderRequest("Thailand Post", "TEST-456", "TH_POST", null, null));

        var request = returns.request(buyer.userId(), suborderId, "NOT_AS_DESCRIBED",
                "One damaged card", List.of(), List.of(new ReturnService.Item(itemId, 1)));
        returns.approve(seller.userId(), request.getId(), "PARTIAL_REFUND",
                new BigDecimal("100.00"), true);
        returns.ship(buyer.userId(), suborderId, request.getId(), "RETURN-456");
        returns.receive(seller.userId(), request.getId(), true);
        var refund = dsl.selectFrom(REFUND).where(REFUND.RETURN_REQUEST_ID.eq(request.getId())).fetchSingle();
        refunds.setDestination(refund.getId(), buyer.userId(), "Test Bank", "Test Buyer", "1234567890");
        refunds.startTransfer(refund.getId(), adminId, "1234567890");
        refunds.processed(refund.getId(), adminId, "REF-TEST");

        assertThat(dsl.selectCount().from(LISTING_UNIT)
                .join(ORDER_ITEM_UNIT).on(ORDER_ITEM_UNIT.LISTING_UNIT_ID.eq(LISTING_UNIT.ID))
                .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(itemId))
                .and(LISTING_UNIT.STATUS.eq("RETURNED")).fetchSingle(0, int.class)).isEqualTo(1);
        assertThat(dsl.selectCount().from(COLLECTION_ITEM)
                .where(COLLECTION_ITEM.SOURCE_ORDER_ITEM_ID.eq(itemId))
                .fetchSingle(0, int.class)).isEqualTo(1);
        assertFinanceInvariants(startingDifference);
    }

    private void assertFinanceInvariants(BigDecimal startingDifference) {
        for (var wallet : dsl.selectFrom(WALLET).fetch()) {
            BigDecimal ledgerTotal = dsl.select(DSL.coalesce(DSL.sum(
                    DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"), LEDGER_ENTRY.AMOUNT)
                            .otherwise(LEDGER_ENTRY.AMOUNT.neg())), BigDecimal.ZERO))
                    .from(LEDGER_ENTRY).where(LEDGER_ENTRY.WALLET_ID.eq(wallet.getId()))
                    .fetchSingle(0, BigDecimal.class);
            assertThat(wallet.getBalanceAvailable().add(wallet.getBalancePending()))
                    .isEqualByComparingTo(ledgerTotal);
        }
        BigDecimal platformPending = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.KIND.eq("PLATFORM")).fetchSingle(WALLET.BALANCE_PENDING);
        BigDecimal held = dsl.select(DSL.coalesce(DSL.sum(ESCROW_HOLD.AMOUNT
                .subtract(ESCROW_HOLD.RELEASED_AMOUNT)
                .subtract(ESCROW_HOLD.REFUNDED_AMOUNT)), BigDecimal.ZERO))
                .from(ESCROW_HOLD).fetchSingle(0, BigDecimal.class);
        assertThat(platformPending).isEqualByComparingTo(held);
        assertThat(walletTotal().subtract(externalNet())).isEqualByComparingTo(startingDifference);
    }

    private BigDecimal externalNet() {
        return dsl.select(DSL.coalesce(DSL.sum(
                DSL.when(LEDGER_ENTRY.ENTRY_TYPE.eq("PAYMENT_IN"), LEDGER_ENTRY.AMOUNT)
                        .when(LEDGER_ENTRY.ENTRY_TYPE.in("REFUND_OUT", "PAYOUT_OUT"),
                                LEDGER_ENTRY.AMOUNT.neg())
                        .otherwise(BigDecimal.ZERO)), BigDecimal.ZERO))
                .from(LEDGER_ENTRY).fetchSingle(0, BigDecimal.class);
    }

    private BigDecimal walletTotal() {
        return dsl.select(DSL.coalesce(DSL.sum(WALLET.BALANCE_AVAILABLE
                .add(WALLET.BALANCE_PENDING)), BigDecimal.ZERO))
                .from(WALLET).fetchSingle(0, BigDecimal.class);
    }
}
