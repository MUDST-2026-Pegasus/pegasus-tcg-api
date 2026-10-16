package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payout.PAYOUT;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.RefundTransferAttempt.REFUND_TRANSFER_ATTEMPT;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerPayoutAccount.SELLER_PAYOUT_ACCOUNT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;

import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminRefund;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.Party;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PaymentSummary;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutAccount;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutOrder;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutSummary;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.RefundSummary;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.SellerSplit;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.WalletSnapshot;
import com.pegasus.pegasustcgapi.jooq.tables.SellerProfile;
import com.pegasus.pegasustcgapi.jooq.tables.UserAccount;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read side of the admin money desks. Each queue is one statement: the people,
 * order numbers and balances an admin checks before moving money are joined or
 * read through scalar subqueries rather than fetched row by row.
 *
 * <p>Writes stay in {@link PaymentRepository}, {@link RefundRepository} and
 * {@link PayoutRepository}; nothing here locks.
 */
@Repository
public class AdminFinanceRepository {

    private static final UserAccount BUYER = USER_ACCOUNT.as("buyer");
    private static final UserAccount SELLER_ACCOUNT = USER_ACCOUNT.as("seller_account");
    private static final UserAccount APPROVER = USER_ACCOUNT.as("approver");
    private static final SellerProfile SELLER = SELLER_PROFILE.as("seller");

    private final DSLContext dsl;
    private final StorageService storage;

    public AdminFinanceRepository(DSLContext dsl, StorageService storage) {
        this.dsl = dsl;
        this.storage = storage;
    }

    private Party buyer(Record r) {
        return new Party(r.get(BUYER.ID), r.get(BUYER.USERNAME), r.get(BUYER.DISPLAY_NAME),
                avatar(r.get(BUYER.AVATAR_URL)));
    }

    private Party seller(Record r, long sellerProfileId) {
        return new Party(sellerProfileId, r.get(SELLER_ACCOUNT.USERNAME),
                r.get(SELLER_ACCOUNT.DISPLAY_NAME), avatar(r.get(SELLER_ACCOUNT.AVATAR_URL)));
    }

    /** {@code user_account.avatar_url} holds an object key; the page needs a short-lived link. */
    private String avatar(String key) {
        return key == null ? null : storage.presignDownload(key);
    }

    // ---- payments -------------------------------------------------------

    /** @param placedAt the order's placement time; the service adds the payment timeout */
    public record PaymentRow(long id, long salesOrderId, String orderNumber, Party buyer,
            BigDecimal amount, String status, String method, String referenceCode,
            String slipImageKey, String failureReason, OffsetDateTime createdAt,
            OffsetDateTime placedAt, OffsetDateTime verifiedAt) {
    }

    /** Oldest first: the order closest to its payment deadline is checked first. */
    public List<PaymentRow> payments(String status) {
        return dsl.select(PAYMENT.ID, PAYMENT.SALES_ORDER_ID, SALES_ORDER.ORDER_NUMBER,
                        BUYER.ID, BUYER.USERNAME, BUYER.DISPLAY_NAME, BUYER.AVATAR_URL, PAYMENT.AMOUNT,
                        PAYMENT.STATUS, PAYMENT.METHOD, PAYMENT.REFERENCE_CODE,
                        PAYMENT.SLIP_IMAGE_KEY, PAYMENT.FAILURE_REASON, PAYMENT.CREATED_AT,
                        SALES_ORDER.PLACED_AT, PAYMENT.VERIFIED_AT)
                .from(PAYMENT)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(PAYMENT.SALES_ORDER_ID))
                .join(BUYER).on(BUYER.ID.eq(SALES_ORDER.BUYER_ID))
                .where(PAYMENT.STATUS.eq(status))
                .orderBy(PAYMENT.CREATED_AT.asc(), PAYMENT.ID.asc())
                .fetch(r -> new PaymentRow(r.get(PAYMENT.ID), r.get(PAYMENT.SALES_ORDER_ID),
                        r.get(SALES_ORDER.ORDER_NUMBER),
                        buyer(r),
                        r.get(PAYMENT.AMOUNT), r.get(PAYMENT.STATUS), r.get(PAYMENT.METHOD),
                        r.get(PAYMENT.REFERENCE_CODE), r.get(PAYMENT.SLIP_IMAGE_KEY),
                        r.get(PAYMENT.FAILURE_REASON), r.get(PAYMENT.CREATED_AT),
                        r.get(SALES_ORDER.PLACED_AT), r.get(PAYMENT.VERIFIED_AT)));
    }

    /** Sub-orders still part of each order, keyed by sales order; cancelled shops drop out. */
    public Map<Long, List<SellerSplit>> splits(Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) return Map.of();
        return dsl.select(SELLER_ORDER.SALES_ORDER_ID, SELLER_ORDER.ID,
                        SELLER_ORDER.SELLER_ORDER_NUMBER, SELLER.ID, SELLER_ACCOUNT.USERNAME,
                        SELLER_ACCOUNT.DISPLAY_NAME, SELLER_ACCOUNT.AVATAR_URL, SELLER_ORDER.GRAND_TOTAL)
                .from(SELLER_ORDER)
                .join(SELLER).on(SELLER.ID.eq(SELLER_ORDER.SELLER_PROFILE_ID))
                .join(SELLER_ACCOUNT).on(SELLER_ACCOUNT.ID.eq(SELLER.USER_ID))
                .where(SELLER_ORDER.SALES_ORDER_ID.in(salesOrderIds))
                .and(SELLER_ORDER.STATUS.ne("CANCELLED"))
                .orderBy(SELLER_ORDER.ID.asc())
                .fetchGroups(r -> r.get(SELLER_ORDER.SALES_ORDER_ID),
                        r -> new SellerSplit(r.get(SELLER_ORDER.ID),
                                r.get(SELLER_ORDER.SELLER_ORDER_NUMBER),
                                seller(r, r.get(SELLER.ID)),
                                r.get(SELLER_ORDER.GRAND_TOTAL)));
    }

    /** Same sum {@link PaymentRepository#unpaidTotal} checks before approving, for many orders. */
    public Map<Long, BigDecimal> unpaidTotals(Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) return Map.of();
        return dsl.select(SELLER_ORDER.SALES_ORDER_ID, DSL.sum(SELLER_ORDER.GRAND_TOTAL))
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.in(salesOrderIds))
                .and(SELLER_ORDER.STATUS.eq("PENDING_PAYMENT"))
                .groupBy(SELLER_ORDER.SALES_ORDER_ID)
                .fetchMap(SELLER_ORDER.SALES_ORDER_ID, DSL.sum(SELLER_ORDER.GRAND_TOTAL));
    }

    public PaymentSummary paymentSummary(OffsetDateTime dayStart) {
        var verifiedToday = PAYMENT.VERIFIED_AT.ge(dayStart).and(PAYMENT.VERIFIED_BY.isNotNull());
        var r = dsl.select(
                        DSL.count().filterWhere(PAYMENT.STATUS.eq("AWAITING_VERIFICATION")),
                        DSL.count().filterWhere(PAYMENT.STATUS.eq("SUCCEEDED").and(verifiedToday)),
                        DSL.count().filterWhere(PAYMENT.STATUS.eq("FAILED").and(verifiedToday)),
                        DSL.count().filterWhere(PAYMENT.STATUS.eq("PENDING")))
                .from(PAYMENT)
                .fetchSingle();
        return new PaymentSummary(r.value1(), r.value2(), r.value3(), r.value4());
    }

    // ---- refunds --------------------------------------------------------

    private static final Field<Integer> FAILED_ATTEMPTS = DSL.field(
            DSL.selectCount().from(REFUND_TRANSFER_ATTEMPT)
                    .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(REFUND.ID))
                    .and(REFUND_TRANSFER_ATTEMPT.STATUS.eq("FAILED")))
            .as("failed_attempts");

    /** Used inside a condition, so it stays unaliased — an alias would render as a bare column name. */
    private static final Field<String> LAST_ATTEMPT_STATUS = DSL.field(
            DSL.select(REFUND_TRANSFER_ATTEMPT.STATUS).from(REFUND_TRANSFER_ATTEMPT)
                    .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(REFUND.ID))
                    .orderBy(REFUND_TRANSFER_ATTEMPT.STARTED_AT.desc(), REFUND_TRANSFER_ATTEMPT.ID.desc())
                    .limit(1));

    private static final Field<String> LAST_FAILURE_REASON = DSL.field(
            DSL.select(REFUND_TRANSFER_ATTEMPT.FAILURE_REASON).from(REFUND_TRANSFER_ATTEMPT)
                    .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(REFUND.ID))
                    .and(REFUND_TRANSFER_ATTEMPT.STATUS.eq("FAILED"))
                    .orderBy(REFUND_TRANSFER_ATTEMPT.STARTED_AT.desc(), REFUND_TRANSFER_ATTEMPT.ID.desc())
                    .limit(1))
            .as("last_failure_reason");

    /**
     * @param status one refund status, or null for every refund newest first; a
     *               single status reads oldest first, the order an admin works it
     */
    public List<AdminRefund> refunds(String status) {
        var query = dsl.select(REFUND.ID, REFUND.SALES_ORDER_ID, SALES_ORDER.ORDER_NUMBER,
                        REFUND.SELLER_ORDER_ID, SELLER_ORDER.SELLER_ORDER_NUMBER,
                        REFUND.RETURN_REQUEST_ID, RETURN_REQUEST.RESOLUTION,
                        BUYER.ID, BUYER.USERNAME, BUYER.DISPLAY_NAME, BUYER.AVATAR_URL,
                        SELLER.ID, SELLER_ACCOUNT.USERNAME, SELLER_ACCOUNT.DISPLAY_NAME,
                        SELLER_ACCOUNT.AVATAR_URL,
                        REFUND.AMOUNT, REFUND.REASON, REFUND.STATUS, REFUND.METHOD,
                        REFUND.BANK_NAME, REFUND.ACCOUNT_NAME, REFUND.ACCOUNT_NUMBER,
                        REFUND.TRANSFER_REFERENCE, ESCROW_HOLD.AMOUNT, ESCROW_HOLD.REFUNDED_AMOUNT,
                        FAILED_ATTEMPTS, LAST_FAILURE_REASON, REFUND.REQUESTED_AT, REFUND.PROCESSED_AT)
                .from(REFUND)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(REFUND.SALES_ORDER_ID))
                .join(BUYER).on(BUYER.ID.eq(SALES_ORDER.BUYER_ID))
                .leftJoin(SELLER_ORDER).on(SELLER_ORDER.ID.eq(REFUND.SELLER_ORDER_ID))
                .leftJoin(SELLER).on(SELLER.ID.eq(SELLER_ORDER.SELLER_PROFILE_ID))
                .leftJoin(SELLER_ACCOUNT).on(SELLER_ACCOUNT.ID.eq(SELLER.USER_ID))
                .leftJoin(ESCROW_HOLD).on(ESCROW_HOLD.SELLER_ORDER_ID.eq(REFUND.SELLER_ORDER_ID))
                .leftJoin(RETURN_REQUEST).on(RETURN_REQUEST.ID.eq(REFUND.RETURN_REQUEST_ID));
        var filtered = status == null
                ? query.orderBy(REFUND.REQUESTED_AT.desc(), REFUND.ID.desc())
                : query.where(REFUND.STATUS.eq(status)).orderBy(REFUND.REQUESTED_AT.asc(), REFUND.ID.asc());
        return filtered.fetch(r -> new AdminRefund(r.get(REFUND.ID), r.get(REFUND.SALES_ORDER_ID),
                r.get(SALES_ORDER.ORDER_NUMBER), r.get(REFUND.SELLER_ORDER_ID),
                r.get(SELLER_ORDER.SELLER_ORDER_NUMBER), r.get(REFUND.RETURN_REQUEST_ID),
                r.get(RETURN_REQUEST.RESOLUTION),
                buyer(r),
                r.get(SELLER.ID) == null ? null : seller(r, r.get(SELLER.ID)),
                r.get(REFUND.AMOUNT), r.get(REFUND.REASON), r.get(REFUND.STATUS), r.get(REFUND.METHOD),
                r.get(REFUND.BANK_NAME), r.get(REFUND.ACCOUNT_NAME), r.get(REFUND.ACCOUNT_NUMBER),
                r.get(REFUND.TRANSFER_REFERENCE), r.get(ESCROW_HOLD.AMOUNT),
                r.get(ESCROW_HOLD.REFUNDED_AMOUNT), r.get(FAILED_ATTEMPTS),
                r.get(LAST_FAILURE_REASON), r.get(REFUND.REQUESTED_AT), r.get(REFUND.PROCESSED_AT)));
    }

    public RefundSummary refundSummary(OffsetDateTime monthStart) {
        var processedThisMonth = REFUND.STATUS.eq("PROCESSED").and(REFUND.PROCESSED_AT.ge(monthStart));
        var r = dsl.select(
                        DSL.count().filterWhere(REFUND.STATUS.eq("APPROVED")),
                        DSL.count().filterWhere(REFUND.STATUS.eq("PROCESSING")),
                        DSL.count().filterWhere(REFUND.STATUS.eq("APPROVED")
                                .and(LAST_ATTEMPT_STATUS.eq("FAILED"))),
                        DSL.count().filterWhere(processedThisMonth),
                        DSL.sum(DSL.when(processedThisMonth, REFUND.AMOUNT)))
                .from(REFUND)
                .fetchSingle();
        return new RefundSummary(r.value1(), r.value2(), r.value3(), r.value4(),
                r.value5() == null ? BigDecimal.ZERO : r.value5());
    }

    // ---- payouts --------------------------------------------------------

    /** Every open payout of the same seller, matching {@link WalletRepository#reservedForPayout}. */
    private static final Field<BigDecimal> RESERVED = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(PAYOUT.as("open").AMOUNT), BigDecimal.ZERO))
                    .from(PAYOUT.as("open"))
                    .where(PAYOUT.as("open").SELLER_PROFILE_ID.eq(PAYOUT.SELLER_PROFILE_ID))
                    .and(PAYOUT.as("open").STATUS.in("REQUESTED", "APPROVED", "PROCESSING")))
            .as("reserved");

    /** A payout row before its orders are attached; {@code sellerProfileId} drives the preview. */
    public record PayoutRow(long id, long sellerProfileId, Party seller, BigDecimal amount,
            String currency, String status, PayoutAccount account, WalletSnapshot wallet,
            OffsetDateTime requestedAt, OffsetDateTime approvedAt, String approvedBy,
            OffsetDateTime paidAt, String transferReference, String rejectionReason) {
    }

    /** @param status one payout status, or null for all; oldest request first either way */
    public List<PayoutRow> payouts(String status) {
        var query = dsl.select(PAYOUT.ID, PAYOUT.SELLER_PROFILE_ID, SELLER_ACCOUNT.USERNAME,
                        SELLER_ACCOUNT.DISPLAY_NAME, SELLER_ACCOUNT.AVATAR_URL, PAYOUT.AMOUNT, PAYOUT.CURRENCY, PAYOUT.STATUS,
                        SELLER_PAYOUT_ACCOUNT.BANK_NAME, SELLER_PAYOUT_ACCOUNT.ACCOUNT_NAME,
                        SELLER_PAYOUT_ACCOUNT.ACCOUNT_NUMBER, SELLER_PAYOUT_ACCOUNT.VERIFIED_AT,
                        WALLET.BALANCE_AVAILABLE, WALLET.BALANCE_PENDING, RESERVED,
                        PAYOUT.REQUESTED_AT, PAYOUT.APPROVED_AT, APPROVER.DISPLAY_NAME,
                        PAYOUT.PAID_AT, PAYOUT.TRANSFER_REFERENCE, PAYOUT.REJECTION_REASON)
                .from(PAYOUT)
                .join(SELLER).on(SELLER.ID.eq(PAYOUT.SELLER_PROFILE_ID))
                .join(SELLER_ACCOUNT).on(SELLER_ACCOUNT.ID.eq(SELLER.USER_ID))
                .join(WALLET).on(WALLET.ID.eq(PAYOUT.WALLET_ID))
                .leftJoin(SELLER_PAYOUT_ACCOUNT).on(SELLER_PAYOUT_ACCOUNT.ID.eq(PAYOUT.PAYOUT_ACCOUNT_ID))
                .leftJoin(APPROVER).on(APPROVER.ID.eq(PAYOUT.APPROVED_BY));
        return (status == null ? query : query.where(PAYOUT.STATUS.eq(status)))
                .orderBy(PAYOUT.REQUESTED_AT.asc(), PAYOUT.ID.asc())
                .fetch(r -> new PayoutRow(r.get(PAYOUT.ID), r.get(PAYOUT.SELLER_PROFILE_ID),
                        seller(r, r.get(PAYOUT.SELLER_PROFILE_ID)),
                        r.get(PAYOUT.AMOUNT), r.get(PAYOUT.CURRENCY), r.get(PAYOUT.STATUS),
                        r.get(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NUMBER) == null ? null : new PayoutAccount(
                                r.get(SELLER_PAYOUT_ACCOUNT.BANK_NAME), r.get(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NAME),
                                r.get(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NUMBER), r.get(SELLER_PAYOUT_ACCOUNT.VERIFIED_AT)),
                        new WalletSnapshot(r.get(WALLET.BALANCE_AVAILABLE), r.get(WALLET.BALANCE_PENDING),
                                r.get(RESERVED)),
                        r.get(PAYOUT.REQUESTED_AT), r.get(PAYOUT.APPROVED_AT), r.get(APPROVER.DISPLAY_NAME),
                        r.get(PAYOUT.PAID_AT), r.get(PAYOUT.TRANSFER_REFERENCE), r.get(PAYOUT.REJECTION_REASON)));
    }

    /** Sub-orders already settled by each payout, keyed by payout id. */
    public Map<Long, List<PayoutOrder>> linkedOrders(Collection<Long> payoutIds) {
        if (payoutIds.isEmpty()) return Map.of();
        return dsl.select(SELLER_ORDER.PAYOUT_ID, SELLER_ORDER.ID, SELLER_ORDER.SELLER_ORDER_NUMBER,
                        SELLER_ORDER.SELLER_NET_AMOUNT, SELLER_ORDER.COMPLETED_AT)
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.PAYOUT_ID.in(payoutIds))
                .orderBy(SELLER_ORDER.COMPLETED_AT.asc(), SELLER_ORDER.ID.asc())
                .fetchGroups(r -> r.get(SELLER_ORDER.PAYOUT_ID),
                        r -> new PayoutOrder(r.get(SELLER_ORDER.ID), r.get(SELLER_ORDER.SELLER_ORDER_NUMBER),
                                r.get(SELLER_ORDER.SELLER_NET_AMOUNT), r.get(SELLER_ORDER.COMPLETED_AT)));
    }

    public PayoutSummary payoutSummary(OffsetDateTime monthStart) {
        var r = dsl.select(
                        DSL.count().filterWhere(PAYOUT.STATUS.eq("REQUESTED")),
                        DSL.count().filterWhere(PAYOUT.STATUS.in("APPROVED", "PROCESSING")),
                        DSL.sum(DSL.when(PAYOUT.STATUS.eq("PAID").and(PAYOUT.PAID_AT.ge(monthStart)),
                                PAYOUT.AMOUNT)),
                        DSL.count().filterWhere(PAYOUT.STATUS.in("REJECTED", "FAILED")
                                .and(PAYOUT.REQUESTED_AT.ge(monthStart))))
                .from(PAYOUT)
                .fetchSingle();
        return new PayoutSummary(r.value1(), r.value2(),
                r.value3() == null ? BigDecimal.ZERO : r.value3(), r.value4());
    }
}
