package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Bucket;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Direction;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

class PayoutIntegrationTest extends PostgresIntegrationTest {

    @Autowired PayoutService payouts;
    @Autowired PayoutAccountRepository accounts;
    @Autowired WalletLedgerService ledger;
    @Autowired TransactionTemplate transactions;
    @Autowired WebApplicationContext context;
    @MockitoBean StorageService storage;
    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void requestReservesAndAdminPaidDebitsOnlyOnce() throws Exception {
        var seller = data.seller();
        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Test")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "TEST")
                .set(SELLER_VERIFICATION.BANK_NAME, "Test Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "test-" + data.tag())
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);
        accounts.upsert(seller.profileId(), verificationId, "TEST", "Test Bank", "Test Seller", "123456");
        long walletId = transactions.execute(status -> ledger.ensureSellerWallet(seller.userId()));
        transactions.executeWithoutResult(status -> ledger.post(walletId, Bucket.AVAILABLE,
                "SELLER_EARNING", Direction.CREDIT, new BigDecimal("200.00"),
                "SELLER_ORDER", verificationId));

        var request = payouts.request(seller.userId(), new BigDecimal("150.00"));
        assertThatThrownBy(() -> payouts.request(seller.userId(), new BigDecimal("100.00")))
                .isInstanceOf(RuntimeException.class);
        assertThat(ledger.sellerWallet(seller.userId(), 0, 10).withdrawable())
                .isEqualByComparingTo("50.00");
        payouts.approve(request.getId(), data.admin().userId());
        payouts.processing(request.getId());
        payouts.paid(request.getId(), "BANK-TRANSFER-123", "payouts/test.png");
        assertThat(ledger.sellerWallet(seller.userId(), 0, 10).balanceAvailable())
                .isEqualByComparingTo("50.00");
        assertThat(dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.REFERENCE_TYPE.eq("PAYOUT")
                .and(LEDGER_ENTRY.REFERENCE_ID.eq(request.getId())))).isEqualTo(1);
        assertThatThrownBy(() -> payouts.paid(request.getId(), "BANK-TRANSFER-123", "payouts/test.png"))
                .isInstanceOf(RuntimeException.class);
        mvc.perform(get("/api/v1/sellers/me/payouts")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(seller.userId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PAID"));
        mvc.perform(get("/api/v1/admin/payouts?status=PAID")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(data.admin().userId())))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        transactions.executeWithoutResult(status -> ledger.post(walletId, Bucket.AVAILABLE,
                "SELLER_EARNING", Direction.CREDIT, new BigDecimal("200.00"),
                "SELLER_ORDER", verificationId + 1_000_000_000L));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> {
                start.await();
                try {
                    payouts.request(seller.userId(), new BigDecimal("150.00"));
                    return true;
                } catch (com.pegasus.pegasustcgapi.exception.ConflictException expected) {
                    return false;
                }
            });
            var second = pool.submit(() -> {
                start.await();
                try {
                    payouts.request(seller.userId(), new BigDecimal("150.00"));
                    return true;
                } catch (com.pegasus.pegasustcgapi.exception.ConflictException expected) {
                    return false;
                }
            });
            start.countDown();
            assertThat((first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            assertThat(ledger.sellerWallet(seller.userId(), 0, 10).withdrawable())
                    .isEqualByComparingTo("100.00");
        } finally {
            pool.shutdownNow();
        }
    }
}
