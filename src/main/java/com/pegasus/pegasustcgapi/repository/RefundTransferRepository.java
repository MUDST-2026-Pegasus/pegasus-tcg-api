package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.RefundTransferAttempt.REFUND_TRANSFER_ATTEMPT;

import com.pegasus.pegasustcgapi.jooq.tables.records.RefundRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundTransferAttemptRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class RefundTransferRepository {

    private final DSLContext dsl;

    public RefundTransferRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void start(RefundRecord refund, long adminId) {
        dsl.insertInto(REFUND_TRANSFER_ATTEMPT)
                .set(REFUND_TRANSFER_ATTEMPT.REFUND_ID, refund.getId())
                .set(REFUND_TRANSFER_ATTEMPT.BANK_NAME, refund.getBankName())
                .set(REFUND_TRANSFER_ATTEMPT.ACCOUNT_NAME, refund.getAccountName())
                .set(REFUND_TRANSFER_ATTEMPT.ACCOUNT_NUMBER, refund.getAccountNumber())
                .set(REFUND_TRANSFER_ATTEMPT.STARTED_BY, adminId)
                .execute();
    }

    public Optional<RefundTransferAttemptRecord> active(long refundId) {
        return dsl.selectFrom(REFUND_TRANSFER_ATTEMPT)
                .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(refundId))
                .and(REFUND_TRANSFER_ATTEMPT.STATUS.eq("PROCESSING"))
                .forUpdate().fetchOptional();
    }

    public List<RefundTransferAttemptRecord> history(long refundId) {
        return dsl.selectFrom(REFUND_TRANSFER_ATTEMPT)
                .where(REFUND_TRANSFER_ATTEMPT.REFUND_ID.eq(refundId))
                .orderBy(REFUND_TRANSFER_ATTEMPT.ID.desc()).fetch();
    }

    public void processed(long attemptId, long adminId, String reference, OffsetDateTime now) {
        dsl.update(REFUND_TRANSFER_ATTEMPT)
                .set(REFUND_TRANSFER_ATTEMPT.STATUS, "PROCESSED")
                .set(REFUND_TRANSFER_ATTEMPT.TRANSFER_REFERENCE, reference)
                .set(REFUND_TRANSFER_ATTEMPT.COMPLETED_BY, adminId)
                .set(REFUND_TRANSFER_ATTEMPT.COMPLETED_AT, now)
                .where(REFUND_TRANSFER_ATTEMPT.ID.eq(attemptId))
                .and(REFUND_TRANSFER_ATTEMPT.STATUS.eq("PROCESSING"))
                .execute();
    }

    public void failed(long attemptId, long adminId, String reason, OffsetDateTime now) {
        dsl.update(REFUND_TRANSFER_ATTEMPT)
                .set(REFUND_TRANSFER_ATTEMPT.STATUS, "FAILED")
                .set(REFUND_TRANSFER_ATTEMPT.FAILURE_REASON, reason)
                .set(REFUND_TRANSFER_ATTEMPT.COMPLETED_BY, adminId)
                .set(REFUND_TRANSFER_ATTEMPT.COMPLETED_AT, now)
                .where(REFUND_TRANSFER_ATTEMPT.ID.eq(attemptId))
                .and(REFUND_TRANSFER_ATTEMPT.STATUS.eq("PROCESSING"))
                .execute();
    }
}
