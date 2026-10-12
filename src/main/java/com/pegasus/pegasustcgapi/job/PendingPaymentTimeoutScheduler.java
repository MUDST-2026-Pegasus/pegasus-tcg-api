package com.pegasus.pegasustcgapi.job;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.PlatformSettingService;
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

    @Autowired
    public PendingPaymentTimeoutScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            PlatformSettingService platformSettingService,
            JobLock jobLock,
            Clock clock,
            @Value("${pegasus.order.payment-timeout-batch-size:200}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.platformSettingService = platformSettingService;
        this.jobLock = jobLock;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.batchSize = batchSize > 0 ? batchSize : 200;
    }

    public PendingPaymentTimeoutScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            PlatformSettingService platformSettingService,
            JobLock jobLock,
            Clock clock) {
        this(orderRepository, orderLifecycleService, platformSettingService, jobLock, clock, 200);
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
                } catch (Exception e) {
                    log.error("Failed to cancel unpaid seller order #{}", order.getId(), e);
                }
            }

            if (expired.size() < batchSize) {
                return;
            }
        }

        log.warn("Payment timeout sweep stopped after {} rounds of {}; the rest waits for the next tick",
                MAX_ROUNDS, batchSize);
    }
}
