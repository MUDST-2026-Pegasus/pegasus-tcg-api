package com.pegasus.pegasustcgapi.job;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled background job auto-completing orders and releasing escrow
 * after the auto-completion window has expired.
 *
 * <p>Two things keep this safe to run on more than one replica. The sweep takes a
 * {@link JobLock}, so only one instance works through the backlog per tick — the
 * conditional update in {@code autoCompleteSellerOrder} already prevented a double
 * release, but every replica was still paying for the same queries and contending
 * on the same rows. And the backlog is read in bounded batches, so a job that has
 * been down for a day works through it in rounds instead of one heap.
 */
@Component
public class OrderAutoReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoReleaseScheduler.class);

    static final String LOCK_NAME = "orderAutoRelease";

    /** Stops a batch that keeps failing from looping forever on the same rows. */
    private static final int MAX_ROUNDS = 50;

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final JobLock jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public OrderAutoReleaseScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            JobLock jobLock,
            Clock clock,
            @Value("${pegasus.order.auto-release-batch-size:200}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.jobLock = jobLock;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.batchSize = batchSize > 0 ? batchSize : 200;
    }

    public OrderAutoReleaseScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            JobLock jobLock,
            Clock clock) {
        this(orderRepository, orderLifecycleService, jobLock, clock, 200);
    }

    @Scheduled(cron = "${pegasus.order.auto-release-cron:0 */5 * * * *}")
    public void runAutoRelease() {
        processAutoReleases();
    }

    public void processAutoReleases() {
        jobLock.runExclusively(LOCK_NAME, this::sweep);
    }

    private void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<SellerOrderRecord> overdueOrders = orderRepository.findOverdueShippedOrders(now, batchSize);
            if (overdueOrders.isEmpty()) {
                return;
            }

            log.info("Found {} overdue orders for auto-release", overdueOrders.size());
            for (SellerOrderRecord order : overdueOrders) {
                try {
                    orderLifecycleService.autoCompleteSellerOrder(order.getId());
                    log.info("Successfully auto-completed seller order #{}", order.getId());
                } catch (Exception e) {
                    log.error("Failed to auto-complete seller order #{}", order.getId(), e);
                }
            }

            if (overdueOrders.size() < batchSize) {
                return;
            }
        }

        log.warn("Auto-release stopped after {} rounds of {}; the backlog will be picked up on the next tick",
                MAX_ROUNDS, batchSize);
    }
}
