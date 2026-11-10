package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;

import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.util.List;

/** Order transitions executed inside the facade transaction. */
final class OrderPaymentLifecycleService {
    private final OrderTransitionSupport support;
    private final AuctionRepository auctionRepository;
    private final OrderSettlementService settlement;
    private final OrderResponseMapper mapper;

    OrderPaymentLifecycleService(OrderTransitionSupport support, AuctionRepository auctionRepository, OrderSettlementService settlement, OrderResponseMapper mapper) {
        this.support = support;
        this.auctionRepository = auctionRepository;
        this.settlement = settlement;
        this.mapper = mapper;
    }

    OrderDetailsResponse markOrderPaid(AuthPrincipal principal, long orderId) {
        support.requireAuthenticated(principal);
        SalesOrderRecord salesOrder = support.orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if (!"PENDING_PAYMENT".equals(salesOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        markOrderPaidBySystem(salesOrder.getId(), principal.userId());
        return mapper.toOrderDetailsResponse(salesOrder.getId());
    }

    void markOrderPaidBySystem(long orderId, Long actorUserId) {
        SalesOrderRecord salesOrder = support.orderRepository.findSalesOrderById(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if (!"PENDING_PAYMENT".equals(salesOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        List<SellerOrderRecord> sellerOrders = support.orderQueries.findSellerOrdersBySalesOrderId(salesOrder.getId());
        for (SellerOrderRecord so : sellerOrders) {
            if ("PENDING_PAYMENT".equals(so.getStatus())) {
                support.orderTransitions.updateSellerOrderStatus(
                        so.getId(),
                        List.of("PENDING_PAYMENT"),
                        "PAID",
                        null, null, null, null, null, null);
                support.orderTransitions.insertStatusHistory(so.getId(), "PENDING_PAYMENT", "PAID", actorUserId, "Payment completed");
            }
        }

        settlement.rollupSalesOrderStatus(salesOrder.getId());
        if (salesOrder.getAuctionId() != null) {
            auctionRepository.markSoldForOrder(salesOrder.getAuctionId(), salesOrder.getId());
        }
    }

    void transitionAfterSales(long sellerOrderId, SellerOrderStatus target, Long actorUserId,
            String reason) {
        SellerOrderRecord order = support.orderRepository.findSellerOrderById(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        SellerOrderStatus current = SellerOrderStatus.of(order.getStatus());
        if (!current.canMoveTo(target)) throw new ConflictException(ErrorCode.RETURN_STATE);
        if (support.orderTransitions.updateSellerOrderStatus(sellerOrderId, List.of(current.name()), target.name(),
                null, null, null, target == SellerOrderStatus.COMPLETED ? support.now() : null, null, null) != 1) {
            throw new ConflictException(ErrorCode.RETURN_STATE);
        }
        support.orderTransitions.insertStatusHistory(sellerOrderId, current.name(), target.name(), actorUserId, reason);
        settlement.rollupSalesOrderStatus(order.getSalesOrderId());
    }
}
