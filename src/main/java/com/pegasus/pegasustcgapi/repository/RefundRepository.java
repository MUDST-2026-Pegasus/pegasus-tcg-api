package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;

import com.pegasus.pegasustcgapi.jooq.tables.records.RefundRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {

    private final DSLContext dsl;

    public RefundRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<RefundRecord> byKey(String key) {
        return dsl.selectFrom(REFUND).where(REFUND.IDEMPOTENCY_KEY.eq(key)).fetchOptional();
    }

    public RefundRecord create(long salesOrderId, long sellerOrderId, BigDecimal amount,
            String reason, String method, String key) {
        return dsl.insertInto(REFUND).set(REFUND.SALES_ORDER_ID, salesOrderId)
                .set(REFUND.SELLER_ORDER_ID, sellerOrderId).set(REFUND.AMOUNT, amount)
                .set(REFUND.REASON, reason).set(REFUND.METHOD, method)
                .set(REFUND.STATUS, "APPROVED").set(REFUND.IDEMPOTENCY_KEY, key)
                .returning().fetchSingle();
    }

    public RefundRecord createForReturn(long salesOrderId, long sellerOrderId, long returnId,
            BigDecimal amount, String reason, String method) {
        return dsl.insertInto(REFUND).set(REFUND.SALES_ORDER_ID, salesOrderId)
                .set(REFUND.SELLER_ORDER_ID, sellerOrderId)
                .set(REFUND.RETURN_REQUEST_ID, returnId)
                .set(REFUND.AMOUNT, amount).set(REFUND.REASON, reason)
                .set(REFUND.METHOD, method).set(REFUND.STATUS, "APPROVED")
                .set(REFUND.IDEMPOTENCY_KEY, "return:" + returnId)
                .returning().fetchSingle();
    }

    public Optional<RefundRecord> lock(long id) {
        return dsl.selectFrom(REFUND).where(REFUND.ID.eq(id)).forUpdate().fetchOptional();
    }

    public Optional<RefundRecord> find(long id) {
        return dsl.selectFrom(REFUND).where(REFUND.ID.eq(id)).fetchOptional();
    }

    public List<RefundRecord> queue(String status) {
        return dsl.selectFrom(REFUND).where(REFUND.STATUS.eq(status))
                .orderBy(REFUND.REQUESTED_AT.asc(), REFUND.ID.asc()).fetch();
    }

    public int processed(long id, long adminId, OffsetDateTime now) {
        return dsl.update(REFUND).set(REFUND.STATUS, "PROCESSED")
                .set(REFUND.PROCESSED_BY, adminId).set(REFUND.PROCESSED_AT, now)
                .where(REFUND.ID.eq(id)).and(REFUND.STATUS.eq("APPROVED"))
                .execute();
    }
}
