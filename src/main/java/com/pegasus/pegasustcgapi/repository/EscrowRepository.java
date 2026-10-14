package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;

import com.pegasus.pegasustcgapi.jooq.tables.records.EscrowHoldRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class EscrowRepository {

    private final DSLContext dsl;

    public EscrowRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void hold(long sellerOrderId, long platformWalletId, BigDecimal amount) {
        dsl.insertInto(ESCROW_HOLD)
                .set(ESCROW_HOLD.SELLER_ORDER_ID, sellerOrderId)
                .set(ESCROW_HOLD.WALLET_ID, platformWalletId)
                .set(ESCROW_HOLD.AMOUNT, amount)
                .execute();
    }

    public Optional<EscrowHoldRecord> lock(long sellerOrderId) {
        return dsl.selectFrom(ESCROW_HOLD)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId))
                .forUpdate().fetchOptional();
    }

    public int release(long id, BigDecimal amount, String status, OffsetDateTime now) {
        return dsl.update(ESCROW_HOLD)
                .set(ESCROW_HOLD.RELEASED_AMOUNT, amount)
                .set(ESCROW_HOLD.STATUS, status)
                .set(ESCROW_HOLD.RELEASED_AT, now)
                .where(ESCROW_HOLD.ID.eq(id)).and(ESCROW_HOLD.STATUS.eq("HELD"))
                .execute();
    }

    public void schedule(long sellerOrderId, OffsetDateTime dueAt) {
        dsl.update(ESCROW_HOLD).set(ESCROW_HOLD.RELEASE_DUE_AT, dueAt)
                .where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId)).execute();
    }

    public void refund(long id, BigDecimal refunded, String status) {
        dsl.update(ESCROW_HOLD).set(ESCROW_HOLD.REFUNDED_AMOUNT, refunded)
                .set(ESCROW_HOLD.STATUS, status)
                .where(ESCROW_HOLD.ID.eq(id)).and(ESCROW_HOLD.STATUS.eq("HELD"))
                .execute();
    }
}
