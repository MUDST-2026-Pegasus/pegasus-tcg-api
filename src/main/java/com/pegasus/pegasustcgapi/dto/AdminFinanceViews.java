package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the admin money desks read: slip review, buyer refunds and seller payouts.
 *
 * <p>Every field of the older plain views ({@code FinanceViews.Refund}, {@code FinanceViews.Payout})
 * is still here under the same name; the rest is added on top.
 *
 * <p>Each queue row carries enough context — who, which order, which bank account,
 * how the money is split — for the admin to decide without opening another page.
 * Values are raw (amounts, timestamps, status codes); the page words them.
 */
public final class AdminFinanceViews {

    private AdminFinanceViews() {}

    /** @param id the buyer's {@code user_account.id}, or the seller's {@code seller_profile.id} */
    public record Party(long id, String username, String displayName) {
    }

    /** One shop's share of a paid order; it becomes that shop's escrow hold on approval. */
    public record SellerSplit(long sellerOrderId, String sellerOrderNumber, Party seller,
            BigDecimal grandTotal) {
    }

    /**
     * @param expiresAt      when the order stops waiting for payment
     * @param unpaidTotal    what the order still owes; approval is refused unless it equals {@code amount}
     * @param splits         the order's live sub-orders, one escrow hold each
     */
    public record PaymentReview(long id, long salesOrderId, String orderNumber, Party buyer,
            BigDecimal amount, BigDecimal unpaidTotal, String status, String method,
            String referenceCode, String slipUrl, String failureReason,
            OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime verifiedAt,
            List<SellerSplit> splits) {
    }

    /** Counts for the stat strip; "today" is cut in Bangkok time. */
    public record PaymentSummary(long awaitingVerification, long approvedToday,
            long rejectedToday, long awaitingTransfer) {
    }

    /**
     * @param resolution         the return's resolution when the refund came from one, e.g. PARTIAL_REFUND
     * @param escrowAmount       the sub-order's escrow hold, null when nothing was ever held
     * @param escrowRefunded     what that hold has paid back so far — this refund included once PROCESSED
     * @param failedAttempts     transfers an admin marked as failed
     * @param lastFailureReason  why the newest failed transfer failed
     */
    public record AdminRefund(long id, long salesOrderId, String orderNumber,
            Long sellerOrderId, String sellerOrderNumber, Long returnRequestId,
            String resolution, Party buyer, Party seller, BigDecimal amount, String reason,
            String status, String method, String bankName, String accountName,
            String accountNumber, String transferReference,
            BigDecimal escrowAmount, BigDecimal escrowRefunded, int failedAttempts,
            String lastFailureReason, OffsetDateTime requestedAt, OffsetDateTime processedAt) {
    }

    /**
     * @param failedAwaitingBuyer APPROVED refunds whose newest transfer failed — waiting on the buyer's new account
     * @param processedThisMonth  refunds settled since the 1st, Bangkok time
     */
    public record RefundSummary(long ready, long processing, long failedAwaitingBuyer,
            long processedThisMonth, BigDecimal processedAmountThisMonth) {
    }

    /**
     * @param available      the seller wallet's withdrawable-before-reservations balance
     * @param reserved       every open payout of this seller, this one included
     */
    public record WalletSnapshot(BigDecimal available, BigDecimal pending, BigDecimal reserved) {
    }

    public record PayoutAccount(String bankName, String accountName, String accountNumber,
            OffsetDateTime verifiedAt) {
    }

    /** A completed sub-order the payout pays for. */
    public record PayoutOrder(long sellerOrderId, String sellerOrderNumber,
            BigDecimal sellerNetAmount, OffsetDateTime completedAt) {
    }

    /**
     * @param account      the account captured when the seller asked; null if it was since removed
     * @param approvedBy   display name of the admin who approved
     * @param orders       linked sub-orders once PAID; before that, the ones payment would link
     * @param ordersLinked true when {@code orders} is the recorded link rather than a preview
     */
    public record AdminPayout(long id, long sellerProfileId, Party seller, BigDecimal amount, String currency,
            String status, PayoutAccount account, WalletSnapshot wallet,
            OffsetDateTime requestedAt, OffsetDateTime approvedAt, String approvedBy,
            OffsetDateTime paidAt, String transferReference, String rejectionReason,
            List<PayoutOrder> orders, boolean ordersLinked) {
    }

    /**
     * @param closedThisMonth REJECTED or FAILED payouts requested since the 1st — the table
     *                        keeps no rejection time, so the request date stands in for it
     */
    public record PayoutSummary(long requested, long inProgress, BigDecimal paidAmountThisMonth,
            long closedThisMonth) {
    }
}
