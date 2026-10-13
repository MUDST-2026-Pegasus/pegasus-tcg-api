package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import com.pegasus.pegasustcgapi.repository.AdminOrderRepository.AdminOrderRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the admin order pages read. Values are raw — amounts, timestamps, codes —
 * and the page words them, so the same row serves the board, the list and the
 * detail sheet.
 *
 * <p>Nothing here carries an email, phone number or address except
 * {@link Detail#shippingAddressSnapshot()}, which only the single-order view returns.
 */
public final class AdminOrderViews {

    private AdminOrderViews() {}

    /** @param id the seller's {@code seller_profile.id}, or the buyer's {@code user_account.id} */
    public record Party(long id, String username, String displayName) {
    }

    public record Tracking(String carrierName, String trackingNumber) {
    }

    /**
     * @param paidAt           when the parent order was paid; sub-orders share it
     * @param returnReasonCode the newest return request's reason, null when none was opened
     * @param reviewRating     1 to 5, null until the buyer reviews
     */
    public record Summary(
            long sellerOrderId,
            String sellerOrderNumber,
            long salesOrderId,
            String orderNumber,
            String status,
            BigDecimal grandTotal,
            int itemCount,
            Party seller,
            Party buyer,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt,
            OffsetDateTime shippedAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt,
            Tracking tracking,
            String returnReasonCode,
            Short reviewRating) {

        public static Summary from(AdminOrderRow row) {
            return new Summary(
                    row.sellerOrderId(),
                    row.sellerOrderNumber(),
                    row.salesOrderId(),
                    row.orderNumber(),
                    row.status(),
                    row.grandTotal(),
                    row.itemCount(),
                    new Party(row.sellerProfileId(), row.sellerUsername(), row.sellerDisplayName()),
                    new Party(row.buyerId(), row.buyerUsername(), row.buyerDisplayName()),
                    row.createdAt(),
                    row.paidAt(),
                    row.shippedAt(),
                    row.completedAt(),
                    row.cancelledAt(),
                    row.trackingNumber() == null
                            ? null : new Tracking(row.carrierName(), row.trackingNumber()),
                    row.returnReasonCode(),
                    row.reviewRating());
        }
    }

    /**
     * @param count every sub-order in these statuses, not just the ones in {@code items}
     */
    public record Column(String key, List<String> statuses, long count, List<Summary> items) {
    }

    /**
     * @param overduePaymentCount unpaid past {@code order.payment_timeout_minutes}; the
     *                            timeout sweep should keep it at zero
     */
    public record Alerts(long openDisputeCount, long overduePaymentCount, long paymentTimeoutMinutes) {
    }

    public record Board(List<Column> columns, Alerts alerts) {
    }

    public record Payment(long id, String method, String status, BigDecimal amount,
            OffsetDateTime paidAt, String failureReason) {

        public static Payment from(PaymentRecord row) {
            return new Payment(row.getId(), row.getMethod(), row.getStatus(), row.getAmount(),
                    row.getPaidAt(), row.getFailureReason());
        }
    }

    /**
     * @param order    the seller's view of the sub-order: items, money, shipments, history
     * @param payment  the newest payment attempt on the parent order, null before checkout pays
     */
    public record Detail(
            Summary summary,
            SellerOrderDetailsResponse order,
            String shippingAddressSnapshot,
            String buyerNote,
            Payment payment) {
    }
}
