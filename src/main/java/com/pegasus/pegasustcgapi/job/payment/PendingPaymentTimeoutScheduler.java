package com.pegasus.pegasustcgapi.job.payment;

import com.pegasus.pegasustcgapi.job.JobLock;
import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels orders that were placed and never paid for.
 *
 * <p>Checkout holds the cards the moment the order is created, so an order nobody
 * pays for keeps them RESERVED indefinitely — the buyer never comes back, and the
 * seller cannot delist the listing while an order still claims its cards
 * (LISTING_HAS_RESERVATIONS). This puts a deadline on that, read from
 * {@code order.payment_timeout_minutes} rather than hard-coded.
 */
@Component
public class PendingPaymentTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentTimeoutScheduler.class);

    static final String LOCK_NAME = "orderPaymentTimeout";

    /** Stops a batch that keeps failing from looping forever on the same rows. */
    private static final int MAX_ROUNDS = 50;

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final PlatformSettingService platformSettingService;
    private final JobLock jobLock;
    private final Clock clock;
    private final int batchSize;
    /** Null in unit tests that build the job by hand; then nothing is logged. */
    private final AuditLogService audit;

    @Autowired
    public PendingPaymentTimeoutScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            PlatformSettingService platformSettingService,
            JobLock jobLock,
            Clock clock,
            @Value("${pegasus.order.payment-timeout-batch-size:200}") int batchSize,
            AuditLogService audit) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.platformSettingService = platformSettingService;
        this.jobLock = jobLock;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.batchSize = batchSize > 0 ? batchSize : 200;
        this.audit = audit;
    }

    public PendingPaymentTimeoutScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            PlatformSettingService platformSettingService,
            JobLock jobLock,
            Clock clock) {
        this(orderRepository, orderLifecycleService, platformSettingService, jobLock, clock, 200, null);
    }

    @Scheduled(cron = "${pegasus.order.payment-timeout-cron:0 */5 * * * *}")
    public void runPaymentTimeout() {
        processExpiredOrders();
    }

    public void processExpiredOrders() {
        jobLock.runExclusively(LOCK_NAME, this::sweep);
    }

    private void sweep() {
        Duration timeout = platformSettingService.getMinutes(
                PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES);
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(timeout);
        int cancelled = 0;
        int failed = 0;
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<SellerOrderRecord> expired =
                        orderRepository.findExpiredPendingPaymentOrders(cutoff, batchSize);
                if (expired.isEmpty()) {
                    return;
                }

                log.info("Cancelling {} orders that were never paid for", expired.size());
                for (SellerOrderRecord order : expired) {
                    try {
                        orderLifecycleService.expireUnpaidSellerOrder(order.getId());
                        cancelled++;
                    } catch (Exception e) {
                        failed++;
                        log.error("Failed to cancel unpaid seller order #{}", order.getId(), e);
                    }
                }

                if (expired.size() < batchSize) {
                    return;
                }
            }

            log.warn("Payment timeout sweep stopped after {} rounds of {}; the rest waits for the next tick",
                    MAX_ROUNDS, batchSize);
        } finally {
            recordRun(cancelled, failed);
        }
    }

    /** One row per run that cancelled anything, not one per order; a quiet tick writes nothing. */
    private void recordRun(int cancelled, int failed) {
        if (audit == null || cancelled == 0) {
            return;
        }
        try {
            audit.recordOnItsOwn(null, AuditAction.UNPAID_ORDERS_EXPIRED, LOCK_NAME,
                    "Cancelled " + cancelled + " unpaid order(s)",
                    AuditLogService.meta("cancelled", cancelled, "failed", failed));
        } catch (RuntimeException e) {
            // The orders are already cancelled; a missing log row must not hide that.
            log.error("Could not write the audit row for a payment timeout run", e);
        }
    }
}
