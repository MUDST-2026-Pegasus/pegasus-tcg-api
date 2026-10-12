package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;

import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final DSLContext dsl;

    public PaymentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SalesOrderRecord> lockOrder(long orderId) {
        return dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(orderId))
                .forUpdate().fetchOptional();
    }

    public Optional<SalesOrderRecord> findOrder(long orderId) {
        return dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(orderId)).fetchOptional();
    }

    public Optional<PaymentRecord> find(long id) {
        return dsl.selectFrom(PAYMENT).where(PAYMENT.ID.eq(id)).fetchOptional();
    }

    public Optional<PaymentRecord> byKey(String key) {
        return dsl.selectFrom(PAYMENT).where(PAYMENT.IDEMPOTENCY_KEY.eq(key)).fetchOptional();
    }

    public Optional<PaymentRecord> activeForOrder(long orderId) {
        return dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(orderId))
                .and(PAYMENT.STATUS.in("PENDING", "AWAITING_VERIFICATION", "SUCCEEDED"))
                .orderBy(PAYMENT.ID.desc()).limit(1).fetchOptional();
    }

    public Optional<PaymentRecord> succeededForOrder(long orderId) {
        return dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(orderId))
                .and(PAYMENT.STATUS.eq("SUCCEEDED")).fetchOptional();
    }

    public void cancelPendingWhenNoUnpaidSuborders(long orderId, boolean expired) {
        if (unpaidTotal(orderId).signum() == 0) {
            dsl.update(PAYMENT).set(PAYMENT.STATUS, expired ? "EXPIRED" : "CANCELLED")
                    .where(PAYMENT.SALES_ORDER_ID.eq(orderId))
                    .and(PAYMENT.STATUS.eq("PENDING"))
                    .execute();
        }
    }

    public PaymentRecord insert(long orderId, String method, BigDecimal amount, String key) {
        return dsl.insertInto(PAYMENT)
                .set(PAYMENT.SALES_ORDER_ID, orderId).set(PAYMENT.METHOD, method)
                .set(PAYMENT.AMOUNT, amount).set(PAYMENT.IDEMPOTENCY_KEY, key)
                .returning().fetchSingle();
    }

    public int attachSlip(long id, String slipKey, String referenceCode) {
        return dsl.update(PAYMENT).set(PAYMENT.SLIP_IMAGE_KEY, slipKey)
                .set(PAYMENT.REFERENCE_CODE, referenceCode)
                .set(PAYMENT.STATUS, "AWAITING_VERIFICATION")
                .where(PAYMENT.ID.eq(id))
                .and(PAYMENT.STATUS.in("PENDING", "AWAITING_VERIFICATION"))
                .execute();
    }

    public boolean slipUsedElsewhere(String slipKey, long id) {
        return dsl.fetchExists(dsl.selectOne().from(PAYMENT)
                .where(PAYMENT.SLIP_IMAGE_KEY.eq(slipKey)).and(PAYMENT.ID.ne(id)));
    }

    public int succeed(long id, Long adminId, OffsetDateTime now) {
        return dsl.update(PAYMENT).set(PAYMENT.STATUS, "SUCCEEDED")
                .set(PAYMENT.PAID_AT, now).set(PAYMENT.VERIFIED_AT, now)
                .set(PAYMENT.VERIFIED_BY, adminId)
                .where(PAYMENT.ID.eq(id)).and(PAYMENT.STATUS.in("PENDING", "AWAITING_VERIFICATION"))
                .execute();
    }

    public int reject(long id, long adminId, String reason, OffsetDateTime now) {
        return dsl.update(PAYMENT).set(PAYMENT.STATUS, "FAILED")
                .set(PAYMENT.FAILURE_REASON, reason)
                .set(PAYMENT.VERIFIED_BY, adminId).set(PAYMENT.VERIFIED_AT, now)
                .where(PAYMENT.ID.eq(id)).and(PAYMENT.STATUS.eq("AWAITING_VERIFICATION"))
                .execute();
    }

    public List<PaymentRecord> queue(String status) {
        return dsl.selectFrom(PAYMENT).where(PAYMENT.STATUS.eq(status))
                .orderBy(PAYMENT.CREATED_AT.asc(), PAYMENT.ID.asc()).fetch();
    }

    public List<SellerOrderRecord> unpaidSuborders(long orderId) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))
                .and(SELLER_ORDER.STATUS.eq("PENDING_PAYMENT"))
                .orderBy(SELLER_ORDER.ID.asc()).fetch();
    }

    public BigDecimal unpaidTotal(long orderId) {
        return dsl.select(DSL.coalesce(DSL.sum(SELLER_ORDER.GRAND_TOTAL), BigDecimal.ZERO))
                .from(SELLER_ORDER).where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))
                .and(SELLER_ORDER.STATUS.eq("PENDING_PAYMENT"))
                .fetchSingle(0, BigDecimal.class);
    }
}
