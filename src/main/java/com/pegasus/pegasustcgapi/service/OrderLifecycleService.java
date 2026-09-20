package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.OrderItemDetailsResponse;
import com.pegasus.pegasustcgapi.dto.OrderStatusHistoryResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.dto.ShipmentResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ShipmentRecord;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.CollectionPort;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating order lifecycle, state transitions, ownership validation,
 * inventory movements, ledger payouts, and collection grants.
 */
@Service
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final PlatformSettingService platformSettingService;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;
    private final CollectionPort collectionPort;

    public OrderLifecycleService(
            OrderRepository orderRepository,
            SellerProfileRepository sellerProfileRepository,
            PlatformSettingService platformSettingService,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            CollectionPort collectionPort) {
        this.orderRepository = orderRepository;
        this.sellerProfileRepository = sellerProfileRepository;
        this.platformSettingService = platformSettingService;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.collectionPort = collectionPort;
    }

    // ==========================================
    // Buyer Operations
    // ==========================================

    @Transactional(readOnly = true)
    public List<OrderDetailsResponse> listBuyerOrders(AuthPrincipal principal) {
        requireAuthenticated(principal);
        List<SalesOrderRecord> orders = orderRepository.findSalesOrdersByBuyerId(principal.userId());
        return orders.stream()
                .map(this::toOrderDetailsResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getBuyerOrder(AuthPrincipal principal, long orderId) {
        requireAuthenticated(principal);
        SalesOrderRecord order = orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toOrderDetailsResponse(order);
    }

    @Transactional
    public OrderDetailsResponse cancelBuyerOrder(AuthPrincipal principal, long orderId, CancelOrderRequest request) {
        requireAuthenticated(principal);

        // Check if orderId is a sales_order belonging to current buyer
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId()).orElse(null);
        if (salesOrder != null) {
            return cancelSalesOrder(principal, salesOrder, request);
        }

        // Check if orderId is a child seller_order belonging to this buyer
        SellerOrderRecord sellerOrder = orderRepository.findSellerOrderById(orderId).orElse(null);
        if (sellerOrder != null) {
            SalesOrderRecord parentOrder = orderRepository.findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId()).orElse(null);
            if (parentOrder != null) {
                return cancelSingleSellerOrder(principal, parentOrder, sellerOrder, request);
            }
        }

        throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
    }

    private OrderDetailsResponse cancelSalesOrder(AuthPrincipal principal, SalesOrderRecord salesOrder, CancelOrderRequest request) {
        int cancelWindowHours = platformSettingService.getInt(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS);
        if (salesOrder.getPlacedAt().plusHours(cancelWindowHours).isBefore(OffsetDateTime.now())) {
            throw new ConflictException(ErrorCode.CANCEL_WINDOW_CLOSED);
        }

        List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
        boolean anyShippedOrCompleted = sellerOrders.stream()
                .anyMatch(so -> List.of("SHIPPED", "DELIVERED", "COMPLETED").contains(so.getStatus()));
        if (anyShippedOrCompleted) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason() : "Cancelled by buyer";

        OffsetDateTime now = OffsetDateTime.now();
        List<Long> allUnitsToRelease = new ArrayList<>();

        for (SellerOrderRecord so : sellerOrders) {
            if (List.of("PENDING_PAYMENT", "PAID", "PREPARING").contains(so.getStatus())) {
                int updated = orderRepository.updateSellerOrderStatus(
                        so.getId(),
                        List.of("PENDING_PAYMENT", "PAID", "PREPARING"),
                        "CANCELLED",
                        null, null, null, null, now, reason);

                if (updated > 0) {
                    orderRepository.insertStatusHistory(so.getId(), so.getStatus(), "CANCELLED", principal.userId(), reason);
                    allUnitsToRelease.addAll(orderRepository.findUnitIdsBySellerOrderId(so.getId()));
                }
            }
        }

        if (!allUnitsToRelease.isEmpty()) {
            inventoryPort.release(allUnitsToRelease);
        }

        rollupSalesOrderStatus(salesOrder.getId());
        return toOrderDetailsResponse(salesOrder.getId());
    }

    private OrderDetailsResponse cancelSingleSellerOrder(AuthPrincipal principal, SalesOrderRecord parentOrder, SellerOrderRecord sellerOrder, CancelOrderRequest request) {
        int cancelWindowHours = platformSettingService.getInt(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS);
        if (parentOrder.getPlacedAt().plusHours(cancelWindowHours).isBefore(OffsetDateTime.now())) {
            throw new ConflictException(ErrorCode.CANCEL_WINDOW_CLOSED);
        }

        if (!List.of("PENDING_PAYMENT", "PAID", "PREPARING").contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason() : "Cancelled by buyer";
        OffsetDateTime now = OffsetDateTime.now();

        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrder.getId(),
                List.of("PENDING_PAYMENT", "PAID", "PREPARING"),
                "CANCELLED",
                null, null, null, null, now, reason);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        orderRepository.insertStatusHistory(sellerOrder.getId(), sellerOrder.getStatus(), "CANCELLED", principal.userId(), reason);
        List<Long> unitIds = orderRepository.findUnitIdsBySellerOrderId(sellerOrder.getId());
        if (!unitIds.isEmpty()) {
            inventoryPort.release(unitIds);
        }

        rollupSalesOrderStatus(parentOrder.getId());
        return toOrderDetailsResponse(parentOrder.getId());
    }

    @Transactional
    public OrderDetailsResponse confirmBuyerOrderReceived(AuthPrincipal principal, long orderId) {
        requireAuthenticated(principal);

        // Check if orderId is a single seller_order
        SellerOrderRecord sellerOrder = orderRepository.findSellerOrderById(orderId).orElse(null);
        if (sellerOrder != null) {
            SalesOrderRecord parentOrder = orderRepository.findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId()).orElse(null);
            if (parentOrder != null) {
                confirmSellerOrder(principal.userId(), sellerOrder);
                rollupSalesOrderStatus(parentOrder.getId());
                return toOrderDetailsResponse(parentOrder.getId());
            }
        }

        // Check if orderId is a sales_order
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId()).orElse(null);
        if (salesOrder != null) {
            List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
            List<SellerOrderRecord> eligibleOrders = sellerOrders.stream()
                    .filter(so -> List.of("SHIPPED", "DELIVERED").contains(so.getStatus()))
                    .toList();

            if (eligibleOrders.isEmpty()) {
                throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
            }

            for (SellerOrderRecord so : eligibleOrders) {
                confirmSellerOrder(principal.userId(), so);
            }

            rollupSalesOrderStatus(salesOrder.getId());
            return toOrderDetailsResponse(salesOrder.getId());
        }

        throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
    }

    private void confirmSellerOrder(long buyerUserId, SellerOrderRecord sellerOrder) {
        if (!List.of("SHIPPED", "DELIVERED").contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        OffsetDateTime now = OffsetDateTime.now();
        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrder.getId(),
                List.of("SHIPPED", "DELIVERED"),
                "COMPLETED",
                null, now, null, now, null, null);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        orderRepository.insertStatusHistory(sellerOrder.getId(), sellerOrder.getStatus(), "COMPLETED", buyerUserId, "Buyer confirmed receipt");
        ledgerPort.release(sellerOrder.getId());
        collectionPort.grant(buyerUserId, sellerOrder.getId());
    }

    @Transactional
    public OrderDetailsResponse markOrderPaid(AuthPrincipal principal, long orderId) {
        requireAuthenticated(principal);
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!"PENDING_PAYMENT".equals(salesOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
        for (SellerOrderRecord so : sellerOrders) {
            if ("PENDING_PAYMENT".equals(so.getStatus())) {
                orderRepository.updateSellerOrderStatus(
                        so.getId(),
                        List.of("PENDING_PAYMENT"),
                        "PAID",
                        null, null, null, null, null, null);
                orderRepository.insertStatusHistory(so.getId(), "PENDING_PAYMENT", "PAID", principal.userId(), "Payment completed");
            }
        }

        rollupSalesOrderStatus(salesOrder.getId());
        return toOrderDetailsResponse(salesOrder.getId());
    }

    // ==========================================
    // Seller Operations
    // ==========================================

    @Transactional(readOnly = true)
    public List<SellerOrderDetailsResponse> listSellerOrders(AuthPrincipal principal) {
        requireAuthenticated(principal);
        SellerProfile profile = requireSellerProfile(principal.userId());
        return orderRepository.findSellerOrdersBySellerProfileId(profile.id()).stream()
                .map(this::toSellerOrderDetailsResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SellerOrderDetailsResponse getSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        requireAuthenticated(principal);
        SellerProfile profile = requireSellerProfile(principal.userId());
        SellerOrderRecord sellerOrder = orderRepository.findSellerOrderByIdAndSellerProfileId(sellerOrderId, profile.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toSellerOrderDetailsResponse(sellerOrder);
    }

    @Transactional
    public SellerOrderDetailsResponse shipSellerOrder(AuthPrincipal principal, long sellerOrderId, ShipOrderRequest request) {
        requireAuthenticated(principal);
        SellerProfile profile = requireSellerProfile(principal.userId());
        SellerOrderRecord sellerOrder = orderRepository.findSellerOrderByIdAndSellerProfileId(sellerOrderId, profile.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!List.of("PAID", "PREPARING").contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        int releaseDays = platformSettingService.getInt(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime autoCompleteAt = now.plusDays(releaseDays);

        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                List.of("PAID", "PREPARING"),
                "SHIPPED",
                now, null, autoCompleteAt, null, null, null);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        orderRepository.insertShipment(
                sellerOrderId,
                request.carrierCode(),
                request.carrierName(),
                request.trackingNumber(),
                "IN_TRANSIT",
                now,
                request.estimatedDeliveryDate(),
                request.proofImageKey(),
                principal.userId());

        List<OrderItemRecord> items = orderRepository.findOrderItemsBySellerOrderId(sellerOrderId);
        for (OrderItemRecord item : items) {
            List<Long> unitIds = orderRepository.findUnitIdsByOrderItemId(item.getId());
            if (!unitIds.isEmpty()) {
                inventoryPort.commitSale(item.getId(), unitIds, principal.userId());
            }
        }

        orderRepository.insertStatusHistory(
                sellerOrderId,
                sellerOrder.getStatus(),
                "SHIPPED",
                principal.userId(),
                "Order shipped with tracking " + request.trackingNumber());

        rollupSalesOrderStatus(sellerOrder.getSalesOrderId());
        return toSellerOrderDetailsResponse(sellerOrderId);
    }

    // ==========================================
    // Auto-Release Scheduler Execution
    // ==========================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoCompleteSellerOrder(long sellerOrderId) {
        SellerOrderRecord so = orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (so == null || !List.of("SHIPPED", "DELIVERED").contains(so.getStatus())) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                List.of("SHIPPED", "DELIVERED"),
                "COMPLETED",
                null, now, null, now, null, null);

        if (updated == 0) {
            return;
        }

        orderRepository.insertStatusHistory(
                sellerOrderId,
                so.getStatus(),
                "COMPLETED",
                null,
                "Auto-completed by escrow release scheduler");

        ledgerPort.release(sellerOrderId);

        SalesOrderRecord parent = orderRepository.findSalesOrderById(so.getSalesOrderId()).orElse(null);
        if (parent != null) {
            collectionPort.grant(parent.getBuyerId(), sellerOrderId);
            rollupSalesOrderStatus(parent.getId());
        }
    }

    // ==========================================
    // Pessimistic Aggregate Status Rollup
    // ==========================================

    public void rollupSalesOrderStatus(long salesOrderId) {
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdForUpdate(salesOrderId).orElse(null);
        if (salesOrder == null) {
            return;
        }

        List<SellerOrderRecord> children = orderRepository.findSellerOrdersBySalesOrderId(salesOrderId);
        if (children.isEmpty()) {
            return;
        }

        Set<String> statuses = children.stream().map(SellerOrderRecord::getStatus).collect(Collectors.toSet());

        String newStatus;
        OffsetDateTime completedAt = salesOrder.getCompletedAt();
        OffsetDateTime cancelledAt = salesOrder.getCancelledAt();
        OffsetDateTime paidAt = salesOrder.getPaidAt();
        OffsetDateTime now = OffsetDateTime.now();

        if (statuses.stream().allMatch("CANCELLED"::equals)) {
            newStatus = "CANCELLED";
            if (cancelledAt == null) {
                cancelledAt = now;
            }
        } else if (statuses.stream().allMatch("COMPLETED"::equals)) {
            newStatus = "COMPLETED";
            if (completedAt == null) {
                completedAt = now;
            }
        } else if (statuses.contains("COMPLETED")) {
            newStatus = "PARTIALLY_COMPLETED";
        } else if (statuses.stream().allMatch("REFUNDED"::equals)) {
            newStatus = "REFUNDED";
        } else if (statuses.stream().anyMatch(s -> List.of("PAID", "PREPARING", "SHIPPED", "DELIVERED", "RETURN_REQUESTED", "RETURNED").contains(s))) {
            newStatus = "PAID";
            if (paidAt == null) {
                paidAt = now;
            }
        } else if (statuses.stream().allMatch("PENDING_PAYMENT"::equals)) {
            newStatus = "PENDING_PAYMENT";
        } else {
            newStatus = "PAID";
        }

        orderRepository.updateSalesOrderStatus(salesOrderId, newStatus, paidAt, completedAt, cancelledAt);
    }

    // ==========================================
    // Helpers & DTO Mappers
    // ==========================================

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
    }

    private SellerProfile requireSellerProfile(long userId) {
        return sellerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SELLER_NOT_FOUND));
    }

    public OrderDetailsResponse toOrderDetailsResponse(long salesOrderId) {
        SalesOrderRecord so = orderRepository.findSalesOrderById(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toOrderDetailsResponse(so);
    }

    public OrderDetailsResponse toOrderDetailsResponse(SalesOrderRecord so) {
        List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(so.getId());
        List<SellerOrderDetailsResponse> sellerResponses = sellerOrders.stream()
                .map(this::toSellerOrderDetailsResponse)
                .toList();

        return new OrderDetailsResponse(
                so.getId(),
                so.getOrderNumber(),
                so.getBuyerId(),
                so.getStatus(),
                so.getCurrency(),
                so.getItemsSubtotal(),
                so.getShippingTotal(),
                so.getDiscountTotal(),
                so.getGrandTotal(),
                so.getShippingAddressId(),
                so.getShippingAddressSnapshot() != null ? so.getShippingAddressSnapshot().data() : null,
                so.getBuyerNote(),
                so.getPlacedAt(),
                so.getPaidAt(),
                so.getCompletedAt(),
                so.getCancelledAt(),
                sellerResponses);
    }

    public SellerOrderDetailsResponse toSellerOrderDetailsResponse(long sellerOrderId) {
        SellerOrderRecord so = orderRepository.findSellerOrderById(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toSellerOrderDetailsResponse(so);
    }

    public SellerOrderDetailsResponse toSellerOrderDetailsResponse(SellerOrderRecord so) {
        List<OrderItemRecord> items = orderRepository.findOrderItemsBySellerOrderId(so.getId());
        List<OrderItemDetailsResponse> itemResponses = items.stream().map(oi -> {
            List<Long> unitIds = orderRepository.findUnitIdsByOrderItemId(oi.getId());
            return new OrderItemDetailsResponse(
                    oi.getId(),
                    oi.getListingId(),
                    oi.getCatalogVariantId(),
                    oi.getQuantity(),
                    oi.getUnitPrice(),
                    oi.getLineTotal(),
                    oi.getProductNameSnapshot(),
                    oi.getVariantLabelSnapshot(),
                    oi.getConditionSnapshot(),
                    oi.getGameNameSnapshot(),
                    oi.getImageKeySnapshot(),
                    unitIds);
        }).toList();

        List<ShipmentRecord> shipments = orderRepository.findShipmentsBySellerOrderId(so.getId());
        List<ShipmentResponse> shipmentResponses = shipments.stream().map(s -> new ShipmentResponse(
                s.getId(),
                s.getSellerOrderId(),
                s.getCarrierCode(),
                s.getCarrierName(),
                s.getTrackingNumber(),
                s.getStatus(),
                s.getShippedAt(),
                s.getEstimatedDeliveryDate(),
                s.getDeliveredAt(),
                s.getProofImageKey(),
                s.getCreatedBy(),
                s.getCreatedAt(),
                s.getUpdatedAt())).toList();

        List<SellerOrderStatusHistoryRecord> histories = orderRepository.findStatusHistoryBySellerOrderId(so.getId());
        List<OrderStatusHistoryResponse> historyResponses = histories.stream().map(h -> new OrderStatusHistoryResponse(
                h.getId(),
                h.getSellerOrderId(),
                h.getFromStatus(),
                h.getToStatus(),
                h.getChangedBy(),
                h.getNote(),
                h.getCreatedAt())).toList();

        return new SellerOrderDetailsResponse(
                so.getId(),
                so.getSalesOrderId(),
                so.getSellerProfileId(),
                so.getSellerOrderNumber(),
                so.getStatus(),
                so.getItemsSubtotal(),
                so.getShippingFee(),
                so.getDiscountAmount(),
                so.getGrandTotal(),
                so.getCommissionAmount(),
                so.getSellerNetAmount(),
                so.getAcceptedAt(),
                so.getShippedAt(),
                so.getDeliveredAt(),
                so.getAutoCompleteAt(),
                so.getCompletedAt(),
                so.getCancelledAt(),
                so.getCancelReason(),
                so.getCreatedAt(),
                itemResponses,
                shipmentResponses,
                historyResponses);
    }
}
