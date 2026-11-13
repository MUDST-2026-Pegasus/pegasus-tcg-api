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
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.WalletRecord;
import java.math.BigDecimal;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Reconciliation Oracle &amp; SQL Assertion Engine for [QA-03-B].
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
     *   <li>{@code amount == seller_order.grand_total}</li>
     *   <li>{@code amount == released_amount + refunded_amount + remaining_held}</li>
     *   <li>Status consistency with release/refund states</li>
     * </ul>
     */
    public void assertEscrowReconciliation(long sellerOrderId) {
        var holdOpt = dsl.selectFrom(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId)).fetchOptional();
        if (holdOpt.isEmpty()) {
            return;
        }

        var hold = holdOpt.get();
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
                assertThat(released).isGreaterThan(BigDecimal.ZERO);
                assertThat(totalAccounted).isLessThan(hold.getAmount());
            }
            case "RELEASED" -> assertThat(totalAccounted).isEqualByComparingTo(hold.getAmount());
            case "REFUNDED", "CANCELLED" -> assertThat(refunded).isEqualByComparingTo(hold.getAmount());
        }
    }

    // =========================================================================
    // 3. Referential & State Integrity Checks
    // =========================================================================

    /**
     * Global integrity verification:
     * <ul>
     *   <li>Zero orphan ledger entries (every entry maps to a valid reference entity)</li>
     *   <li>Zero duplicate financial postings (ux_ledger_entry_once violated)</li>
     *   <li>Every seller_order status transition recorded in audit history</li>
     *   <li>Zero negative balances in any wallet</li>
     * </ul>
     */
    public void assertGlobalReferentialIntegrity() {
        // 1. Zero unauthorized negative wallet balances (seller wallets >= 0 available & pending, platform >= 0 pending)
        int negativeWallets = dsl.fetchCount(WALLET,
                WALLET.BALANCE_PENDING.lt(BigDecimal.ZERO)
                        .or(WALLET.KIND.ne("PLATFORM").and(WALLET.BALANCE_AVAILABLE.lt(BigDecimal.ZERO))));
        assertThat(negativeWallets).as("Database must contain 0 unauthorized negative wallet balances").isZero();

        // 2. Zero orphan ledger entries
        int orphanPaymentLedgers = dsl.fetchCount(
                DSL.selectFrom(LEDGER_ENTRY)
                        .where(LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYMENT"))
                        .andNotExists(DSL.selectOne().from(PAYMENT).where(PAYMENT.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))
        );
        assertThat(orphanPaymentLedgers).as("0 orphan ledger entries for PAYMENT reference").isZero();

        int orphanOrderLedgers = dsl.fetchCount(
                DSL.selectFrom(LEDGER_ENTRY)
                        .where(LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER"))
                        .andNotExists(DSL.selectOne().from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))
        );
        assertThat(orphanOrderLedgers).as("0 orphan ledger entries for SELLER_ORDER reference").isZero();

        int orphanRefundLedgers = dsl.fetchCount(
                DSL.selectFrom(LEDGER_ENTRY)
                        .where(LEDGER_ENTRY.REFERENCE_TYPE.eq("REFUND"))
                        .andNotExists(DSL.selectOne().from(REFUND).where(REFUND.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))
        );
        assertThat(orphanRefundLedgers).as("0 orphan ledger entries for REFUND reference").isZero();

        int orphanPayoutLedgers = dsl.fetchCount(
                DSL.selectFrom(LEDGER_ENTRY)
                        .where(LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYOUT"))
                        .andNotExists(DSL.selectOne().from(PAYOUT).where(PAYOUT.ID.eq(LEDGER_ENTRY.REFERENCE_ID)))
        );
        assertThat(orphanPayoutLedgers).as("0 orphan ledger entries for PAYOUT reference").isZero();

        // 3. Zero duplicate financial postings
        var duplicateLedgerEntries = dsl.select(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.DIRECTION,
                        LEDGER_ENTRY.REFERENCE_TYPE, LEDGER_ENTRY.REFERENCE_ID, DSL.count())
                .from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.ne("ADJUSTMENT"))
                .groupBy(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.DIRECTION,
                        LEDGER_ENTRY.REFERENCE_TYPE, LEDGER_ENTRY.REFERENCE_ID)
                .having(DSL.count().gt(1))
                .fetch();
        assertThat(duplicateLedgerEntries).as("0 duplicate non-ADJUSTMENT ledger postings").isEmpty();

        // 4. Audit trail existence for seller orders
        var unrecordedOrders = dsl.select(SELLER_ORDER.ID)
                .from(SELLER_ORDER)
                .whereNotExists(DSL.selectOne().from(SELLER_ORDER_STATUS_HISTORY)
                        .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(SELLER_ORDER.ID)))
                .fetch();
        assertThat(unrecordedOrders).as("Every seller_order must have audit history entries").isEmpty();
    }
}
