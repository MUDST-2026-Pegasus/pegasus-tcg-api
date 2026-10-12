package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
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
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import org.jooq.JSONB;
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

    @Test
    void paidLinksCompletedSubOrdersWithinAmount() {
        var seller = data.seller();
        var buyer = data.buyer();
        var salesOrder = dsl.insertInto(SALES_ORDER)
                .set(SALES_ORDER.ORDER_NUMBER, "SO-" + data.tag())
                .set(SALES_ORDER.BUYER_ID, buyer.userId())
                .set(SALES_ORDER.STATUS, "COMPLETED")
                .set(SALES_ORDER.CURRENCY, "THB")
                .set(SALES_ORDER.ITEMS_SUBTOTAL, new BigDecimal("180.00"))
                .set(SALES_ORDER.SHIPPING_TOTAL, BigDecimal.ZERO)
                .set(SALES_ORDER.DISCOUNT_TOTAL, BigDecimal.ZERO)
                .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("180.00"))
                .set(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, JSONB.valueOf("{}"))
                .returningResult(SALES_ORDER.ID).fetchSingle(SALES_ORDER.ID);

        long order1 = dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, salesOrder)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, "SUB1-" + data.tag())
                .set(SELLER_ORDER.STATUS, "COMPLETED")
                .set(SELLER_ORDER.ITEMS_SUBTOTAL, new BigDecimal("60.00"))
                .set(SELLER_ORDER.SHIPPING_FEE, BigDecimal.ZERO)
                .set(SELLER_ORDER.DISCOUNT_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.GRAND_TOTAL, new BigDecimal("60.00"))
                .set(SELLER_ORDER.COMMISSION_RATE_PERCENT, BigDecimal.ZERO)
                .set(SELLER_ORDER.COMMISSION_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.SELLER_NET_AMOUNT, new BigDecimal("60.00"))
                .set(SELLER_ORDER.COMPLETED_AT, OffsetDateTime.now(clock).minusMinutes(10))
                .returningResult(SELLER_ORDER.ID).fetchSingle(SELLER_ORDER.ID);

        long order2 = dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, salesOrder)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, "SUB2-" + data.tag())
                .set(SELLER_ORDER.STATUS, "COMPLETED")
                .set(SELLER_ORDER.ITEMS_SUBTOTAL, new BigDecimal("70.00"))
                .set(SELLER_ORDER.SHIPPING_FEE, BigDecimal.ZERO)
                .set(SELLER_ORDER.DISCOUNT_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.GRAND_TOTAL, new BigDecimal("70.00"))
                .set(SELLER_ORDER.COMMISSION_RATE_PERCENT, BigDecimal.ZERO)
                .set(SELLER_ORDER.COMMISSION_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.SELLER_NET_AMOUNT, new BigDecimal("70.00"))
                .set(SELLER_ORDER.COMPLETED_AT, OffsetDateTime.now(clock).minusMinutes(5))
                .returningResult(SELLER_ORDER.ID).fetchSingle(SELLER_ORDER.ID);

        long order3 = dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, salesOrder)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, "SUB3-" + data.tag())
                .set(SELLER_ORDER.STATUS, "COMPLETED")
                .set(SELLER_ORDER.ITEMS_SUBTOTAL, new BigDecimal("50.00"))
                .set(SELLER_ORDER.SHIPPING_FEE, BigDecimal.ZERO)
                .set(SELLER_ORDER.DISCOUNT_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.GRAND_TOTAL, new BigDecimal("50.00"))
                .set(SELLER_ORDER.COMMISSION_RATE_PERCENT, BigDecimal.ZERO)
                .set(SELLER_ORDER.COMMISSION_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.SELLER_NET_AMOUNT, new BigDecimal("50.00"))
                .set(SELLER_ORDER.COMPLETED_AT, OffsetDateTime.now(clock))
                .returningResult(SELLER_ORDER.ID).fetchSingle(SELLER_ORDER.ID);

        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Test")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "TEST")
                .set(SELLER_VERIFICATION.BANK_NAME, "Test Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "test-link-" + data.tag())
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);
        accounts.upsert(seller.profileId(), verificationId, "TEST", "Test Bank", "Test Seller", "123456");
        long walletId = transactions.execute(status -> ledger.ensureSellerWallet(seller.userId()));
        transactions.executeWithoutResult(status -> ledger.post(walletId, Bucket.AVAILABLE,
                "SELLER_EARNING", Direction.CREDIT, new BigDecimal("200.00"),
                "SELLER_ORDER", order1));

        var req = payouts.request(seller.userId(), new BigDecimal("130.00"));
        payouts.approve(req.getId(), data.admin().userId());
        payouts.processing(req.getId());
        payouts.paid(req.getId(), "REF-LINK", "payouts/link.png");

        assertThat(dsl.select(SELLER_ORDER.PAYOUT_ID).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(order1))
                .fetchSingle(SELLER_ORDER.PAYOUT_ID)).isEqualTo(req.getId());
        assertThat(dsl.select(SELLER_ORDER.PAYOUT_ID).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(order2))
                .fetchSingle(SELLER_ORDER.PAYOUT_ID)).isEqualTo(req.getId());
        assertThat(dsl.select(SELLER_ORDER.PAYOUT_ID).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(order3))
                .fetchSingle(SELLER_ORDER.PAYOUT_ID)).isNull();
    }
}
