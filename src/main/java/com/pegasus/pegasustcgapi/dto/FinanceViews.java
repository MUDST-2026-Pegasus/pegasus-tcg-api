package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.jooq.tables.records.PayoutRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundTransferAttemptRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ReturnRequestRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerRatingSummaryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerReviewRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** JSON-safe projections; jOOQ records include table metadata and cannot be serialized as API bodies. */
public final class FinanceViews {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private FinanceViews() {}

    public record Payout(long id, long sellerProfileId, BigDecimal amount, String currency,
            String status, OffsetDateTime requestedAt, OffsetDateTime approvedAt,
            OffsetDateTime paidAt, String transferReference, String rejectionReason) {
        public static Payout from(PayoutRecord row) {
            return new Payout(row.getId(), row.getSellerProfileId(), row.getAmount(),
                    row.getCurrency(), row.getStatus(), row.getRequestedAt(),
                    row.getApprovedAt(), row.getPaidAt(), row.getTransferReference(),
                    row.getRejectionReason());
        }
    }

    public record Refund(long id, long salesOrderId, Long sellerOrderId,
            Long returnRequestId, BigDecimal amount, String reason, String status,
            String method, String bankName, String accountName, String accountNumber,
            String transferReference, OffsetDateTime requestedAt, OffsetDateTime processedAt) {
        public static Refund from(RefundRecord row) {
            return new Refund(row.getId(), row.getSalesOrderId(), row.getSellerOrderId(),
                    row.getReturnRequestId(), row.getAmount(), row.getReason(), row.getStatus(),
                    row.getMethod(), row.getBankName(), row.getAccountName(), row.getAccountNumber(),
                    row.getTransferReference(), row.getRequestedAt(), row.getProcessedAt());
        }
    }

    public record RefundTransferAttempt(long id, long refundId, String bankName,
            String accountName, String accountNumber, String status, long startedBy,
            OffsetDateTime startedAt, Long completedBy, OffsetDateTime completedAt,
            String transferReference, String failureReason) {
        public static RefundTransferAttempt from(RefundTransferAttemptRecord row) {
            return new RefundTransferAttempt(row.getId(), row.getRefundId(), row.getBankName(),
                    row.getAccountName(), row.getAccountNumber(), row.getStatus(), row.getStartedBy(),
                    row.getStartedAt(), row.getCompletedBy(), row.getCompletedAt(), row.getTransferReference(),
                    row.getFailureReason());
        }
    }

    public record ReturnRequest(long id, long sellerOrderId, String reasonCode,
            String description, String status, String resolution, BigDecimal refundAmount,
            List<String> evidenceKeys, String returnTrackingNumber, OffsetDateTime requestedAt,
            OffsetDateTime reviewedAt, OffsetDateTime receivedAt, OffsetDateTime closedAt) {
        public static ReturnRequest from(ReturnRequestRecord row, ObjectMapper json) {
            return new ReturnRequest(row.getId(), row.getSellerOrderId(), row.getReasonCode(),
                    row.getDescription(), row.getStatus(), row.getResolution(), row.getRefundAmount(),
                    json.readValue(row.getEvidenceKeys().data(), STRING_LIST),
                    row.getReturnTrackingNumber(), row.getRequestedAt(), row.getReviewedAt(),
                    row.getReceivedAt(), row.getClosedAt());
        }
    }

    public record Review(long id, long sellerOrderId, short rating, String title,
            String comment, String replyComment, boolean visible, OffsetDateTime createdAt,
            OffsetDateTime repliedAt) {
        public static Review from(SellerReviewRecord row) {
            return new Review(row.getId(), row.getSellerOrderId(), row.getRating(), row.getTitle(),
                    row.getComment(), row.getReplyComment(), row.getIsVisible(),
                    row.getCreatedAt(), row.getRepliedAt());
        }
    }

    public record RatingSummary(int ratingCount, int ratingSum, BigDecimal ratingAvg,
            int count1, int count2, int count3, int count4, int count5) {
        public static RatingSummary from(SellerRatingSummaryRecord row) {
            return row == null ? null : new RatingSummary(row.getRatingCount(), row.getRatingSum(),
                    row.getRatingAvg(), row.getCount_1(), row.getCount_2(), row.getCount_3(),
                    row.getCount_4(), row.getCount_5());
        }
    }
}
