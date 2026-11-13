package com.pegasus.pegasustcgapi.qa.reconciliation;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payout.PAYOUT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.finance.payout.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.finance.payout.PayoutService;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService.Bucket;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService.Direction;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [QA-03-B] Targeted Concurrency &amp; Race Condition Reconciliation Tests.
 */
@DisplayName("[QA-03-B] Concurrency & Race Condition Invariants")
class ConcurrencyRaceReconciliationTest extends PostgresIntegrationTest {

    @Autowired private CheckoutService checkout;
    @Autowired private CartService cart;
    @Autowired private PaymentService payments;
    @Autowired private OrderLifecycleService orders;
    @Autowired private PayoutService payouts;
    @Autowired private PayoutAccountRepository payoutAccounts;
    @Autowired private WalletLedgerService walletLedgerService;
    @Autowired private TransactionTemplate transactions;

    private ReconciliationOracle oracle() {
        return new ReconciliationOracle(dsl);
    }

    @Test
    @DisplayName("Race 1: Overselling Race Condition — 10 Concurrent Threads Competing for Last 2 Cards")
    void oversellingRace_TenThreads_TwoCards_ZeroNegativeStock() throws Exception {
        var oracle = oracle();
        var seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Pikachu Illustrator " + data.tag(), "PR");

        int initialStock = 2;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "999.00");
        oracle.assertListingStockInvariant(listing.id(), initialStock);

        int buyerCount = 10;
        List<AuthPrincipal> buyers = new ArrayList<>();
        for (int i = 0; i < buyerCount; i++) {
            AuthPrincipal buyer = data.buyer();
            buyers.add(buyer);
            cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        }

        AtomicInteger successes = new AtomicInteger();
        Queue<ErrorCode> errors = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);
        try {
            CountDownLatch ready = new CountDownLatch(buyerCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < buyerCount; i++) {
                final AuthPrincipal buyer = buyers.get(i);
                final String key = "race-chk-" + data.tag() + "-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (start.await(30, TimeUnit.SECONDS)) {
                            checkout.checkout(buyer, key, null, null);
                            successes.incrementAndGet();
                        }
                    } catch (ConflictException e) {
                        errors.add(e.errorCode());
                    } catch (Exception e) {
                        // unexpected
                    }
                    return null;
                }));
            }

            boolean allReady = ready.await(30, TimeUnit.SECONDS);
            assertThat(allReady).as("All worker threads must reach ready latch").isTrue();
            start.countDown();

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // 1. Assert exactly 2 successes, 8 graceful conflict failures
        assertThat(successes.get()).as("Exactly 2 buyers must secure the stock").isEqualTo(2);
        assertThat(errors).hasSize(8)
                .isSubsetOf(ErrorCode.INSUFFICIENT_STOCK, ErrorCode.LISTING_NOT_PURCHASABLE);

        // 2. Listing and physical unit stock assertions
        var listingRecord = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingRecord.getQuantityAvailable()).isZero();
        assertThat(listingRecord.getQuantityReserved()).isEqualTo(2);
        assertThat(listingRecord.getQuantityTotal()).isEqualTo(2);

        int listedCount = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listing.id())
                .and(LISTING_UNIT.STATUS.eq("LISTED")));
        int reservedCount = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listing.id())
                .and(LISTING_UNIT.STATUS.eq("RESERVED")));

        assertThat(listedCount).isZero();
        assertThat(reservedCount).isEqualTo(2);

        oracle.assertListingStockInvariant(listing.id(), initialStock);
        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Race 2: Colliding Order State Transitions — Simultaneous Cancel vs Pay on Same Order")
    void collidingTransitions_SimultaneousCancelVsPay_ReconcilesToConsistentTerminalState() throws Exception {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Mewtwo Star " + data.tag(), "UR");

        var listing = data.onSale(seller, card, CardCondition.NM, 1, "500.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "collide-" + data.tag(), null, null);
        long salesOrderId = order.orderId();

        AtomicInteger cancelSuccesses = new AtomicInteger();
        AtomicInteger paySuccesses = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<?> cancelFuture = pool.submit(() -> {
                ready.countDown();
                try {
                    if (start.await(30, TimeUnit.SECONDS)) {
                        orders.cancelBuyerOrder(buyer, salesOrderId, new CancelOrderRequest("Fast cancel"));
                        cancelSuccesses.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

            Future<?> payFuture = pool.submit(() -> {
                ready.countDown();
                try {
                    if (start.await(30, TimeUnit.SECONDS)) {
                        payments.payMock(buyer, salesOrderId);
                        paySuccesses.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

            boolean allReady = ready.await(30, TimeUnit.SECONDS);
            assertThat(allReady).as("All worker threads must reach ready latch").isTrue();
            start.countDown();

            cancelFuture.get(30, TimeUnit.SECONDS);
            payFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // In the collision race between cancel and pay:
        // Case A: cancel commits first -> order CANCELLED, stock released. Pay then fails.
        // Case B: pay commits first -> order PAID. Since PAID is in CANCELLABLE_STATUSES,
        // cancel can legitimately succeed on the paid order, transitioning it to CANCELLED and queueing a refund.
        // In both cases, at least one transition succeeds and the system terminates in a valid, reconciled state.
        assertThat(cancelSuccesses.get() + paySuccesses.get())
                .as("At least one transition must succeed in collision race")
                .isGreaterThanOrEqualTo(1);

        var finalSalesOrder = dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(salesOrderId)).fetchSingle();
        assertThat(finalSalesOrder.getStatus()).isIn("CANCELLED", "PAID");

        if ("CANCELLED".equals(finalSalesOrder.getStatus())) {
            oracle.assertListingStockInvariant(listing.id(), 1);
            var l = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
            assertThat(l.getQuantityAvailable()).isEqualTo(1);
            assertThat(l.getQuantityReserved()).isZero();
        } else {
            oracle.assertEscrowReconciliation(order.sellerOrders().getFirst().id());
            oracle.assertWalletEquation(walletLedgerService.platformWalletId());
        }

        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Race 3: Concurrent Wallet Payout Requests — Exceeding Balance Refuses Overdraw (balance >= 0)")
    void concurrentPayoutRequests_ExceedingBalance_RefusesOverdrawAndConservesBalance() throws Exception {
        var oracle = oracle();
        var seller = data.seller();
        var admin = data.admin();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "SellerPayout " + data.tag(), "R");

        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Prasert")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "KBANK")
                .set(SELLER_VERIFICATION.BANK_NAME, "Kasikorn Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "111-2-33333-4")
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);

        payoutAccounts.upsert(seller.profileId(), verificationId, "KBANK", "Kasikorn Bank", "Prasert Seller", "1112333334");

        // Seed legitimate seller earnings via completed order (avoiding synthetic orphan ledger entries)
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "200.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "payout-race-" + data.tag(), null, null);
        payments.payMock(buyer, order.orderId());
        orders.shipSellerOrder(seller.principal(), order.sellerOrders().getFirst().id(),
                new ShipOrderRequest("Kerry", "KR-" + data.tag(), "KERRY", null, null));
        orders.confirmBuyerOrderReceived(buyer, order.orderId());

        long sellerWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        BigDecimal withdrawableAmount = dsl.select(WALLET.BALANCE_AVAILABLE).from(WALLET)
                .where(WALLET.ID.eq(sellerWalletId)).fetchSingle(WALLET.BALANCE_AVAILABLE);

        oracle.assertWalletEquation(sellerWalletId);

        // Two concurrent threads each attempt to request payout of full withdrawableAmount
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (start.await(30, TimeUnit.SECONDS)) {
                            payouts.request(seller.userId(), withdrawableAmount);
                            successes.incrementAndGet();
                        }
                    } catch (ConflictException e) {
                        if (e.errorCode() == ErrorCode.INSUFFICIENT_BALANCE) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                }));
            }

            boolean allReady = ready.await(30, TimeUnit.SECONDS);
            assertThat(allReady).as("All worker threads must reach ready latch").isTrue();
            start.countDown();

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // Exactly 1 payout request can succeed, 1 must fail with INSUFFICIENT_BALANCE
        assertThat(successes.get()).as("Exactly 1 concurrent payout request succeeds").isEqualTo(1);
        assertThat(failures.get()).as("Exactly 1 concurrent payout request fails with INSUFFICIENT_BALANCE").isEqualTo(1);

        int payoutRows = dsl.fetchCount(PAYOUT, PAYOUT.SELLER_PROFILE_ID.eq(seller.profileId()));
        assertThat(payoutRows).isEqualTo(1);

        oracle.assertWalletEquation(sellerWalletId);
        oracle.assertGlobalReferentialIntegrity();
    }
}
