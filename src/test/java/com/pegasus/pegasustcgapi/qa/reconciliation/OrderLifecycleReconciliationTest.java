package com.pegasus.pegasustcgapi.qa.reconciliation;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.PlatformSetting.PLATFORM_SETTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.job.payment.PendingPaymentTimeoutScheduler;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.finance.payout.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.repository.payment.PaymentRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.aftersales.ReturnService;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.finance.payout.PayoutService;
import com.pegasus.pegasustcgapi.service.finance.refund.RefundService;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * [QA-03-B] Full Transaction Matrix Lifecycle Integration Tests with SQL Reconciliation Oracle.
 */
@DisplayName("[QA-03-B] Order Lifecycle & Financial Reconciliation Matrix")
class OrderLifecycleReconciliationTest extends PostgresIntegrationTest {

    @MockitoBean private StorageService storage;
    @Autowired private CheckoutService checkout;
    @Autowired private CartService cart;
    @Autowired private PaymentService payments;
    @Autowired private PaymentRepository paymentRows;
    @Autowired private OrderLifecycleService orders;
    @Autowired private ReturnService returns;
    @Autowired private RefundService refunds;
    @Autowired private PayoutService payouts;
    @Autowired private PayoutAccountRepository payoutAccounts;
    @Autowired private WalletLedgerService walletLedgerService;
    @Autowired private PendingPaymentTimeoutScheduler timeoutScheduler;

    private ReconciliationOracle oracle() {
        return new ReconciliationOracle(dsl);
    }

    @Test
    @DisplayName("Lifecycle 1: Normal E2E Flow — Stock Seed -> Checkout -> Paid -> Completed -> Escrow Released -> Payout")
    void normalE2EOrderLifecycle_FullReconciliation() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var admin = data.admin();
        var card = data.card(data.game("Pokemon"), "Charizard VMAX " + data.tag(), "UR");

        // 1. Initial Stock Seed: 5 units @ 100.00 THB
        int initialStock = 5;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "100.00");
        oracle.assertListingStockInvariant(listing.id(), initialStock);

        // 2. Checkout 2 cards
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 2));
        var checkoutResponse = checkout.checkout(buyer, "normal-e2e-" + data.tag(), null, null);
        long salesOrderId = checkoutResponse.orderId();
        long sellerOrderId = checkoutResponse.sellerOrders().getFirst().id();

        // Invariant check post-checkout
        oracle.assertListingStockInvariant(listing.id(), initialStock);
        oracle.assertOrderFinancialConservation(salesOrderId);
        oracle.assertOrderStockMovements(salesOrderId);

        var listingRecord = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingRecord.getQuantityAvailable()).isEqualTo(3);
        assertThat(listingRecord.getQuantityReserved()).isEqualTo(2);
        assertThat(listingRecord.getQuantityTotal()).isEqualTo(5);

        // 3. Payment Paid (Escrow Funded)
        long platformWalletId = walletLedgerService.platformWalletId();
        BigDecimal platformPendingBefore = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(platformWalletId)).fetchSingle(WALLET.BALANCE_PENDING);

        var payment = payments.create(buyer, salesOrderId, "BANK_TRANSFER", "pay-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/test-" + data.tag() + ".png", "BANK-TX-" + data.tag());
        payments.verify(admin.userId(), payment.id(), true, null);

        // Invariant check post-payment
        oracle.assertWalletEquation(platformWalletId);
        oracle.assertEscrowReconciliation(sellerOrderId);
        oracle.assertOrderStockMovements(salesOrderId);

        BigDecimal platformPendingAfter = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.ID.eq(platformWalletId)).fetchSingle(WALLET.BALANCE_PENDING);
        assertThat(platformPendingAfter.subtract(platformPendingBefore))
                .isEqualByComparingTo(checkoutResponse.grandTotal());

        // 4. Order Shipped & Completed (Receipt confirmed by buyer)
        orders.shipSellerOrder(seller.principal(), sellerOrderId,
                new ShipOrderRequest("Kerry Express", "KRY-" + data.tag(), "KERRY", null, LocalDate.now().plusDays(2)));
        oracle.assertOrderStockMovements(salesOrderId);

        // Units are now SOLD
        int soldUnitsCount = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listing.id())
                .and(LISTING_UNIT.STATUS.eq("SOLD")));
        assertThat(soldUnitsCount).isEqualTo(2);

        // Buyer confirms receipt -> escrow released
        orders.confirmBuyerOrderReceived(buyer, salesOrderId);

        // Invariant check post-order completion & escrow release
        oracle.assertEscrowReconciliation(sellerOrderId);
        oracle.assertWalletEquation(platformWalletId);

        long sellerWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        oracle.assertWalletEquation(sellerWalletId);

        var sellerOrderRecord = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle();
        assertThat(sellerOrderRecord.getStatus()).isEqualTo("COMPLETED");

        // 5. Payout Requested & Completed
        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Somchai")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "BBL")
                .set(SELLER_VERIFICATION.BANK_NAME, "Bangkok Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "001-2-34567-8")
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);

        payoutAccounts.upsert(seller.profileId(), verificationId, "BBL", "Bangkok Bank", "Somchai Seller", "0012345678");

        BigDecimal sellerBalance = dsl.select(WALLET.BALANCE_AVAILABLE).from(WALLET)
                .where(WALLET.ID.eq(sellerWalletId)).fetchSingle(WALLET.BALANCE_AVAILABLE);
        assertThat(sellerBalance).isEqualByComparingTo(sellerOrderRecord.getSellerNetAmount());

        var payoutRecord = payouts.request(seller.userId(), sellerBalance);
        payouts.approve(payoutRecord.getId(), admin.userId());
        payouts.processing(payoutRecord.getId(), admin.userId());
        payouts.paid(payoutRecord.getId(), "TRANSFER-REF-" + data.tag(), "payouts/test-" + data.tag() + ".png", admin.userId());

        // Post-payout reconciliation
        oracle.assertWalletEquation(sellerWalletId);
        oracle.assertWalletEquation(platformWalletId);
        oracle.assertGlobalReferentialIntegrity();

        BigDecimal finalSellerBalance = dsl.select(WALLET.BALANCE_AVAILABLE).from(WALLET)
                .where(WALLET.ID.eq(sellerWalletId)).fetchSingle(WALLET.BALANCE_AVAILABLE);
        assertThat(finalSellerBalance).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Lifecycle 2: Buyer Cancel Flow — Reserved Stock Returns to Available, Zero Phantom Ledger")
    void buyerCancelFlow_StockReturnsToAvailable_ZeroPhantomLedger() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("OnePiece"), "Luffy " + data.tag(), "SEC");

        int initialStock = 4;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "250.00");
        oracle.assertListingStockInvariant(listing.id(), initialStock);

        // Checkout 1 card
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "cancel-flow-" + data.tag(), null, null);
        long salesOrderId = order.orderId();

        // Stock reserved
        var listingAfterCheckout = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingAfterCheckout.getQuantityAvailable()).isEqualTo(3);
        assertThat(listingAfterCheckout.getQuantityReserved()).isEqualTo(1);

        // Buyer cancels before payment
        orders.cancelBuyerOrder(buyer, salesOrderId, new CancelOrderRequest("Changed my mind"));

        // Reconcile stock and verify release
        oracle.assertListingStockInvariant(listing.id(), initialStock);
        oracle.assertOrderStockMovements(salesOrderId);

        var listingAfterCancel = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingAfterCancel.getQuantityAvailable()).isEqualTo(4);
        assertThat(listingAfterCancel.getQuantityReserved()).isEqualTo(0);
        assertThat(listingAfterCancel.getQuantityTotal()).isEqualTo(4);

        // Ensure zero ledger entries created
        int ledgerCount = dsl.fetchCount(LEDGER_ENTRY,
                LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(order.sellerOrders().getFirst().id())));
        assertThat(ledgerCount).isZero();

        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Lifecycle 3: Payment Timeout Flow — Scheduler Cancels Unpaid Order & Releases Stock Cleanly")
    void paymentTimeoutFlow_ReleasesStockCleanly() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Gengar " + data.tag(), "SR");

        int initialStock = 3;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "500.00");

        // Checkout 1 card
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "timeout-flow-" + data.tag(), null, null);
        long salesOrderId = order.orderId();
        long sellerOrderId = order.sellerOrders().getFirst().id();

        // Configure 5 minute timeout and advance test clock past timeout cutoff
        data.setting(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES, "5");
        var placedAt = dsl.select(SALES_ORDER.PLACED_AT).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(salesOrderId)).fetchSingle(SALES_ORDER.PLACED_AT);
        clock.setInstant(placedAt.plusMinutes(6).toInstant());

        // Run timeout scheduler sweep
        timeoutScheduler.processExpiredOrders();

        // Verify order is terminal CANCELLED
        var so = dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(salesOrderId)).fetchSingle();
        assertThat(so.getStatus()).isEqualTo("CANCELLED");

        var sub = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle();
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");

        // Stock restored to available
        oracle.assertListingStockInvariant(listing.id(), initialStock);
        oracle.assertOrderStockMovements(salesOrderId);

        var listingRecord = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(listingRecord.getQuantityAvailable()).isEqualTo(3);
        assertThat(listingRecord.getQuantityReserved()).isEqualTo(0);

        // Zero ledger rows
        int ledgerCount = dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.REFERENCE_ID.eq(sellerOrderId));
        assertThat(ledgerCount).isZero();

        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Lifecycle 4: Full Refund Flow — Return Requested & Approved, Stock Restocked, Escrow Refunded")
    void fullRefundFlow_StockRestocked_EscrowRefunded() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var admin = data.admin();
        var card = data.card(data.game("Pokemon"), "Rayquaza " + data.tag(), "HR");

        int initialStock = 2;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "300.00");

        // 1. Checkout and Pay
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "full-refund-" + data.tag(), null, null);
        long salesOrderId = order.orderId();
        long sellerOrderId = order.sellerOrders().getFirst().id();
        long orderItemId = order.sellerOrders().getFirst().items().getFirst().id();

        payments.payMock(buyer, salesOrderId);

        // 2. Ship and Deliver
        orders.shipSellerOrder(seller.principal(), sellerOrderId,
                new ShipOrderRequest("Flash Express", "FL-" + data.tag(), "FLASH", null, null));
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.DELIVERED_AT, OffsetDateTime.now().minusHours(1))
                .set(SELLER_ORDER.AUTO_COMPLETE_AT, OffsetDateTime.now().plusDays(2))
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).execute();

        // 3. Buyer opens Return Request
        var returnReq = returns.request(buyer.userId(), sellerOrderId, "DAMAGED", "Card is scratched",
                List.of(), List.of(new ReturnService.Item(orderItemId, 1)));

        // 4. Seller approves full refund without requiring return shipment (or receives it)
        returns.approve(seller.userId(), returnReq.getId(), "FULL_REFUND", new BigDecimal("300.00"), false);

        // 5. Refund is processed
        var refundRow = dsl.selectFrom(com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND)
                .where(com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND.RETURN_REQUEST_ID.eq(returnReq.getId()))
                .fetchSingle();
        assertThat(refundRow.getStatus()).isEqualTo("PROCESSED");

        // Reconcile Escrow, Wallet, Stock
        oracle.assertEscrowReconciliation(sellerOrderId);
        long platformWalletId = walletLedgerService.platformWalletId();
        oracle.assertWalletEquation(platformWalletId);
        oracle.assertGlobalReferentialIntegrity();

        var hold = dsl.selectFrom(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId)).fetchSingle();
        assertThat(hold.getRefundedAmount()).isEqualByComparingTo("300.00");
        assertThat(hold.getReleasedAmount()).isEqualByComparingTo("0.00");
        assertThat(hold.getStatus()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("Lifecycle 5: Partial Refund Flow — Multi-Item Order, 1 Returned, Escrow Split & Released")
    void partialRefundFlow_MultiItemOrder_EscrowSplitAndReleased() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var admin = data.admin();
        var card = data.card(data.game("Pokemon"), "Mew " + data.tag(), "UR");

        int initialStock = 5;
        var listing = data.onSale(seller, card, CardCondition.NM, initialStock, "100.00");

        // Checkout 2 cards
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 2));
        var order = checkout.checkout(buyer, "partial-refund-" + data.tag(), null, null);
        long salesOrderId = order.orderId();
        long sellerOrderId = order.sellerOrders().getFirst().id();
        long orderItemId = order.sellerOrders().getFirst().items().getFirst().id();

        var payment = payments.create(buyer, salesOrderId, "BANK_TRANSFER", "pay-part-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/test-" + data.tag() + ".png", "BANK-TX-" + data.tag());
        payments.verify(admin.userId(), payment.id(), true, null);

        orders.shipSellerOrder(seller.principal(), sellerOrderId,
                new ShipOrderRequest("Kerry Express", "KRY-" + data.tag(), "KERRY", null, null));
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.DELIVERED_AT, OffsetDateTime.now().minusHours(1))
                .set(SELLER_ORDER.AUTO_COMPLETE_AT, OffsetDateTime.now().plusDays(2))
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).execute();

        // Return 1 out of 2 cards
        var returnReq = returns.request(buyer.userId(), sellerOrderId, "NOT_AS_DESCRIBED", "1 card damaged",
                List.of(), List.of(new ReturnService.Item(orderItemId, 1)));

        // Seller approves partial refund of 100.00 THB
        returns.approve(seller.userId(), returnReq.getId(), "PARTIAL_REFUND", new BigDecimal("100.00"), false);

        var refundRow = dsl.selectFrom(com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND)
                .where(com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND.RETURN_REQUEST_ID.eq(returnReq.getId()))
                .fetchSingle();

        refunds.setDestination(refundRow.getId(), buyer.userId(), "Kasikornbank", "Buyer Name", "1234567890");
        refunds.startTransfer(refundRow.getId(), admin.userId(), "1234567890");
        refunds.processed(refundRow.getId(), admin.userId(), "REF-PART-" + data.tag());

        // Assert Escrow Hold is PARTIALLY_RELEASED: 100 refunded, 100 released
        var hold = dsl.selectFrom(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId)).fetchSingle();
        assertThat(hold.getRefundedAmount()).isEqualByComparingTo("100.00");
        assertThat(hold.getReleasedAmount()).isEqualByComparingTo("100.00");
        assertThat(hold.getStatus()).isEqualTo("PARTIALLY_RELEASED");

        // Reconcile wallets
        long platformWalletId = walletLedgerService.platformWalletId();
        long sellerWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        oracle.assertWalletEquation(platformWalletId);
        oracle.assertWalletEquation(sellerWalletId);
        oracle.assertGlobalReferentialIntegrity();
    }
}
