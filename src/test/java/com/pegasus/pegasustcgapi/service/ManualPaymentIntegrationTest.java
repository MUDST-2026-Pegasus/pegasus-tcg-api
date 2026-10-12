package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.CommissionCharge.COMMISSION_CHARGE;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.RefundTransferAttempt.REFUND_TRANSFER_ATTEMPT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ManualPaymentIntegrationTest extends PostgresIntegrationTest {

    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired WalletLedgerService wallets;
    @Autowired OrderLifecycleService orders;
    @Autowired RefundService refunds;

    @Test
    void adminApprovalPostsExactlyOnePaymentAndHoldsEscrow() {
        var seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Pikachu", "AR");
        AuthPrincipal buyer = data.buyer();
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "checkout-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        String key = "payment-" + data.tag();

        var pending = payments.create(buyer, order.orderId(), "BANK_TRANSFER", key);
        BigDecimal pendingBefore = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(wallets.platformWalletId())).fetchSingle(WALLET.BALANCE_PENDING);
        assertThat(payments.create(buyer, order.orderId(), "BANK_TRANSFER", key).id()).isEqualTo(pending.id());
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT")
                .and(LEDGER_ENTRY.REFERENCE_ID.eq(pending.id())))).isZero();

        // The storage upload is tested by StorageService; this fixture represents a submitted slip.
        paymentRows.attachSlip(pending.id(), "payments/test-" + data.tag() + ".png", "BANK-123");
        var approved = payments.verify(data.admin().userId(), pending.id(), true, null);
        assertThat(approved.status()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> payments.verify(data.admin().userId(), pending.id(), true, null))
                .isInstanceOf(RuntimeException.class);
        assertThat(dsl.select(PAYMENT.STATUS).from(PAYMENT).where(PAYMENT.ID.eq(pending.id()))
                .fetchSingle(PAYMENT.STATUS)).isEqualTo("SUCCEEDED");
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(suborderId))
                .fetchSingle(SELLER_ORDER.STATUS)).isEqualTo("PAID");
        assertThat(dsl.select(ESCROW_HOLD.AMOUNT).from(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId))
                .fetchSingle(ESCROW_HOLD.AMOUNT)).isEqualByComparingTo(order.grandTotal());
        assertThat(dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT")
                .and(LEDGER_ENTRY.REFERENCE_ID.eq(pending.id())))).isEqualTo(1);
        BigDecimal pendingBalance = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(wallets.platformWalletId())).fetchSingle(WALLET.BALANCE_PENDING);
        assertThat(pendingBalance.subtract(pendingBefore)).isEqualByComparingTo(order.grandTotal());
        BigDecimal ledgerTotal = dsl.select(DSL.coalesce(DSL.sum(DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"),
                LEDGER_ENTRY.AMOUNT).otherwise(LEDGER_ENTRY.AMOUNT.neg())), BigDecimal.ZERO))
                .from(LEDGER_ENTRY).where(LEDGER_ENTRY.WALLET_ID.eq(wallets.platformWalletId()))
                .fetchSingle(0, BigDecimal.class);
        BigDecimal walletTotal = dsl.select(WALLET.BALANCE_AVAILABLE.add(WALLET.BALANCE_PENDING))
                .from(WALLET).where(WALLET.ID.eq(wallets.platformWalletId()))
                .fetchSingle(0, BigDecimal.class);
        assertThat(ledgerTotal).isEqualByComparingTo(walletTotal);
    }

    @Test
    void cancellationQueuesManualRefundAndDebitsOnlyAfterAdminConfirmsTransfer() {
        var seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Eevee", "AR");
        AuthPrincipal buyer = data.buyer();
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "80.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "checkout-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "payment-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/test-" + data.tag() + ".png", "BANK-123");
        long adminId = data.admin().userId();
        payments.verify(adminId, payment.id(), true, null);
        BigDecimal before = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(wallets.platformWalletId())).fetchSingle(WALLET.BALANCE_PENDING);

        orders.cancelBuyerOrder(buyer, order.orderId(), new CancelOrderRequest("Changed my mind"));
        var refund = dsl.selectFrom(REFUND).where(REFUND.SELLER_ORDER_ID.eq(suborderId)).fetchSingle();
        assertThat(refund.getStatus()).isEqualTo("APPROVED");
        assertThat(dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(wallets.platformWalletId()))
                .fetchSingle(WALLET.BALANCE_PENDING)).isEqualByComparingTo(before);

        refunds.setDestination(refund.getId(), buyer.userId(), "Test Bank", "Test Buyer", "1234567890");
        refunds.startTransfer(refund.getId(), adminId, "1234567890");
        assertThatThrownBy(() -> refunds.setDestination(refund.getId(), buyer.userId(),
                "Other Bank", "Test Buyer", "9876543210")).isInstanceOf(ConflictException.class);
        refunds.transferFailed(refund.getId(), adminId, "Bank rejected account");
        assertThat(dsl.select(REFUND_TRANSFER_ATTEMPT.STATUS).from(REFUND_TRANSFER_ATTEMPT)
                .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(refund.getId()))
                .fetchSingle(REFUND_TRANSFER_ATTEMPT.STATUS)).isEqualTo("FAILED");
        refunds.setDestination(refund.getId(), buyer.userId(), "Test Bank", "Test Buyer", "9876543210");
        refunds.startTransfer(refund.getId(), adminId, "9876543210");
        refunds.processed(refund.getId(), adminId, "REF-TEST");
        assertThat(dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(wallets.platformWalletId()))
                .fetchSingle(WALLET.BALANCE_PENDING)).isEqualByComparingTo(before.subtract(refund.getAmount()));
        assertThat(dsl.select(ESCROW_HOLD.STATUS).from(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId))
                .fetchSingle(ESCROW_HOLD.STATUS)).isEqualTo("CANCELLED");
        assertThat(refunds.processed(refund.getId(), adminId, "REF-TEST").getStatus()).isEqualTo("PROCESSED");
        assertThatThrownBy(() -> refunds.processed(refund.getId(), adminId, "DIFFERENT"))
                .isInstanceOf(ConflictException.class);
        assertThat(dsl.selectFrom(REFUND_TRANSFER_ATTEMPT)
                .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(refund.getId()))
                .orderBy(REFUND_TRANSFER_ATTEMPT.ID.asc()).fetch())
                .extracting(r -> r.getStatus()).containsExactly("FAILED", "PROCESSED");
    }

    @Test
    void simultaneousPaymentRequestsAndApprovalsNeverDoubleCredit() throws Exception {
        var seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Concurrent " + data.tag(), "AR");
        var buyer = data.buyer();
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "checkout-concurrent-" + data.tag(), null, null);
        String key = "payment-concurrent-" + data.tag();

        var pool = Executors.newFixedThreadPool(2);
        try {
            var createStart = new CountDownLatch(1);
            var first = pool.submit(() -> {
                createStart.await();
                return payments.create(buyer, order.orderId(), "BANK_TRANSFER", key).id();
            });
            var second = pool.submit(() -> {
                createStart.await();
                return payments.create(buyer, order.orderId(), "BANK_TRANSFER", key).id();
            });
            createStart.countDown();
            long paymentId = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(paymentId);
            paymentRows.attachSlip(paymentId, "payments/test-" + data.tag() + ".png", "BANK-789");
            long adminId = data.admin().userId();
            var verifyStart = new CountDownLatch(1);
            var verifyFirst = pool.submit(() -> {
                verifyStart.await();
                try {
                    payments.verify(adminId, paymentId, true, null);
                    return true;
                } catch (ConflictException expected) {
                    return false;
                }
            });
            var verifySecond = pool.submit(() -> {
                verifyStart.await();
                try {
                    payments.verify(adminId, paymentId, true, null);
                    return true;
                } catch (ConflictException expected) {
                    return false;
                }
            });
            verifyStart.countDown();
            assertThat((verifyFirst.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (verifySecond.get(30, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            assertThat(dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT")
                    .and(LEDGER_ENTRY.REFERENCE_ID.eq(paymentId)))).isEqualTo(1);
            assertThat(dsl.fetchCount(ESCROW_HOLD,
                    ESCROW_HOLD.SELLER_ORDER_ID.eq(order.sellerOrders().getFirst().id()))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void buyerConfirmationRacingAutoCompletionReleasesEscrowOnlyOnce() throws Exception {
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Escrow race " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "escrow-race-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "pay-race-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/race-" + data.tag() + ".png", "BANK-RACE");
        payments.verify(data.admin().userId(), payment.id(), true, null);
        orders.shipSellerOrder(seller.principal(), suborderId,
                new ShipOrderRequest("Thailand Post", "TRACK-RACE", "TH_POST", null, null));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var buyerTask = pool.submit(() -> {
                start.await();
                try {
                    orders.confirmBuyerOrderReceived(buyer, order.orderId());
                } catch (ConflictException expected) {
                    // The scheduler won the status transition.
                }
                return null;
            });
            var schedulerTask = pool.submit(() -> {
                start.await();
                orders.autoCompleteSellerOrder(suborderId);
                return null;
            });
            start.countDown();
            buyerTask.get(30, TimeUnit.SECONDS);
            schedulerTask.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        orders.autoCompleteSellerOrder(suborderId);
        assertThat(dsl.select(ESCROW_HOLD.STATUS).from(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId))
                .fetchSingle(ESCROW_HOLD.STATUS)).isEqualTo("RELEASED");
        assertThat(dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.ENTRY_TYPE.eq("ESCROW_RELEASE")
                .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborderId)))).isEqualTo(1);
        assertThat(dsl.fetchCount(COMMISSION_CHARGE,
                COMMISSION_CHARGE.SELLER_ORDER_ID.eq(suborderId))).isEqualTo(1);
    }
}
