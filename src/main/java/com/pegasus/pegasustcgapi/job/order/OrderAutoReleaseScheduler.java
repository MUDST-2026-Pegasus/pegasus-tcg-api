package com.pegasus.pegasustcgapi.job.order;

import com.pegasus.pegasustcgapi.job.JobLock;
import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
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
    /** Null in unit tests that build the job by hand; then nothing is logged. */
    private final AuditLogService audit;

    @Autowired
    public OrderAutoReleaseScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            JobLock jobLock,
            Clock clock,
            @Value("${pegasus.order.auto-release-batch-size:200}") int batchSize,
            AuditLogService audit) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.jobLock = jobLock;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.batchSize = batchSize > 0 ? batchSize : 200;
        this.audit = audit;
    }

    public OrderAutoReleaseScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            JobLock jobLock,
            Clock clock) {
        this(orderRepository, orderLifecycleService, jobLock, clock, 200, null);
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
        int completed = 0;
        int failed = 0;
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<SellerOrderRecord> overdueOrders = orderRepository.findOverdueShippedOrders(now, batchSize);
                if (overdueOrders.isEmpty()) {
                    return;
                }

                log.info("Found {} overdue orders for auto-release", overdueOrders.size());
                for (SellerOrderRecord order : overdueOrders) {
                    try {
                        orderLifecycleService.autoCompleteSellerOrder(order.getId());
                        completed++;
                        log.info("Successfully auto-completed seller order #{}", order.getId());
                    } catch (Exception e) {
                        failed++;
                        log.error("Failed to auto-complete seller order #{}", order.getId(), e);
                    }
                }

                if (overdueOrders.size() < batchSize) {
                    return;
                }
            }

            log.warn("Auto-release stopped after {} rounds of {}; the backlog will be picked up on the next tick",
                    MAX_ROUNDS, batchSize);
        } finally {
            recordRun(completed, failed);
        }
    }

    /** One row per run that completed anything, not one per order; a quiet tick writes nothing. */
    private void recordRun(int completed, int failed) {
        if (audit == null || completed == 0) {
            return;
        }
        try {
            audit.recordOnItsOwn(null, AuditAction.ORDERS_AUTO_RELEASED, LOCK_NAME,
                    "Auto-completed " + completed + " order(s) and released escrow",
                    AuditLogService.meta("completed", completed, "failed", failed));
        } catch (RuntimeException e) {
            // The orders are already completed; a missing log row must not hide that.
            log.error("Could not write the audit row for an auto-release run", e);
        }
    }
}
