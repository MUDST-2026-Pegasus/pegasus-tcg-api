package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;

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

    public int setDestination(long id, String bankName, String accountName, String accountNumber) {
        return dsl.update(REFUND).set(REFUND.BANK_NAME, bankName)
                .set(REFUND.ACCOUNT_NAME, accountName)
                .set(REFUND.ACCOUNT_NUMBER, accountNumber)
                .where(REFUND.ID.eq(id)).and(REFUND.STATUS.eq("APPROVED"))
                .execute();
    }

    public List<RefundRecord> forBuyer(long buyerId) {
        return dsl.select(REFUND.fields()).from(REFUND)
                .join(SALES_ORDER).on(REFUND.SALES_ORDER_ID.eq(SALES_ORDER.ID))
                .where(SALES_ORDER.BUYER_ID.eq(buyerId))
                .orderBy(REFUND.REQUESTED_AT.desc(), REFUND.ID.desc())
                .fetchInto(RefundRecord.class);
    }

    /** @param adminId null when the system settled it, e.g. the mock gateway */
    public int processed(long id, Long adminId, String transferReference, OffsetDateTime now) {
        return dsl.update(REFUND).set(REFUND.STATUS, "PROCESSED")
                .set(REFUND.PROCESSED_BY, adminId).set(REFUND.PROCESSED_AT, now)
                .set(REFUND.TRANSFER_REFERENCE, transferReference)
                .where(REFUND.ID.eq(id)).and(REFUND.STATUS.in("PROCESSING", "APPROVED"))
                .execute();
    }

    public int transition(long id, String expected, String next) {
        return dsl.update(REFUND).set(REFUND.STATUS, next)
                .where(REFUND.ID.eq(id)).and(REFUND.STATUS.eq(expected)).execute();
    }
}
