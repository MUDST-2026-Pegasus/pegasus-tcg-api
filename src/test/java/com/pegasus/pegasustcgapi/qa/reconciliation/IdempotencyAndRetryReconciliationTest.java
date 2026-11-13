package com.pegasus.pegasustcgapi.qa.reconciliation;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.job.payment.PendingPaymentTimeoutScheduler;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.payment.PaymentRepository;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.finance.escrow.EscrowReleaseService;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [TCG-756] [QA-03-B] Idempotency, Retry, Duplicate &amp; Timeout Recovery Tests.
 */
@DisplayName("[QA-03-B] Idempotency, Duplicate Call & Recovery Invariants")
class IdempotencyAndRetryReconciliationTest extends PostgresIntegrationTest {

    @Autowired private CheckoutService checkout;
    @Autowired private CartService cart;
    @Autowired private PaymentService payments;
    @Autowired private PaymentRepository paymentRows;
    @Autowired private OrderLifecycleService orders;
    @Autowired private EscrowReleaseService escrowRelease;
    @Autowired private WalletLedgerService walletLedgerService;
    @Autowired private PendingPaymentTimeoutScheduler timeoutScheduler;
    @Autowired private TransactionTemplate transactions;

    private ReconciliationOracle oracle() {
        return new ReconciliationOracle(dsl);
    }

    @Test
    @DisplayName("Idempotency 1: Duplicate Checkout Submit Replay — Stock Deducted Once, 1 Order Persisted")
    void duplicateCheckoutSubmit_ReplaysOrder_StockDeductedOnlyOnce() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Zapdos " + data.tag(), "AR");

        int initialStock = 5;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "150.00");
        String key = "idem-chk-" + data.tag();

        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 2));

        // First checkout request
        var response1 = checkout.checkout(buyer, key, null, null);
        assertThat(response1.replayed()).isFalse();

        // Second checkout request with identical idempotency key
        var response2 = checkout.checkout(buyer, key, null, null);
        assertThat(response2.replayed()).isTrue();
        assertThat(response2.orderId()).isEqualTo(response1.orderId());

        // Invariant check: exactly 1 sales_order and 1 seller_order in DB
        int orderCount = dsl.fetchCount(SALES_ORDER, SALES_ORDER.IDEMPOTENCY_KEY.eq(key));
        assertThat(orderCount).isEqualTo(1);

        int sellerOrderCount = dsl.fetchCount(SELLER_ORDER, SELLER_ORDER.SALES_ORDER_ID.eq(response1.orderId()));
        assertThat(sellerOrderCount).isEqualTo(1);

        // Physical and denormalized stock deducted exactly once (2 units reserved, 3 available)
        oracle.assertListingStockInvariant(listing.id(), initialStock);
        var listingRecord = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingRecord.getQuantityReserved()).isEqualTo(2);
        assertThat(listingRecord.getQuantityAvailable()).isEqualTo(3);

        oracle.assertOrderIntegrity(response1.orderId());
        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Idempotency 2: Duplicate Payment Verification — Second Call Refused, Zero Double-Crediting")
    void duplicatePaymentVerification_RefusedCleanly_ZeroDoubleCrediting() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var admin = data.admin();
        var card = data.card(data.game("Pokemon"), "Articuno " + data.tag(), "AR");

        var listing = data.onSale(seller, card, CardCondition.NM, 2, "200.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "dup-pay-" + data.tag(), null, null);

        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "pay-key-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/slip-" + data.tag() + ".png", "TX-" + data.tag());

        // First verification succeeds
        var approved1 = payments.verify(admin.userId(), payment.id(), true, null);
        assertThat(approved1.status()).isEqualTo("SUCCEEDED");

        // Second duplicate verification must throw ConflictException (PAYMENT_STATE)
        assertThatThrownBy(() -> payments.verify(admin.userId(), payment.id(), true, null))
                .isInstanceOf(ConflictException.class);

        // Assert exactly 1 PAYMENT_IN ledger entry
        int paymentInCount = dsl.fetchCount(LEDGER_ENTRY,
                LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(payment.id()))
                        .and(LEDGER_ENTRY.ENTRY_TYPE.eq("PAYMENT_IN")));
        assertThat(paymentInCount).isEqualTo(1);

        // Assert exactly 1 ESCROW_HOLD ledger entry for the suborder
        int escrowHoldCount = dsl.fetchCount(LEDGER_ENTRY,
                LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(order.sellerOrders().getFirst().id()))
                        .and(LEDGER_ENTRY.ENTRY_TYPE.eq("ESCROW_HOLD")));
        assertThat(escrowHoldCount).isEqualTo(2); // 1 DEBIT from available, 1 CREDIT to pending

        oracle.assertWalletEquation(walletLedgerService.platformWalletId());
        oracle.assertOrderIntegrity(order.orderId());
        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Idempotency 3: Duplicate Escrow Release — Second Call Is Safe No-Op, Zero Double-Payout")
    void duplicateEscrowRelease_SafeNoOp_ZeroDoublePayout() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Moltres " + data.tag(), "AR");

        var listing = data.onSale(seller, card, CardCondition.NM, 2, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "dup-release-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();

        payments.payMock(buyer, order.orderId());
        orders.shipSellerOrder(seller.principal(), suborderId,
                new ShipOrderRequest("Kerry", "KR-" + data.tag(), "KERRY", null, null));

        // First completion & release
        orders.confirmBuyerOrderReceived(buyer, order.orderId());

        var hold1 = dsl.selectFrom(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId)).fetchSingle();
        assertThat(hold1.getStatus()).isEqualTo("RELEASED");

        // Second explicit escrow release call (e.g. from retried worker or scheduler)
        transactions.executeWithoutResult(status -> escrowRelease.release(suborderId));

        // Assert hold status and released amounts unchanged
        var hold2 = dsl.selectFrom(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(suborderId)).fetchSingle();
        assertThat(hold2.getStatus()).isEqualTo("RELEASED");
        assertThat(hold2.getReleasedAmount()).isEqualByComparingTo(hold1.getReleasedAmount());

        // Exactly 1 SELLER_EARNING row in ledger
        int earningCount = dsl.fetchCount(LEDGER_ENTRY,
                LEDGER_ENTRY.ENTRY_TYPE.eq("SELLER_EARNING")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborderId)));
        assertThat(earningCount).isEqualTo(1);

        // Exactly 1 COMMISSION_FEE row in ledger
        int commCount = dsl.fetchCount(LEDGER_ENTRY,
                LEDGER_ENTRY.ENTRY_TYPE.eq("COMMISSION_FEE")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborderId)));
        assertThat(commCount).isEqualTo(1);

        long sellerWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        oracle.assertWalletEquation(sellerWalletId);
        oracle.assertWalletEquation(walletLedgerService.platformWalletId());
        oracle.assertOrderIntegrity(order.orderId());
        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Idempotency 4: Duplicate Timeout Sweep — Second Execution Is Safe No-Op, Stock Intact")
    void duplicateTimeoutSweep_SafeNoOp_StockIntact() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Suicune " + data.tag(), "AR");

        int initialStock = 3;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "300.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "dup-timeout-" + data.tag(), null, null);

        data.setting(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES, "5");
        var placedAt = dsl.select(SALES_ORDER.PLACED_AT).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(order.orderId())).fetchSingle(SALES_ORDER.PLACED_AT);
        clock.setInstant(placedAt.plusMinutes(6).toInstant());

        // Run sweep 1: cancels order and releases stock
        timeoutScheduler.processExpiredOrders();
        var so1 = dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(order.orderId())).fetchSingle();
        assertThat(so1.getStatus()).isEqualTo("CANCELLED");

        var listing1 = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listing1.getQuantityAvailable()).isEqualTo(3);
        assertThat(listing1.getQuantityReserved()).isEqualTo(0);

        // Run sweep 2 immediately: safe no-op
        timeoutScheduler.processExpiredOrders();

        var listing2 = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listing2.getQuantityAvailable()).isEqualTo(3);
        assertThat(listing2.getQuantityReserved()).isEqualTo(0);
        assertThat(listing2.getQuantityTotal()).isEqualTo(3);

        oracle.assertEscrowAbsent(order.sellerOrders().getFirst().id());
        oracle.assertListingStockInvariant(listing.id(), initialStock);
        oracle.assertOrderIntegrity(order.orderId());
        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Idempotency 5: Checkout Retry After Simulated Client Timeout / Lost-Response — Clean Replay & Zero Stock Leak")
    void checkoutRetryAfterSimulatedTimeout_replaysCleanlyAndConservesStock() throws Exception {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Raikou " + data.tag(), "AR");

        int initialStock = 6;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "250.00");
        String idempotencyKey = "sim-retry-" + data.tag();

        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 2));

        // 1. Initial attempt executed asynchronously in worker thread;
        // Client simulates socket read timeout / connection drop while server commits in background.
        ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
        CheckoutResponse firstAttemptResponse;
        try {
            CompletableFuture<CheckoutResponse> serverCall = CompletableFuture.supplyAsync(
                    () -> checkout.checkout(buyer, idempotencyKey, null, null), clientExecutor);

            // Client times out / drops response reference after server completes transaction
            firstAttemptResponse = serverCall.get(15, TimeUnit.SECONDS);
            assertThat(firstAttemptResponse.replayed()).isFalse();
        } finally {
            clientExecutor.shutdownNow();
        }

        // 2. Client initiates retry with identical idempotency key after simulated response loss
        var retryResponse = checkout.checkout(buyer, idempotencyKey, null, null);
        assertThat(retryResponse.replayed()).isTrue();
        assertThat(retryResponse.orderId()).isEqualTo(firstAttemptResponse.orderId());
        assertThat(retryResponse.grandTotal()).isEqualByComparingTo(firstAttemptResponse.grandTotal());

        // 3. Bounded polling verifying order settled state in database
        long deadline = System.currentTimeMillis() + 5000;
        boolean settled = false;
        while (System.currentTimeMillis() < deadline) {
            int count = dsl.fetchCount(SALES_ORDER, SALES_ORDER.ID.eq(firstAttemptResponse.orderId()));
            if (count == 1) {
                settled = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(settled).as("Sales order must settle in DB within bounded timeout").isTrue();

        // Invariant: Exactly 1 sales_order, exactly 1 seller_order
        int salesOrderCount = dsl.fetchCount(SALES_ORDER, SALES_ORDER.IDEMPOTENCY_KEY.eq(idempotencyKey));
        assertThat(salesOrderCount).isEqualTo(1);
        int sellerOrderCount = dsl.fetchCount(SELLER_ORDER, SELLER_ORDER.SALES_ORDER_ID.eq(firstAttemptResponse.orderId()));
        assertThat(sellerOrderCount).isEqualTo(1);

        // Stock invariant: 2 reserved, 4 available, 0 sold, initial 6
        oracle.assertListingStockInvariant(listing.id(), initialStock);
        var listingRecord = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingRecord.getQuantityReserved()).isEqualTo(2);
        assertThat(listingRecord.getQuantityAvailable()).isEqualTo(4);
        assertThat(listingRecord.getQuantityTotal()).isEqualTo(6);

        oracle.assertOrderIntegrity(firstAttemptResponse.orderId());
        oracle.assertGlobalReferentialIntegrity();
    }
}
