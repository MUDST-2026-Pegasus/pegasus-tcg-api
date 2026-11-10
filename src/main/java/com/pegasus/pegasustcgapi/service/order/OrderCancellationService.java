package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Order transitions executed inside the facade transaction. */
final class OrderCancellationService {
    private final OrderTransitionSupport support;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;
    private final OrderSettlementService settlement;
    private final OrderResponseMapper mapper;

    OrderCancellationService(OrderTransitionSupport support, InventoryPort inventoryPort, LedgerPort ledgerPort, OrderSettlementService settlement, OrderResponseMapper mapper) {
        this.support = support;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.settlement = settlement;
        this.mapper = mapper;
    }

    OrderDetailsResponse cancelBuyerOrder(AuthPrincipal principal, long orderId, CancelOrderRequest request) {
        support.requireAuthenticated(principal);

        // Check if orderId is a sales_order belonging to current buyer
        SalesOrderRecord salesOrder = support.orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId()).orElse(null);
        if (salesOrder != null) {
            return cancelSalesOrder(principal, salesOrder, request);
        }

        // Check if orderId is a child seller_order belonging to this buyer
        SellerOrderRecord sellerOrder = support.resolveBuyerSellerOrder(principal, orderId);
        if (sellerOrder != null) {
            SalesOrderRecord parentOrder = support.orderRepository
                    .findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
            return cancelSingleSellerOrder(principal, parentOrder, sellerOrder, request);
        }

        throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
    }

    private OrderDetailsResponse cancelSalesOrder(AuthPrincipal principal, SalesOrderRecord salesOrder, CancelOrderRequest request) {
        if ("AUCTION".equals(salesOrder.getSource())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        support.requireWithinCancelWindow(salesOrder);
        if (support.orderRepository.hasPaymentUnderReview(salesOrder.getId())) {
            throw new ConflictException(ErrorCode.PAYMENT_UNDER_REVIEW);
        }

        List<SellerOrderRecord> sellerOrders = support.orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
        boolean anyShippedOrCompleted = sellerOrders.stream()
                .anyMatch(so -> List.of("SHIPPED", "DELIVERED", "COMPLETED").contains(so.getStatus()));
        if (anyShippedOrCompleted) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        salesOrder = support.orderRepository.findSalesOrderByIdForUpdate(salesOrder.getId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if ("AUCTION".equals(salesOrder.getSource())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        support.requireWithinCancelWindow(salesOrder);
        sellerOrders = support.orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
        if (sellerOrders.stream().anyMatch(so -> List.of("SHIPPED", "DELIVERED", "COMPLETED")
                .contains(so.getStatus()))) throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        if (support.orderRepository.hasPaymentUnderReview(salesOrder.getId())) {
            throw new ConflictException(ErrorCode.PAYMENT_UNDER_REVIEW);
        }

        String reason = support.cancelReason(request);
        OffsetDateTime now = support.now();
        List<Long> allUnitsToRelease = new ArrayList<>();

        for (SellerOrderRecord so : sellerOrders) {
            if (OrderTransitionSupport.CANCELLABLE_STATUSES.contains(so.getStatus())) {
                int updated = support.orderRepository.updateSellerOrderStatus(
                        so.getId(),
                        OrderTransitionSupport.CANCELLABLE_STATUSES,
                        "CANCELLED",
                        null, null, null, null, now, reason);

                if (updated > 0) {
                    support.orderRepository.insertStatusHistory(so.getId(), so.getStatus(), "CANCELLED", principal.userId(), reason);
                    allUnitsToRelease.addAll(support.orderRepository.findUnitIdsBySellerOrderId(so.getId()));
                    ledgerPort.refundOnCancel(so.getId(), reason, principal.userId());
                }
            }
        }

        if (!allUnitsToRelease.isEmpty()) {
            inventoryPort.release(allUnitsToRelease);
        }

        settlement.rollupSalesOrderStatus(salesOrder.getId());
        return mapper.toOrderDetailsResponse(salesOrder.getId());
    }

    private OrderDetailsResponse cancelSingleSellerOrder(AuthPrincipal principal, SalesOrderRecord parentOrder, SellerOrderRecord sellerOrder, CancelOrderRequest request) {
        if ("AUCTION".equals(parentOrder.getSource())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        support.requireWithinCancelWindow(parentOrder);
        if (support.orderRepository.hasPaymentUnderReview(parentOrder.getId())) {
            throw new ConflictException(ErrorCode.PAYMENT_UNDER_REVIEW);
        }

        if (!OrderTransitionSupport.CANCELLABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        parentOrder = support.orderRepository.findSalesOrderByIdForUpdate(parentOrder.getId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if ("AUCTION".equals(parentOrder.getSource())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        support.requireWithinCancelWindow(parentOrder);
        sellerOrder = support.orderRepository.findSellerOrderById(sellerOrder.getId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if (!OrderTransitionSupport.CANCELLABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        if (support.orderRepository.hasPaymentUnderReview(parentOrder.getId())) {
            throw new ConflictException(ErrorCode.PAYMENT_UNDER_REVIEW);
        }

        String reason = support.cancelReason(request);
        OffsetDateTime now = support.now();

        int updated = support.orderRepository.updateSellerOrderStatus(
                sellerOrder.getId(),
                OrderTransitionSupport.CANCELLABLE_STATUSES,
                "CANCELLED",
                null, null, null, null, now, reason);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        support.orderRepository.insertStatusHistory(sellerOrder.getId(), sellerOrder.getStatus(), "CANCELLED", principal.userId(), reason);
        List<Long> unitIds = support.orderRepository.findUnitIdsBySellerOrderId(sellerOrder.getId());
        if (!unitIds.isEmpty()) {
            inventoryPort.release(unitIds);
        }
        ledgerPort.refundOnCancel(sellerOrder.getId(), reason, principal.userId());

        settlement.rollupSalesOrderStatus(parentOrder.getId());
        return mapper.toOrderDetailsResponse(parentOrder.getId());
    }

    SellerOrderDetailsResponse cancelSellerOrderBySeller(
            AuthPrincipal principal, long sellerOrderId, String reason) {
        support.requireAuthenticated(principal);
        SellerProfile profile = support.requireSellerProfile(principal.userId());
        SellerOrderRecord sellerOrder = support.orderRepository.findSellerOrderByIdAndSellerProfileId(sellerOrderId, profile.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!OrderTransitionSupport.CANCELLABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        if (support.orderRepository.hasPaymentUnderReview(sellerOrder.getSalesOrderId())) {
            throw new ConflictException(ErrorCode.PAYMENT_UNDER_REVIEW);
        }

        String note = "Cancelled by seller: " + reason;
        int updated = support.orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                OrderTransitionSupport.CANCELLABLE_STATUSES,
                "CANCELLED",
                null, null, null, null, support.now(), reason);
        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        support.orderRepository.insertStatusHistory(sellerOrderId, sellerOrder.getStatus(), "CANCELLED", principal.userId(), note);
        List<Long> unitIds = support.orderRepository.findUnitIdsBySellerOrderId(sellerOrderId);
        if (!unitIds.isEmpty()) {
            inventoryPort.release(unitIds);
        }
        // The bare reason, not the note: refund.reason is varchar(255) like cancel_reason.
        ledgerPort.refundOnCancel(sellerOrderId, reason, principal.userId());

        settlement.rollupSalesOrderStatus(sellerOrder.getSalesOrderId());
        return mapper.toSellerOrderDetailsResponse(sellerOrderId);
    }

    void expireUnpaidSellerOrder(long sellerOrderId) {
        SellerOrderRecord so = support.orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (so == null || !SellerOrderStatus.PENDING_PAYMENT.name().equals(so.getStatus())) {
            return;
        }
        support.orderRepository.findSalesOrderByIdForUpdate(so.getSalesOrderId());
        so = support.orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (so == null || !SellerOrderStatus.PENDING_PAYMENT.name().equals(so.getStatus())) return;
        if (support.orderRepository.hasPaymentUnderReview(so.getSalesOrderId())) return;

        String reason = "Cancelled automatically: payment was not received in time";
        int updated = support.orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                List.of(SellerOrderStatus.PENDING_PAYMENT.name()),
                "CANCELLED",
                null, null, null, null, support.now(), reason);

        if (updated == 0) {
            return;
        }

        support.orderRepository.insertStatusHistory(
                sellerOrderId, so.getStatus(), "CANCELLED", null, reason);

        List<Long> unitIds = support.orderRepository.findUnitIdsBySellerOrderId(sellerOrderId);
        if (!unitIds.isEmpty()) {
            inventoryPort.release(unitIds);
        }

        ledgerPort.refundOnCancel(sellerOrderId, reason, null);

        settlement.rollupSalesOrderStatus(so.getSalesOrderId());
    }

    boolean expireUnpaidAuctionOrder(long salesOrderId, long auctionId) {
        SalesOrderRecord sale = support.orderRepository.findSalesOrderByIdForUpdate(salesOrderId).orElse(null);
        if (sale == null || !"AUCTION".equals(sale.getSource())
                || !java.util.Objects.equals(sale.getAuctionId(), auctionId)
                || support.orderRepository.hasPaymentUnderReview(salesOrderId)) return false;

        // A buyer may have cancelled an unpaid checkout already. Its card was released then.
        if ("CANCELLED".equals(sale.getStatus())) return true;
        if (!"PENDING_PAYMENT".equals(sale.getStatus())) return false;

        List<SellerOrderRecord> children = support.orderRepository.findSellerOrdersBySalesOrderId(salesOrderId);
        if (children.size() != 1 || !"PENDING_PAYMENT".equals(children.getFirst().getStatus())) return false;

        SellerOrderRecord child = children.getFirst();
        String reason = "Cancelled automatically: auction payment was not received in time";
        int updated = support.orderRepository.updateSellerOrderStatus(child.getId(),
                List.of("PENDING_PAYMENT"), "CANCELLED", null, null, null, null, support.now(), reason);
        if (updated != 1) return false;
        support.orderRepository.insertStatusHistory(child.getId(), "PENDING_PAYMENT", "CANCELLED", null, reason);
        List<Long> unitIds = support.orderRepository.findUnitIdsBySellerOrderId(child.getId());
        if (!unitIds.isEmpty()) inventoryPort.release(unitIds);
        ledgerPort.refundOnCancel(child.getId(), reason, null);
        settlement.rollupSalesOrderStatus(salesOrderId);
        return true;
    }
}
