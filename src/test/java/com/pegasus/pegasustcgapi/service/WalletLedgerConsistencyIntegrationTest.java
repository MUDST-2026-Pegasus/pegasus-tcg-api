package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.service.WalletLedgerService.Bucket;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Direction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class WalletLedgerConsistencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DSLContext dsl;
    @Autowired WalletLedgerService service;
    @Autowired TransactionTemplate transactions;

    @Test
    void movementIsAtomicAndLedgerMatchesWallet() {
        long userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "wallet-test@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "wallettest")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Wallet Test")
                .returningResult(USER_ACCOUNT.ID).fetchSingle(USER_ACCOUNT.ID);

        long platformId = service.platformWalletId();
        long sellerId = transactions.execute(status -> service.ensureSellerWallet(userId));
        transactions.executeWithoutResult(status -> {
            service.post(platformId, Bucket.AVAILABLE, "PAYMENT_IN", Direction.CREDIT,
                    new BigDecimal("100.00"), "PAYMENT", 1L);
            service.post(platformId, Bucket.AVAILABLE, "ESCROW_HOLD", Direction.DEBIT,
                    new BigDecimal("100.00"), "SELLER_ORDER", 1L);
            service.post(platformId, Bucket.PENDING, "ESCROW_HOLD", Direction.CREDIT,
                    new BigDecimal("100.00"), "SELLER_ORDER", 1L);
        });
        assertBalanceMatchesLedger(platformId, "100.00");

        transactions.executeWithoutResult(status -> {
            service.post(platformId, Bucket.PENDING, "ESCROW_RELEASE", Direction.DEBIT,
                    new BigDecimal("100.00"), "SELLER_ORDER", 1L);
            service.post(platformId, Bucket.AVAILABLE, "COMMISSION_FEE", Direction.CREDIT,
                    new BigDecimal("5.00"), "SELLER_ORDER", 1L);
            service.post(sellerId, Bucket.AVAILABLE, "SELLER_EARNING", Direction.CREDIT,
                    new BigDecimal("95.00"), "SELLER_ORDER", 1L);
        });
        assertBalanceMatchesLedger(platformId, "5.00");
        assertBalanceMatchesLedger(sellerId, "95.00");

        assertThatThrownBy(() -> dsl.update(LEDGER_ENTRY)
                .set(LEDGER_ENTRY.AMOUNT, new BigDecimal("1.00"))
                .where(LEDGER_ENTRY.WALLET_ID.eq(sellerId)).execute())
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> dsl.insertInto(WALLET).set(WALLET.KIND, "PLATFORM").execute())
                .isInstanceOf(RuntimeException.class);
        assertBalanceMatchesLedger(sellerId, "95.00");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                service.post(sellerId, Bucket.AVAILABLE, "SELLER_EARNING", Direction.CREDIT,
                        new BigDecimal("95.00"), "SELLER_ORDER", 1L)))
                .isInstanceOf(RuntimeException.class);
        assertBalanceMatchesLedger(sellerId, "95.00");
    }

    @Test
    void twentyConcurrentPostsHaveDistinctSequentialBalances() throws Exception {
        String tag = Long.toString(System.nanoTime(), 36);
        long userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "wallet-race-" + tag + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "wallet_race_" + tag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Wallet Race")
                .returningResult(USER_ACCOUNT.ID).fetchSingle(USER_ACCOUNT.ID);
        long walletId = transactions.execute(status -> service.ensureSellerWallet(userId));
        long referenceBase = System.nanoTime();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(20);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 20; index++) {
                long referenceId = referenceBase + index;
                jobs.add(pool.submit(() -> {
                    start.await();
                    transactions.executeWithoutResult(status -> service.post(walletId, Bucket.AVAILABLE,
                            "SELLER_EARNING", Direction.CREDIT, new BigDecimal("1.00"),
                            "SELLER_ORDER", referenceId));
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        var balances = dsl.select(LEDGER_ENTRY.BALANCE_AFTER).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.WALLET_ID.eq(walletId))
                .orderBy(LEDGER_ENTRY.BALANCE_AFTER.asc())
                .fetch(LEDGER_ENTRY.BALANCE_AFTER);
        assertThat(balances).hasSize(20);
        for (int index = 0; index < 20; index++) {
            assertThat(balances.get(index)).isEqualByComparingTo(BigDecimal.valueOf(index + 1));
        }
        assertBalanceMatchesLedger(walletId, "20.00");
    }

    private void assertBalanceMatchesLedger(long walletId, String expected) {
        BigDecimal balance = dsl.select(WALLET.BALANCE_AVAILABLE.add(WALLET.BALANCE_PENDING))
                .from(WALLET).where(WALLET.ID.eq(walletId)).fetchSingle(0, BigDecimal.class);
        BigDecimal ledgerTotal = dsl.select(DSL.coalesce(DSL.sum(DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"),
                LEDGER_ENTRY.AMOUNT).otherwise(LEDGER_ENTRY.AMOUNT.neg())), BigDecimal.ZERO))
                .from(LEDGER_ENTRY).where(LEDGER_ENTRY.WALLET_ID.eq(walletId))
                .fetchSingle(0, BigDecimal.class);
        assertThat(balance).isEqualByComparingTo(expected).isEqualByComparingTo(ledgerTotal);
    }
}
