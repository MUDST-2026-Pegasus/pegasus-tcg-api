package com.pegasus.pegasustcgapi.qa.reconciliation;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payout.PAYOUT;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.jooq.tables.records.EscrowHoldRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.WalletRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Reconciliation Oracle &amp; SQL Assertion Engine for [TCG-756 / QA-03-B].
 *
 * <p>Executes deterministic invariant verifications across:
 * <ol>
 *   <li>Physical vs Denormalized Stock Quantities &amp; Status Transitions</li>
 *   <li>Double-Entry Ledger &amp; Wallet Balance Conservation</li>
 *   <li>Order Financial Calculations, Discount Allocations &amp; Escrow Release</li>
 *   <li>Referential Integrity, No-Orphan Postings &amp; Idempotency Uniqueness</li>
 * </ol>
 */
@Component
public class ReconciliationOracle {

    private final DSLContext dsl;

    public ReconciliationOracle(DSLContext dsl) {
        this.dsl = dsl;
    }

    // =========================================================================
    // 1. Stock Invariant Checks
    // =========================================================================

    /**
     * Asserts stock invariants on a specific listing:
     * <ul>
     *   <li>{@code quantity_total == quantity_available + quantity_reserved}</li>
     *   <li>{@code quantity_available >= 0 && quantity_reserved >= 0 && quantity_total >= 0}</li>
     *   <li>Denormalized quantities match count of physical units in LISTED and RESERVED states</li>
     *   <li>If initial seeded stock is provided, total tracked units (including sold/returned) match initial seed</li>
     * </ul>
     */
    public void assertListingStockInvariant(long listingId, Integer initialSeededTotal) {
        ListingRecord listing = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();

        int available = listing.getQuantityAvailable();
        int reserved = listing.getQuantityReserved();
        int total = listing.getQuantityTotal();

        assertThat(available).as("Listing #%d quantity_available must be >= 0", listingId).isGreaterThanOrEqualTo(0);
        assertThat(reserved).as("Listing #%d quantity_reserved must be >= 0", listingId).isGreaterThanOrEqualTo(0);
        assertThat(total).as("Listing #%d quantity_total must equal available + reserved", listingId)
                .isEqualTo(available + reserved);

        int listedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq("LISTED")).and(LISTING_UNIT.DELETED_AT.isNull()));
        int reservedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq("RESERVED")).and(LISTING_UNIT.DELETED_AT.isNull()));

        assertThat(available).as("Listing #%d quantity_available must match LISTED units", listingId)
                .isEqualTo(listedUnits);
        assertThat(reserved).as("Listing #%d quantity_reserved must match RESERVED units", listingId)
                .isEqualTo(reservedUnits);

        if (initialSeededTotal != null) {
            int soldUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                    .and(LISTING_UNIT.STATUS.eq("SOLD")));
            int returnedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                    .and(LISTING_UNIT.STATUS.eq("RETURNED")));
            int writtenOffUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                    .and(LISTING_UNIT.STATUS.eq("WRITTEN_OFF")));

            int accountedTotal = listedUnits + reservedUnits + soldUnits + returnedUnits + writtenOffUnits;
            assertThat(accountedTotal).as("Listing #%d initial seeded stock must equal sum of all unit states", listingId)
                    .isEqualTo(initialSeededTotal);
        }
    }

    /**
     * Asserts that every order item in the sales order has exact corresponding physical units
     * mapped via order_item_unit, and unit statuses match the order lifecycle state.
     */
    public void assertOrderStockMovements(long salesOrderId) {
        List<SellerOrderRecord> sellerOrders = dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId)).fetch();

        for (SellerOrderRecord so : sellerOrders) {
            var orderItems = dsl.selectFrom(ORDER_ITEM).where(ORDER_ITEM.SELLER_ORDER_ID.eq(so.getId())).fetch();

            for (var item : orderItems) {
                int linkedUnitsCount = dsl.fetchCount(ORDER_ITEM_UNIT, ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(item.getId()));
                assertThat(linkedUnitsCount)
                        .as("OrderItem #%d quantity (%d) must match order_item_unit row count", item.getId(), item.getQuantity())
                        .isEqualTo(item.getQuantity());

                List<String> unitStatuses = dsl.select(LISTING_UNIT.STATUS)
                        .from(ORDER_ITEM_UNIT)
                        .join(LISTING_UNIT).on(ORDER_ITEM_UNIT.LISTING_UNIT_ID.eq(LISTING_UNIT.ID))
                        .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(item.getId()))
                        .fetch(LISTING_UNIT.STATUS);

                for (String status : unitStatuses) {
                    switch (so.getStatus()) {
                        case "PENDING_PAYMENT", "PAID", "PREPARING" -> assertThat(status)
                                .as("Unit for active order #%d must be RESERVED", so.getId())
                                .isEqualTo("RESERVED");
                        case "SHIPPED", "DELIVERED", "COMPLETED" -> assertThat(status)
                                .as("Unit for fulfilled order #%d must be SOLD", so.getId())
                                .isEqualTo("SOLD");
                        case "CANCELLED" -> assertThat(status)
                                .as("Unit for cancelled order #%d must not remain RESERVED", so.getId())
                                .isNotEqualTo("RESERVED");
                        case "RETURNED", "REFUNDED" -> assertThat(status)
                                .as("Unit for returned order #%d must be SOLD, RETURNED or IN_STOCK", so.getId())
                                .isIn("SOLD", "RETURNED", "IN_STOCK");
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. Financial & Ledger Invariant Checks
    // =========================================================================

    /**
     * Asserts the fundamental double-entry equation for a wallet:
     * {@code opening_balance + SUM(credits) - SUM(debits) == closing_balance}.
     * Also asserts zero unauthorized negative balances.
     */
    public void assertWalletEquation(long walletId) {
        WalletRecord wallet = dsl.selectFrom(WALLET).where(WALLET.ID.eq(walletId)).fetchSingle();

        if ("SELLER".equals(wallet.getKind())) {
            assertThat(wallet.getBalanceAvailable())
                    .as("Seller wallet #%d balance_available must strictly remain >= 0", walletId)
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(wallet.getBalancePending())
                    .as("Seller wallet #%d balance_pending must strictly remain ZERO", walletId)
                    .isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            // Platform wallet: pending holds escrow and must strictly remain >= 0.
            // Available balance represents net commission cash minus promo subsidies funded;
            // it is permitted to sit below zero per ck_wallet_non_negative when subsidies exceed earnings.
            assertThat(wallet.getBalancePending())
                    .as("Platform wallet balance_pending must strictly remain >= 0", walletId)
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        BigDecimal ledgerNetTotal = dsl.select(DSL.coalesce(
                        DSL.sum(DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"), LEDGER_ENTRY.AMOUNT)
                                .otherwise(LEDGER_ENTRY.AMOUNT.neg())),
                        BigDecimal.ZERO))
                .from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.WALLET_ID.eq(walletId))
                .fetchSingle(0, BigDecimal.class);

        BigDecimal walletTotal = wallet.getBalanceAvailable().add(wallet.getBalancePending());

        assertThat(walletTotal)
                .as("Wallet #%d total balance (%s) must equal net ledger entries sum (%s)",
                        walletId, walletTotal, ledgerNetTotal)
                .isEqualByComparingTo(ledgerNetTotal);

        if ("SELLER".equals(wallet.getKind())) {
            assertThat(wallet.getBalanceAvailable())
                    .as("Seller wallet #%d balance_available must equal net ledger entries", walletId)
                    .isEqualByComparingTo(ledgerNetTotal);
        }
    }

    /**
     * Verifies platform wallet bucket separation:
     * {@code balance_pending} strictly equals the sum of outstanding active escrow holds.
     */
    public void assertPlatformWalletBuckets() {
        long platformWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.KIND.eq("PLATFORM")).fetchSingle(WALLET.ID);
        WalletRecord wallet = dsl.selectFrom(WALLET).where(WALLET.ID.eq(platformWalletId)).fetchSingle();

        BigDecimal activeEscrow = dsl.select(DSL.coalesce(
                        DSL.sum(ESCROW_HOLD.AMOUNT.subtract(ESCROW_HOLD.RELEASED_AMOUNT).subtract(ESCROW_HOLD.REFUNDED_AMOUNT)),
                        BigDecimal.ZERO))
                .from(ESCROW_HOLD)
                .where(ESCROW_HOLD.STATUS.in("HELD", "PARTIALLY_RELEASED"))
                .fetchSingle(0, BigDecimal.class);

        assertThat(wallet.getBalancePending())
                .as("Platform wallet balance_pending (%s) must equal outstanding active escrow holds (%s)",
                        wallet.getBalancePending(), activeEscrow)
                .isEqualByComparingTo(activeEscrow);

        assertWalletEquation(platformWalletId);
    }

    /**
     * Verifies money conservation across sales_order and seller_orders:
     * <ul>
     *   <li>{@code grand_total == items_subtotal + shipping_total - discount_total}</li>
     *   <li>Parent order totals equal the sum of child seller order totals</li>
     *   <li>For each suborder: {@code discount_amount == seller_discount + platform_discount}</li>
     *   <li>For each suborder: {@code seller_net == grand_total + platform_discount - commission}</li>
     *   <li>Item discount allocations sum up without exceeding total discounts</li>
     * </ul>
     */
    public void assertOrderFinancialConservation(long salesOrderId) {
        SalesOrderRecord so = dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(salesOrderId)).fetchSingle();

        BigDecimal itemsSubtotal = so.getItemsSubtotal();
        BigDecimal shippingTotal = so.getShippingTotal();
        BigDecimal discountTotal = so.getDiscountTotal();
        BigDecimal grandTotal = so.getGrandTotal();

        assertThat(itemsSubtotal).as("items_subtotal >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(shippingTotal).as("shipping_total >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(discountTotal).as("discount_total >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(grandTotal).as("grand_total >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);

        BigDecimal expectedGrandTotal = itemsSubtotal.add(shippingTotal).subtract(discountTotal);
        assertThat(grandTotal)
                .as("sales_order grand_total must equal items_subtotal + shipping_total - discount_total")
                .isEqualByComparingTo(expectedGrandTotal);

        List<SellerOrderRecord> sellerOrders = dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId)).fetch();

        BigDecimal sumSubtotal = BigDecimal.ZERO;
        BigDecimal sumShipping = BigDecimal.ZERO;
        BigDecimal sumDiscount = BigDecimal.ZERO;
        BigDecimal sumGrandTotal = BigDecimal.ZERO;

        for (SellerOrderRecord sub : sellerOrders) {
            sumSubtotal = sumSubtotal.add(sub.getItemsSubtotal());
            sumShipping = sumShipping.add(sub.getShippingFee());
            sumDiscount = sumDiscount.add(sub.getDiscountAmount());
            sumGrandTotal = sumGrandTotal.add(sub.getGrandTotal());

            // Suborder discount split
            assertThat(sub.getDiscountAmount())
                    .as("seller_order discount_amount must equal seller_discount + platform_discount")
                    .isEqualByComparingTo(sub.getSellerDiscountAmount().add(sub.getPlatformDiscountAmount()));

            // Suborder grand total
            BigDecimal subExpectedGrand = sub.getItemsSubtotal().add(sub.getShippingFee()).subtract(sub.getDiscountAmount());
            assertThat(sub.getGrandTotal())
                    .as("seller_order grand_total must equal items + shipping - discount")
                    .isEqualByComparingTo(subExpectedGrand);

            // Suborder seller net amount (accounting for platform promo subsidy)
            BigDecimal subExpectedNet = sub.getGrandTotal()
                    .add(sub.getPlatformDiscountAmount())
                    .subtract(sub.getCommissionAmount());
            assertThat(sub.getSellerNetAmount())
                    .as("seller_order net_amount must equal grand_total + platform_discount - commission")
                    .isEqualByComparingTo(subExpectedNet);

            // Order items discount allocation
            var items = dsl.selectFrom(ORDER_ITEM).where(ORDER_ITEM.SELLER_ORDER_ID.eq(sub.getId())).fetch();
            BigDecimal sumItemDiscounts = BigDecimal.ZERO;
            BigDecimal sumSellerItemDiscounts = BigDecimal.ZERO;

            for (var item : items) {
                assertThat(item.getLineTotal())
                        .as("OrderItem line_total must equal unit_price * quantity")
                        .isEqualByComparingTo(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                assertThat(item.getDiscountAllocated())
                        .as("discount_allocated must be <= line_total")
                        .isLessThanOrEqualTo(item.getLineTotal());

                sumItemDiscounts = sumItemDiscounts.add(item.getDiscountAllocated());
                sumSellerItemDiscounts = sumSellerItemDiscounts.add(item.getSellerDiscountAllocated());
            }

            assertThat(sumItemDiscounts)
                    .as("Sum of order_item discount_allocated must be <= seller_order discount_amount")
                    .isLessThanOrEqualTo(sub.getDiscountAmount());
            assertThat(sumSellerItemDiscounts)
                    .as("Sum of order_item seller_discount_allocated must equal seller_discount_amount")
                    .isEqualByComparingTo(sub.getSellerDiscountAmount());
        }

        assertThat(itemsSubtotal).as("Parent items_subtotal must equal sum of child subtotals").isEqualByComparingTo(sumSubtotal);
        assertThat(shippingTotal).as("Parent shipping_total must equal sum of child shipping fees").isEqualByComparingTo(sumShipping);
        assertThat(discountTotal).as("Parent discount_total must equal sum of child discounts").isEqualByComparingTo(sumDiscount);
        assertThat(grandTotal).as("Parent grand_total must equal sum of child grand_totals").isEqualByComparingTo(sumGrandTotal);
    }

    /**
     * Asserts escrow hold invariants for a suborder:
     * <ul>
     *   <li>Must strictly exist in database (never returns silently)</li>
     *   <li>{@code amount == seller_order.grand_total}</li>
     *   <li>{@code amount == released_amount + refunded_amount + remaining_held}</li>
     *   <li>In PARTIALLY_RELEASED: {@code released_amount > 0 && refunded_amount > 0 && released + refunded == amount}</li>
     * </ul>
     */
    public void assertEscrowReconciliation(long sellerOrderId) {
        EscrowHoldRecord hold = dsl.selectFrom(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId))
                .fetchOptional()
                .orElseThrow(() -> new AssertionError("Expected escrow_hold row to exist for seller_order #" + sellerOrderId));

        SellerOrderRecord so = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle();

        assertThat(hold.getAmount())
                .as("Escrow hold amount must equal seller_order grand_total")
                .isEqualByComparingTo(so.getGrandTotal());

        BigDecimal released = hold.getReleasedAmount();
        BigDecimal refunded = hold.getRefundedAmount();
        BigDecimal totalAccounted = released.add(refunded);

        assertThat(released).as("released_amount >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(refunded).as("refunded_amount >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(totalAccounted)
                .as("released_amount + refunded_amount must be <= hold amount")
                .isLessThanOrEqualTo(hold.getAmount());

        switch (hold.getStatus()) {
            case "HELD" -> {
                assertThat(released).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(refunded).isLessThan(hold.getAmount());
            }
            case "PARTIALLY_RELEASED" -> {
                assertThat(released).as("PARTIALLY_RELEASED released_amount must be > 0").isGreaterThan(BigDecimal.ZERO);
                assertThat(refunded).as("PARTIALLY_RELEASED refunded_amount must be > 0").isGreaterThan(BigDecimal.ZERO);
                assertThat(totalAccounted)
                        .as("PARTIALLY_RELEASED totalAccounted (released + refunded) must equal hold amount in full")
                        .isEqualByComparingTo(hold.getAmount());
            }
            case "RELEASED" -> assertThat(totalAccounted).isEqualByComparingTo(hold.getAmount());
            case "REFUNDED", "CANCELLED" -> assertThat(refunded).isEqualByComparingTo(hold.getAmount());
        }
    }

    /**
     * Asserts that no escrow_hold row exists for a seller_order (e.g. before payment or unpaid cancel).
     */
    public void assertEscrowAbsent(long sellerOrderId) {
        boolean exists = dsl.fetchExists(ESCROW_HOLD, ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId));
        assertThat(exists).as("escrow_hold must NOT exist for unpaid seller_order #%d", sellerOrderId).isFalse();
    }

    // =========================================================================
    // 3. Referential, Audit Sequence & State Integrity Checks
    // =========================================================================

    /**
     * Asserts that seller_order_status_history forms a valid chronological sequence of state transitions.
     *
     * @param sellerOrderId target suborder
     * @param expectedSequence optional explicit sequence of status names (e.g. ["PAID", "SHIPPED", "COMPLETED"])
     */
    public void assertOrderStatusHistorySequence(long sellerOrderId, List<String> expectedSequence) {
        var histories = dsl.select(SELLER_ORDER_STATUS_HISTORY.FROM_STATUS, SELLER_ORDER_STATUS_HISTORY.TO_STATUS)
                .from(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.ID.asc())
                .fetch();

        assertThat(histories).as("SellerOrder #%d must have status history entries", sellerOrderId).isNotEmpty();

        for (int i = 1; i < histories.size(); i++) {
            String prevTo = histories.get(i - 1).value2();
            String currFrom = histories.get(i).value1();
            assertThat(currFrom)
                    .as("History step #%d from_status must match step #%d to_status", i, i - 1)
                    .isEqualTo(prevTo);
        }

        String currentStatus = dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle(SELLER_ORDER.STATUS);
        assertThat(histories.getLast().value2())
                .as("Final history transition must match current seller_order status")
                .isEqualTo(currentStatus);

        if (expectedSequence != null && !expectedSequence.isEmpty()) {
            List<String> actualTo = histories.map(r -> r.value2());
            assertThat(actualTo).as("SellerOrder #%d history sequence mismatch", sellerOrderId)
                    .isEqualTo(expectedSequence);
        }
    }

    /**
     * Scoped referential and ledger integrity for a specific sales order and its child entities.
     */
    public void assertOrderIntegrity(long salesOrderId) {
        List<Long> suborderIds = dsl.select(SELLER_ORDER.ID)
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId))
                .fetch(SELLER_ORDER.ID);

        List<Long> paymentIds = dsl.select(PAYMENT.ID)
                .from(PAYMENT)
                .where(PAYMENT.SALES_ORDER_ID.eq(salesOrderId))
                .fetch(PAYMENT.ID);

        List<Long> refundIds = dsl.select(REFUND.ID)
                .from(REFUND)
                .where(REFUND.SALES_ORDER_ID.eq(salesOrderId))
                .fetch(REFUND.ID);

        // 1. Audit trail existence & valid sequence for all suborders
        for (Long soId : suborderIds) {
            assertOrderStatusHistorySequence(soId, null);
        }

        // 2. Collect scoped isolated wallets involved in this order (sellers and buyer)
        List<Long> sellerUserIds = dsl.select(SELLER_PROFILE.USER_ID)
                .from(SELLER_ORDER)
                .join(SELLER_PROFILE).on(SELLER_ORDER.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID))
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId))
                .fetch(SELLER_PROFILE.USER_ID);

        List<Long> scopedWalletIds = new ArrayList<>(dsl.select(WALLET.ID)
                .from(WALLET)
                .where(WALLET.USER_ID.in(sellerUserIds))
                .fetch(WALLET.ID));

        Long buyerUserId = dsl.select(SALES_ORDER.BUYER_ID)
                .from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(salesOrderId))
                .fetchOne(SALES_ORDER.BUYER_ID);
        if (buyerUserId != null) {
            scopedWalletIds.addAll(dsl.select(WALLET.ID)
                    .from(WALLET)
                    .where(WALLET.USER_ID.eq(buyerUserId))
                    .fetch(WALLET.ID));
        }

        // Assert 0 orphan ledger entries across scoped wallets
        assertScopedOrphanReferences(scopedWalletIds);

        // 3. Zero duplicate financial postings for these references
        var duplicateOrderPostings = dsl.select(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.DIRECTION,
                        LEDGER_ENTRY.REFERENCE_TYPE, LEDGER_ENTRY.REFERENCE_ID, DSL.count())
                .from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.ne("ADJUSTMENT"))
                .and(
                        (LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER").and(LEDGER_ENTRY.REFERENCE_ID.in(suborderIds)))
                                .or(LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT").and(LEDGER_ENTRY.REFERENCE_ID.in(paymentIds)))
                                .or(LEDGER_ENTRY.REFERENCE_TYPE.eq("REFUND").and(LEDGER_ENTRY.REFERENCE_ID.in(refundIds)))
                )
                .groupBy(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.DIRECTION,
                        LEDGER_ENTRY.REFERENCE_TYPE, LEDGER_ENTRY.REFERENCE_ID)
                .having(DSL.count().gt(1))
                .fetch();
        assertThat(duplicateOrderPostings).as("0 duplicate ledger postings for order #%d", salesOrderId).isEmpty();

        // 4. Wallet balance equation for involved seller wallets and platform wallet
        long platformWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.KIND.eq("PLATFORM")).fetchSingle(WALLET.ID);
        assertWalletEquation(platformWalletId);

        for (Long sUid : sellerUserIds) {
            dsl.select(WALLET.ID).from(WALLET).where(WALLET.USER_ID.eq(sUid))
                    .fetchOptional(WALLET.ID)
                    .ifPresent(this::assertWalletEquation);
        }
    }

    /**
     * Checks that all ledger entries associated with the given scoped wallets have valid
     * references in their corresponding target tables (SELLER_ORDER, PAYMENT, REFUND, PAYOUT).
     */
    public void assertScopedOrphanReferences(Collection<Long> walletIds) {
        if (walletIds == null || walletIds.isEmpty()) {
            return;
        }

        int orphanEntries = dsl.fetchCount(
                DSL.selectFrom(LEDGER_ENTRY)
                        .where(LEDGER_ENTRY.WALLET_ID.in(walletIds))
                        .and(
                                (LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER")
                                        .and(LEDGER_ENTRY.REFERENCE_ID.isNull()
                                                .orNotExists(DSL.selectOne().from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))))
                                .or(LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT")
                                        .and(LEDGER_ENTRY.REFERENCE_ID.isNull()
                                                .orNotExists(DSL.selectOne().from(PAYMENT).where(PAYMENT.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))))
                                .or(LEDGER_ENTRY.REFERENCE_TYPE.eq("REFUND")
                                        .and(LEDGER_ENTRY.REFERENCE_ID.isNull()
                                                .orNotExists(DSL.selectOne().from(REFUND).where(REFUND.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))))
                                .or(LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYOUT")
                                        .and(LEDGER_ENTRY.REFERENCE_ID.isNull()
                                                .orNotExists(DSL.selectOne().from(PAYOUT).where(PAYOUT.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))))
                        )
        );
        assertThat(orphanEntries)
                .as("0 orphan ledger entries for scoped wallets %s", walletIds)
                .isZero();
    }

    /**
     * Scoped referential and ledger integrity for a specific wallet and its transactions.
     */
    public void assertWalletIntegrity(long walletId) {
        assertWalletEquation(walletId);
        assertScopedOrphanReferences(List.of(walletId));
    }

    /**
     * Global invariant verification that holds true regardless of other tests.
     */
    public void assertGlobalReferentialIntegrity() {
        int negativeWallets = dsl.fetchCount(WALLET,
                WALLET.BALANCE_PENDING.lt(BigDecimal.ZERO)
                        .or(WALLET.KIND.ne("PLATFORM").and(WALLET.BALANCE_AVAILABLE.lt(BigDecimal.ZERO))));
        assertThat(negativeWallets).as("Database must contain 0 unauthorized negative wallet balances").isZero();

        assertPlatformWalletBuckets();
    }
}
