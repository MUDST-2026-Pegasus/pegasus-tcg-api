package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;

import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.port.collection.CollectionPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Order transitions executed inside the facade transaction. */
final class OrderFulfillmentService {
    private final OrderTransitionSupport support;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;
    private final CollectionPort collectionPort;
    private final OrderSettlementService settlement;
    private final OrderResponseMapper mapper;

    OrderFulfillmentService(OrderTransitionSupport support, InventoryPort inventoryPort, LedgerPort ledgerPort, CollectionPort collectionPort, OrderSettlementService settlement, OrderResponseMapper mapper) {
        this.support = support;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.collectionPort = collectionPort;
        this.settlement = settlement;
        this.mapper = mapper;
    }

    OrderDetailsResponse confirmBuyerOrderReceived(AuthPrincipal principal, long orderId) {
        support.requireAuthenticated(principal);

        // Resolved in the same order as cancellation: sales_order first, then a
        // sub-order of one. The two id spaces overlap, so reading them the other way
        // round here would let the same id mean a different order on each endpoint.
        SalesOrderRecord salesOrder = support.orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId()).orElse(null);
        if (salesOrder != null) {
            List<SellerOrderRecord> sellerOrders = support.orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
            List<SellerOrderRecord> eligibleOrders = sellerOrders.stream()
                    .filter(so -> OrderTransitionSupport.CONFIRMABLE_STATUSES.contains(so.getStatus()))
                    .toList();

            if (eligibleOrders.isEmpty()) {
                throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
            }

            for (SellerOrderRecord so : eligibleOrders) {
                confirmSellerOrder(principal.userId(), so);
            }

            settlement.rollupSalesOrderStatus(salesOrder.getId());
            return mapper.toOrderDetailsResponse(salesOrder.getId());
        }

        SellerOrderRecord sellerOrder = support.resolveBuyerSellerOrder(principal, orderId);
        if (sellerOrder != null) {
            SalesOrderRecord parentOrder = support.orderRepository
                    .findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
            confirmSellerOrder(principal.userId(), sellerOrder);
            settlement.rollupSalesOrderStatus(parentOrder.getId());
            return mapper.toOrderDetailsResponse(parentOrder.getId());
        }

        throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
    }

    private void confirmSellerOrder(long buyerUserId, SellerOrderRecord sellerOrder) {
        if (!OrderTransitionSupport.CONFIRMABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        OffsetDateTime now = support.now();
        int updated = support.orderRepository.updateSellerOrderStatus(
                sellerOrder.getId(),
                OrderTransitionSupport.CONFIRMABLE_STATUSES,
                "COMPLETED",
                null, now, null, now, null, null);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        support.orderRepository.insertStatusHistory(sellerOrder.getId(), sellerOrder.getStatus(), "COMPLETED", buyerUserId, "Buyer confirmed receipt");
        ledgerPort.release(sellerOrder.getId());
        collectionPort.grant(buyerUserId, sellerOrder.getId());
    }

    SellerOrderDetailsResponse shipSellerOrder(AuthPrincipal principal, long sellerOrderId, ShipOrderRequest request) {
        support.requireAuthenticated(principal);
        SellerProfile profile = support.requireSellerProfile(principal.userId());
        SellerOrderRecord sellerOrder = support.orderRepository.findSellerOrderByIdAndSellerProfileId(sellerOrderId, profile.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!OrderTransitionSupport.SHIPPABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        Duration escrowWindow = support.platformSettingService.getDays(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS);
        OffsetDateTime now = support.now();
        OffsetDateTime autoCompleteAt = now.plus(escrowWindow);

        int updated = support.orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                OrderTransitionSupport.SHIPPABLE_STATUSES,
                "SHIPPED",
                now, null, autoCompleteAt, null, null, null);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        support.orderRepository.insertShipment(
                sellerOrderId,
                request.carrierCode(),
                request.carrierName(),
                request.trackingNumber(),
                "IN_TRANSIT",
                now,
                request.estimatedDeliveryDate(),
                request.proofImageKey(),
                principal.userId());

        // One query for the whole parcel's cards rather than one per line.
        Map<Long, List<Long>> unitsByItem = support.orderRepository.findUnitIdsGroupedBySellerOrderId(sellerOrderId);
        for (Map.Entry<Long, List<Long>> entry : unitsByItem.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                inventoryPort.commitSale(entry.getKey(), entry.getValue(), principal.userId());
            }
        }

        support.orderRepository.insertStatusHistory(
                sellerOrderId,
                sellerOrder.getStatus(),
                "SHIPPED",
                principal.userId(),
                "Order shipped with tracking " + request.trackingNumber());

        ledgerPort.scheduleRelease(sellerOrderId, autoCompleteAt);

        settlement.rollupSalesOrderStatus(sellerOrder.getSalesOrderId());
        return mapper.toSellerOrderDetailsResponse(sellerOrderId);
    }

    SellerOrderDetailsResponse acceptSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        support.requireAuthenticated(principal);
        SellerProfile profile = support.requireSellerProfile(principal.userId());
        SellerOrderRecord sellerOrder = support.orderRepository.findSellerOrderByIdAndSellerProfileId(sellerOrderId, profile.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!SellerOrderStatus.of(sellerOrder.getStatus()).canMoveTo(SellerOrderStatus.PREPARING)
                || support.orderRepository.markSellerOrderAccepted(sellerOrderId, support.now()) == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        support.orderRepository.insertStatusHistory(
                sellerOrderId, sellerOrder.getStatus(), "PREPARING", principal.userId(), "Seller accepted the order");
        return mapper.toSellerOrderDetailsResponse(sellerOrderId);
    }

    void autoCompleteSellerOrder(long sellerOrderId) {
        SellerOrderRecord so = support.orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (so == null || !OrderTransitionSupport.CONFIRMABLE_STATUSES.contains(so.getStatus())) {
            return;
        }

        OffsetDateTime now = support.now();
        int updated = support.orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                OrderTransitionSupport.CONFIRMABLE_STATUSES,
                "COMPLETED",
                null, now, null, now, null, null);

        if (updated == 0) {
            return;
        }

        support.orderRepository.insertStatusHistory(
                sellerOrderId,
                so.getStatus(),
                "COMPLETED",
                null,
                "Auto-completed by escrow release scheduler");

        ledgerPort.release(sellerOrderId);

        SalesOrderRecord parent = support.orderRepository.findSalesOrderById(so.getSalesOrderId()).orElse(null);
        if (parent != null) {
            collectionPort.grant(parent.getBuyerId(), sellerOrderId);
            settlement.rollupSalesOrderStatus(parent.getId());
        }
    }
}
