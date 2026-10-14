package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static com.pegasus.pegasustcgapi.jooq.tables.Payout.PAYOUT;

import java.math.BigDecimal;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

    private final DSLContext dsl;

    public WalletRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Balance> findByUserId(long userId) {
        return dsl.selectFrom(WALLET).where(WALLET.USER_ID.eq(userId))
                .fetchOptional(r -> new Balance(r.getId(), r.getBalanceAvailable(), r.getBalancePending()));
    }

    public Balance lock(long walletId) {
        return dsl.selectFrom(WALLET).where(WALLET.ID.eq(walletId)).forUpdate()
                .fetchOptional(r -> new Balance(r.getId(), r.getBalanceAvailable(), r.getBalancePending()))
                .orElseThrow(() -> new IllegalStateException("Wallet not found: " + walletId));
    }

    public long platformId() {
        return dsl.select(WALLET.ID).from(WALLET).where(WALLET.KIND.eq("PLATFORM"))
                .fetchSingle(WALLET.ID);
    }

    public long ensureSeller(long userId) {
        dsl.insertInto(WALLET).set(WALLET.KIND, "SELLER").set(WALLET.USER_ID, userId)
                .onConflict(WALLET.USER_ID).doNothing().execute();
        return dsl.select(WALLET.ID).from(WALLET).where(WALLET.USER_ID.eq(userId))
                .fetchSingle(WALLET.ID);
    }

    public BigDecimal reservedForPayout(long sellerProfileId) {
        return dsl.select(DSL.coalesce(DSL.sum(PAYOUT.AMOUNT), BigDecimal.ZERO))
                .from(PAYOUT)
                .where(PAYOUT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(PAYOUT.STATUS.in("REQUESTED", "APPROVED", "PROCESSING"))
                .fetchSingle(0, BigDecimal.class);
    }

    public void update(Balance balance) {
        dsl.update(WALLET)
                .set(WALLET.BALANCE_AVAILABLE, balance.available())
                .set(WALLET.BALANCE_PENDING, balance.pending())
                .set(WALLET.VERSION, WALLET.VERSION.plus(1))
                .where(WALLET.ID.eq(balance.id()))
                .execute();
    }

    public record Balance(long id, BigDecimal available, BigDecimal pending) {
        public BigDecimal total() {
            return available.add(pending);
        }
    }
}
