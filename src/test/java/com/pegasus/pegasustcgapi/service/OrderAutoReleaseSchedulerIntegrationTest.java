package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.job.OrderAutoReleaseScheduler;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@Import(OrderAutoReleaseSchedulerIntegrationTest.TestClockConfig.class)
@DisplayName("OrderAutoReleaseSchedulerIntegrationTest — Escrow Auto-Release Scheduler")
class OrderAutoReleaseSchedulerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DSLContext dsl;

    @Autowired
    private OrderAutoReleaseScheduler scheduler;

    @Autowired
    private MutableTestClock testClock;

    @MockitoSpyBean
    private LedgerPort ledgerPort;

    private long buyerId;
    private long sellerProfileId;

    public static class MutableTestClock extends Clock {
        private Instant instant;
        private ZoneId zone = ZoneOffset.UTC;

        public MutableTestClock(Instant initialInstant) {
            this.instant = initialInstant;
        }

        public void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        public void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableTestClock copy = new MutableTestClock(this.instant);
            copy.zone = zone;
            return copy;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public MutableTestClock mutableTestClock() {
            return new MutableTestClock(Instant.now());
        }
    }

    @BeforeEach
    void setUp() {
        String tag = Long.toString(System.nanoTime(), 36);

        long sellerUserId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "seller_" + tag + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "seller_" + tag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Seller " + tag)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        sellerProfileId = dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, sellerUserId)
                .set(SELLER_PROFILE.STATUS, "VERIFIED")
                .set(SELLER_PROFILE.VERIFIED_AT, OffsetDateTime.now())
                .returningResult(SELLER_PROFILE.ID)
                .fetchSingle(SELLER_PROFILE.ID);

        buyerId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "buyer_" + tag + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "buyer_" + tag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Buyer " + tag)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        testClock.setInstant(Instant.now());
    }

    @Test
    @DisplayName("processAutoReleases completes overdue SHIPPED order and triggers escrow payout exactly once")
    void processAutoReleases_OverdueOrder_CompletesAndReleasesEscrowOnce() {
        String tag = Long.toString(System.nanoTime(), 36);

        // 1. Seed sales_order
        long salesOrderId = dsl.insertInto(SALES_ORDER)
                .set(SALES_ORDER.ORDER_NUMBER, "ORD-SCHED-" + tag)
                .set(SALES_ORDER.BUYER_ID, buyerId)
                .set(SALES_ORDER.STATUS, "PAID")
                .set(SALES_ORDER.CURRENCY, "THB")
                .set(SALES_ORDER.ITEMS_SUBTOTAL, new BigDecimal("100.00"))
                .set(SALES_ORDER.SHIPPING_TOTAL, new BigDecimal("10.00"))
                .set(SALES_ORDER.DISCOUNT_TOTAL, BigDecimal.ZERO)
                .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("110.00"))
                .set(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, JSONB.valueOf("{}"))
                .set(SALES_ORDER.IDEMPOTENCY_KEY, "key-sched-" + tag)
                .returningResult(SALES_ORDER.ID)
                .fetchSingle(SALES_ORDER.ID);

        // 2. Seed seller_order in SHIPPED status with auto_complete_at set 7 days in future
        OffsetDateTime now = OffsetDateTime.now(testClock);
        OffsetDateTime shippedAt = now.minusDays(1);
        OffsetDateTime autoCompleteAt = shippedAt.plusDays(7);

        long sellerOrderId = dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, salesOrderId)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, "SO-SCHED-" + tag)
                .set(SELLER_ORDER.STATUS, "SHIPPED")
                .set(SELLER_ORDER.ITEMS_SUBTOTAL, new BigDecimal("100.00"))
                .set(SELLER_ORDER.SHIPPING_FEE, new BigDecimal("10.00"))
                .set(SELLER_ORDER.DISCOUNT_AMOUNT, BigDecimal.ZERO)
                .set(SELLER_ORDER.GRAND_TOTAL, new BigDecimal("110.00"))
                .set(SELLER_ORDER.COMMISSION_AMOUNT, new BigDecimal("11.00"))
                .set(SELLER_ORDER.SELLER_NET_AMOUNT, new BigDecimal("99.00"))
                .set(SELLER_ORDER.SHIPPED_AT, shippedAt)
                .set(SELLER_ORDER.AUTO_COMPLETE_AT, autoCompleteAt)
                .returningResult(SELLER_ORDER.ID)
                .fetchSingle(SELLER_ORDER.ID);

        // Before advancing time: run scheduler, should NOT auto-complete
        scheduler.processAutoReleases();

        SellerOrderRecord orderBeforeAdvance = dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(sellerOrderId))
                .fetchSingle();
        assertThat(orderBeforeAdvance.getStatus()).isEqualTo("SHIPPED");

        // 3. Advance controllable clock past auto_complete_at (+8 days)
        testClock.advance(Duration.ofDays(8));

        // 4. Invoke processAutoReleases() twice in succession
        scheduler.processAutoReleases();
        scheduler.processAutoReleases();

        // 5. Assertions:
        // - Order status updates to COMPLETED
        SellerOrderRecord orderAfterRuns = dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(sellerOrderId))
                .fetchSingle();
        assertThat(orderAfterRuns.getStatus()).isEqualTo("COMPLETED");
        assertThat(orderAfterRuns.getCompletedAt()).isNotNull();

        // - Parent sales_order status rollup updates to COMPLETED
        String parentStatus = dsl.select(SALES_ORDER.STATUS)
                .from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(salesOrderId))
                .fetchSingle(SALES_ORDER.STATUS);
        assertThat(parentStatus).isEqualTo("COMPLETED");

        // - Escrow payout (LedgerPort.release(...)) is called EXACTLY ONCE
        verify(ledgerPort, times(1)).release(sellerOrderId);
    }
}
