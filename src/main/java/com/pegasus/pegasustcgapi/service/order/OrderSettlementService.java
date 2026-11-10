package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rolls up seller order states and settles discounts in the caller's transaction. */
final class OrderSettlementService {
    private final OrderRepository orderRepository;
    private final Clock clock;
    /** Null when the service was built without flash sales, as the unit tests do. */
    private final SaleCheckoutService saleCheckout;
    /** Null when the service was built without discount codes, as the unit tests do. */
    private final CheckoutPromotionService checkoutPromotions;

    OrderSettlementService(OrderRepository orderRepository, Clock clock,
            SaleCheckoutService saleCheckout, CheckoutPromotionService checkoutPromotions) {
        this.orderRepository = orderRepository;
        this.clock = clock;
        this.saleCheckout = saleCheckout;
        this.checkoutPromotions = checkoutPromotions;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    void rollupSalesOrderStatus(long salesOrderId) {
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdForUpdate(salesOrderId).orElse(null);
        if (salesOrder == null) {
            return;
        }

        List<SellerOrderRecord> children = orderRepository.findSellerOrdersBySalesOrderId(salesOrderId);
        if (children.isEmpty()) {
            return;
        }

        Set<SellerOrderStatus> statuses = children.stream()
                .map(child -> SellerOrderStatus.of(child.getStatus()))
                .collect(Collectors.toSet());

        String newStatus;
        OffsetDateTime completedAt = salesOrder.getCompletedAt();
        OffsetDateTime cancelledAt = salesOrder.getCancelledAt();
        OffsetDateTime paidAt = salesOrder.getPaidAt();
        OffsetDateTime now = now();

        boolean allTerminal = statuses.stream().allMatch(SellerOrderStatus::terminal);
        boolean anyCompleted = statuses.contains(SellerOrderStatus.COMPLETED);

        if (statuses.equals(Set.of(SellerOrderStatus.CANCELLED))) {
            newStatus = "CANCELLED";
            if (cancelledAt == null) {
                cancelledAt = now;
            }
        } else if (statuses.equals(Set.of(SellerOrderStatus.REFUNDED))) {
            newStatus = "REFUNDED";
        } else if (allTerminal && anyCompleted) {
            // Every sub-order has finished and at least one of them was delivered, so
            // the order as a whole is done — a sibling that was cancelled does not
            // leave it hanging at PARTIALLY_COMPLETED for good.
            newStatus = "COMPLETED";
            if (completedAt == null) {
                completedAt = now;
            }
        } else if (anyCompleted) {
            newStatus = "PARTIALLY_COMPLETED";
        } else if (allTerminal) {
            // Nothing completed and nothing is still running: a mix of cancellations
            // and refunds. Not a row in the spec's table, and PAID would be plainly
            // wrong for an order where every part was called off.
            newStatus = statuses.contains(SellerOrderStatus.CANCELLED) ? "CANCELLED" : "REFUNDED";
            if ("CANCELLED".equals(newStatus) && cancelledAt == null) {
                cancelledAt = now;
            }
        } else if (stillUnpaid(statuses)) {
            // A shop that cancelled before payment drops out; the buyer still owes
            // for the rest. Calling this PAID would block the payment and the timeout.
            newStatus = "PENDING_PAYMENT";
        } else {
            // Somewhere between paid and delivered: the buyer has paid, and the parent
            // stays PAID while the sellers pack and post.
            newStatus = "PAID";
            if (paidAt == null) {
                paidAt = now;
            }
        }

        orderRepository.updateSalesOrderStatus(salesOrderId, newStatus, paidAt, completedAt, cancelledAt);
        settlePromotions(salesOrderId, newStatus, paidAt, children);
        settleSales(paidAt, children);
    }

    /**
     * Flash quota and campaign budget follow the money the way codes do [TCG-680]:
     * a part of the order cancelled or timed out before payment gives them back;
     * once paid they stay spent, whatever happens after.
     */
    private void settleSales(OffsetDateTime paidAt, List<SellerOrderRecord> children) {
        if (saleCheckout == null || paidAt != null) {
            return;
        }
        children.stream()
                .filter(child -> SellerOrderStatus.of(child.getStatus()) == SellerOrderStatus.CANCELLED)
                .forEach(child -> saleCheckout.release(child.getId()));
    }

    /**
     * Discount code uses follow the money [TCG-569]: spent once the order is paid, given
     * back when it is called off unpaid. A shop that drops out before payment gives its
     * own code back at once. Cancelling after payment gives nothing back.
     */
    private void settlePromotions(long salesOrderId, String newStatus, OffsetDateTime paidAt,
            List<SellerOrderRecord> children) {
        if (checkoutPromotions == null) {
            return;
        }
        if (paidAt != null) {
            checkoutPromotions.consume(salesOrderId);
            return;
        }
        if ("CANCELLED".equals(newStatus)) {
            checkoutPromotions.release(salesOrderId);
            return;
        }
        children.stream()
                .filter(child -> SellerOrderStatus.of(child.getStatus()) == SellerOrderStatus.CANCELLED)
                .forEach(child -> checkoutPromotions.releaseSellerOrder(child.getId()));
    }

    /** True when every sub-order that is not cancelled or refunded is still waiting for payment. */
    private static boolean stillUnpaid(Set<SellerOrderStatus> statuses) {
        Set<SellerOrderStatus> live = EnumSet.copyOf(statuses);
        live.removeAll(EnumSet.of(SellerOrderStatus.CANCELLED, SellerOrderStatus.REFUNDED));
        return live.equals(EnumSet.of(SellerOrderStatus.PENDING_PAYMENT));
    }

}
