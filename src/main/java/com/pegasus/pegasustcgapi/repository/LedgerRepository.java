package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final DSLContext dsl;

    public LedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(long walletId, String type, String direction, BigDecimal amount,
            BigDecimal balanceAfter, String referenceType, long referenceId) {
        dsl.insertInto(LEDGER_ENTRY)
                .set(LEDGER_ENTRY.WALLET_ID, walletId)
                .set(LEDGER_ENTRY.ENTRY_TYPE, type)
                .set(LEDGER_ENTRY.DIRECTION, direction)
                .set(LEDGER_ENTRY.AMOUNT, amount)
                .set(LEDGER_ENTRY.BALANCE_AFTER, balanceAfter)
                .set(LEDGER_ENTRY.REFERENCE_TYPE, referenceType)
                .set(LEDGER_ENTRY.REFERENCE_ID, referenceId)
                .execute();
    }

    public List<Entry> page(long walletId, int size, int offset) {
        return dsl.selectFrom(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.WALLET_ID.eq(walletId))
                .orderBy(LEDGER_ENTRY.ID.desc())
                .limit(size).offset(offset)
                .fetch(r -> new Entry(r.getId(), r.getEntryType(), r.getDirection(),
                        r.getAmount(), r.getBalanceAfter(), r.getReferenceType(),
                        r.getReferenceId(), r.getCreatedAt()));
    }

    public long count(long walletId) {
        return dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.WALLET_ID.eq(walletId));
    }

    public record Entry(long id, String type, String direction, BigDecimal amount,
            BigDecimal balanceAfter, String referenceType, long referenceId,
            OffsetDateTime createdAt) {
    }
}
