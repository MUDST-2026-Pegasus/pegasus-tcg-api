package com.pegasus.pegasustcgapi.job;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled background job auto-completing orders and releasing escrow
 * after the auto-completion window has expired.
 */
@Component
public class OrderAutoReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoReleaseScheduler.class);

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderAutoReleaseScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService,
            java.time.Clock clock) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.clock = clock;
    }

    public OrderAutoReleaseScheduler(
            OrderRepository orderRepository,
            OrderLifecycleService orderLifecycleService) {
        this(orderRepository, orderLifecycleService, java.time.Clock.systemUTC());
    }

    @Scheduled(cron = "${pegasus.order.auto-release-cron:0 */5 * * * *}")
    public void runAutoRelease() {
        processAutoReleases();
    }

    public void processAutoReleases() {
        OffsetDateTime now = clock != null ? OffsetDateTime.now(clock) : OffsetDateTime.now();
        List<SellerOrderRecord> overdueOrders = orderRepository.findOverdueShippedOrders(now);
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
    }
}
