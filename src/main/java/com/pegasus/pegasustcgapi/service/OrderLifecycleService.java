package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.OrderItemDetailsResponse;
import com.pegasus.pegasustcgapi.dto.OrderStatusHistoryResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.dto.ShipmentResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ShipmentRecord;
import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.CollectionPort;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating order lifecycle, state transitions, ownership validation,
 * inventory movements, ledger payouts, and collection grants.
 *
 * <p>The response mappers all go through {@link #sellerDetailsById}, which loads a
 * whole page of orders with a fixed number of queries. Mapping one order at a time
 * used to cost three queries per sub-order plus one per item, so a buyer with a
 * long history could make a single GET run hundreds of statements.
 */
@Service
public class OrderLifecycleService {

    /**
     * The compare-and-set guards, as names because the column stores names. Which
     * statuses belong in each set lives on {@link SellerOrderStatus}, so a new
     * transition is described in one place rather than in a string list per method.
     */
    private static final List<String> CANCELLABLE_STATUSES =
            SellerOrderStatus.namesOf(SellerOrderStatus.CANCELLABLE);
    private static final List<String> CONFIRMABLE_STATUSES =
            SellerOrderStatus.namesOf(SellerOrderStatus.CONFIRMABLE);
    private static final List<String> SHIPPABLE_STATUSES =
            SellerOrderStatus.namesOf(SellerOrderStatus.SHIPPABLE);

    private final OrderRepository orderRepository;
    private final SellerPort sellerPort;
    private final PlatformSettingService platformSettingService;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;
    private final CollectionPort collectionPort;
    private final Clock clock;

    @Autowired
    public OrderLifecycleService(
            OrderRepository orderRepository,
            SellerPort sellerPort,
            PlatformSettingService platformSettingService,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            CollectionPort collectionPort,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.sellerPort = sellerPort;
        this.platformSettingService = platformSettingService;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.collectionPort = collectionPort;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public OrderLifecycleService(
            OrderRepository orderRepository,
            SellerPort sellerPort,
            PlatformSettingService platformSettingService,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            CollectionPort collectionPort) {
        this(orderRepository, sellerPort, platformSettingService,
                inventoryPort, ledgerPort, collectionPort, Clock.systemUTC());
    }

    // ==========================================
    // Buyer Operations
    // ==========================================

    @Transactional(readOnly = true)
    public PageResponse<OrderDetailsResponse> listBuyerOrders(AuthPrincipal principal, int page, int size) {
        requireAuthenticated(principal);
        Paging paging = Paging.of(page, size);
        List<SalesOrderRecord> orders = orderRepository.findSalesOrdersByBuyerId(
                principal.userId(), paging.size(), paging.offset());
        return PageResponse.of(
                toOrderDetailsResponses(orders),
                paging.page(),
                paging.size(),
                orderRepository.countSalesOrdersByBuyerId(principal.userId()));
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
        SellerOrderRecord sellerOrder = resolveBuyerSellerOrder(principal, orderId);
        if (sellerOrder != null) {
            SalesOrderRecord parentOrder = orderRepository
                    .findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
            return cancelSingleSellerOrder(principal, parentOrder, sellerOrder, request);
        }

        throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
    }

    private OrderDetailsResponse cancelSalesOrder(AuthPrincipal principal, SalesOrderRecord salesOrder, CancelOrderRequest request) {
        requireWithinCancelWindow(salesOrder);

        List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
        boolean anyShippedOrCompleted = sellerOrders.stream()
                .anyMatch(so -> List.of("SHIPPED", "DELIVERED", "COMPLETED").contains(so.getStatus()));
        if (anyShippedOrCompleted) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        String reason = cancelReason(request);
        OffsetDateTime now = now();
        List<Long> allUnitsToRelease = new ArrayList<>();

        for (SellerOrderRecord so : sellerOrders) {
            if (CANCELLABLE_STATUSES.contains(so.getStatus())) {
                int updated = orderRepository.updateSellerOrderStatus(
                        so.getId(),
                        CANCELLABLE_STATUSES,
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
        requireWithinCancelWindow(parentOrder);

        if (!CANCELLABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        String reason = cancelReason(request);
        OffsetDateTime now = now();

        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrder.getId(),
                CANCELLABLE_STATUSES,
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

        // Resolved in the same order as cancellation: sales_order first, then a
        // sub-order of one. The two id spaces overlap, so reading them the other way
        // round here would let the same id mean a different order on each endpoint.
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId()).orElse(null);
        if (salesOrder != null) {
            List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrder.getId());
            List<SellerOrderRecord> eligibleOrders = sellerOrders.stream()
                    .filter(so -> CONFIRMABLE_STATUSES.contains(so.getStatus()))
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

        SellerOrderRecord sellerOrder = resolveBuyerSellerOrder(principal, orderId);
        if (sellerOrder != null) {
            SalesOrderRecord parentOrder = orderRepository
                    .findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
            confirmSellerOrder(principal.userId(), sellerOrder);
            rollupSalesOrderStatus(parentOrder.getId());
            return toOrderDetailsResponse(parentOrder.getId());
        }

        throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
    }

    /** A sub-order this buyer paid for, or null when the id is not theirs. */
    private SellerOrderRecord resolveBuyerSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        SellerOrderRecord sellerOrder = orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (sellerOrder == null) {
            return null;
        }
        boolean ownedByCaller = orderRepository
                .findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId())
                .isPresent();
        return ownedByCaller ? sellerOrder : null;
    }

    private void confirmSellerOrder(long buyerUserId, SellerOrderRecord sellerOrder) {
        if (!CONFIRMABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        OffsetDateTime now = now();
        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrder.getId(),
                CONFIRMABLE_STATUSES,
                "COMPLETED",
                null, now, null, now, null, null);

        if (updated == 0) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        orderRepository.insertStatusHistory(sellerOrder.getId(), sellerOrder.getStatus(), "COMPLETED", buyerUserId, "Buyer confirmed receipt");
        ledgerPort.release(sellerOrder.getId());
        collectionPort.grant(buyerUserId, sellerOrder.getId());
    }

    /**
     * Moves an order to PAID without taking a payment.
     *
     * <p>There is no payment provider yet, so nothing here proves money changed
     * hands. The endpoint that reaches it is registered only when the mock payment
     * flag is on; see {@code MockPaymentController}.
     */
    @Transactional
    public OrderDetailsResponse markOrderPaid(AuthPrincipal principal, long orderId) {
        requireAuthenticated(principal);
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!"PENDING_PAYMENT".equals(salesOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

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
    /**
     * @param status null for every sub-order, or one status to narrow to — a seller
     *               looking at what to pack asks for PAID or PREPARING
     * @throws com.pegasus.pegasustcgapi.exception.BadRequestException when the name
     *         is not a status this build knows
     */
    public PageResponse<SellerOrderDetailsResponse> listSellerOrders(
            AuthPrincipal principal, String status, int page, int size) {
        requireAuthenticated(principal);
        SellerProfile profile = requireSellerProfile(principal.userId());
        String filter = normalizeStatusFilter(status);
        Paging paging = Paging.of(page, size);
        List<SellerOrderRecord> orders = orderRepository.findSellerOrdersBySellerProfileId(
                profile.id(), filter, paging.size(), paging.offset());
        return PageResponse.of(
                List.copyOf(sellerDetailsById(orders).values()),
                paging.page(),
                paging.size(),
                orderRepository.countSellerOrdersBySellerProfileId(profile.id(), filter));
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        SellerOrderStatus parsed = SellerOrderStatus.parseOrNull(status);
        if (parsed == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "status must be one of " + java.util.Arrays.toString(SellerOrderStatus.values()));
        }
        return parsed.name();
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

        if (!SHIPPABLE_STATUSES.contains(sellerOrder.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }

        Duration escrowWindow = platformSettingService.getDays(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS);
        OffsetDateTime now = now();
        OffsetDateTime autoCompleteAt = now.plus(escrowWindow);

        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                SHIPPABLE_STATUSES,
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

        // One query for the whole parcel's cards rather than one per line.
        Map<Long, List<Long>> unitsByItem = orderRepository.findUnitIdsGroupedBySellerOrderId(sellerOrderId);
        for (Map.Entry<Long, List<Long>> entry : unitsByItem.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                inventoryPort.commitSale(entry.getKey(), entry.getValue(), principal.userId());
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
        if (so == null || !CONFIRMABLE_STATUSES.contains(so.getStatus())) {
            return;
        }

        OffsetDateTime now = now();
        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                CONFIRMABLE_STATUSES,
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

    /**
     * Cancels one unpaid sub-order whose payment deadline has passed.
     *
     * <p>Its own transaction, like the escrow sweep: one order that will not close
     * must not stop the rest of the batch. {@code changed_by} is null because no
     * person did this.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireUnpaidSellerOrder(long sellerOrderId) {
        SellerOrderRecord so = orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (so == null || !SellerOrderStatus.PENDING_PAYMENT.name().equals(so.getStatus())) {
            return;
        }

        String reason = "Cancelled automatically: payment was not received in time";
        int updated = orderRepository.updateSellerOrderStatus(
                sellerOrderId,
                List.of(SellerOrderStatus.PENDING_PAYMENT.name()),
                "CANCELLED",
                null, null, null, null, now(), reason);

        if (updated == 0) {
            return;
        }

        orderRepository.insertStatusHistory(
                sellerOrderId, so.getStatus(), "CANCELLED", null, reason);

        List<Long> unitIds = orderRepository.findUnitIdsBySellerOrderId(sellerOrderId);
        if (!unitIds.isEmpty()) {
            inventoryPort.release(unitIds);
        }

        rollupSalesOrderStatus(so.getSalesOrderId());
    }

    // ==========================================
    // Pessimistic Aggregate Status Rollup
    // ==========================================

    /**
     * Recomputes the parent order's status from its sub-orders, under the row lock
     * taken by {@code findSalesOrderByIdForUpdate}.
     *
     * <p>{@code MANDATORY} because that lock is the whole point: outside a
     * transaction each statement would commit on its own and release the lock the
     * moment the SELECT returned, so two confirmations racing on sibling sub-orders
     * could each write a parent status computed from stale children. Failing loudly
     * beats a guard that quietly is not there.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rollupSalesOrderStatus(long salesOrderId) {
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
        } else if (statuses.equals(Set.of(SellerOrderStatus.PENDING_PAYMENT))) {
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
    }

    // ==========================================
    // Helpers & DTO Mappers
    // ==========================================

    /**
     * The cancellation window only runs once money is involved.
     *
     * <p>An unpaid order is holding stock and nothing else, so there is no reason
     * to make the buyer keep it; the deadline that matters for those is the payment
     * timeout, which cancels them on its own.
     */
    private void requireWithinCancelWindow(SalesOrderRecord salesOrder) {
        if (salesOrder.getPaidAt() == null) {
            return;
        }
        Duration window = platformSettingService.getHours(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS);
        if (salesOrder.getPaidAt().plus(window).isBefore(now())) {
            throw new ConflictException(ErrorCode.CANCEL_WINDOW_CLOSED);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String cancelReason(CancelOrderRequest request) {
        return (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason() : "Cancelled by buyer";
    }

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
    }

    /**
     * Read from the seller module, not from the token: a profile that was suspended
     * after the token was minted must not still pass as a seller.
     */
    private SellerProfile requireSellerProfile(long userId) {
        return sellerPort.requireProfile(userId);
    }

    public OrderDetailsResponse toOrderDetailsResponse(long salesOrderId) {
        SalesOrderRecord so = orderRepository.findSalesOrderById(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toOrderDetailsResponse(so);
    }

    public OrderDetailsResponse toOrderDetailsResponse(SalesOrderRecord so) {
        return toOrderDetailsResponses(List.of(so)).getFirst();
    }

    /** A whole page of orders in a fixed number of queries, whatever it holds. */
    public List<OrderDetailsResponse> toOrderDetailsResponses(List<SalesOrderRecord> salesOrders) {
        if (salesOrders.isEmpty()) {
            return List.of();
        }

        List<Long> salesOrderIds = salesOrders.stream().map(SalesOrderRecord::getId).toList();
        Map<Long, List<SellerOrderRecord>> sellerOrdersByParent =
                orderRepository.findSellerOrdersBySalesOrderIds(salesOrderIds);

        List<SellerOrderRecord> allSellerOrders = salesOrders.stream()
                .flatMap(so -> sellerOrdersByParent.getOrDefault(so.getId(), List.of()).stream())
                .toList();
        Map<Long, SellerOrderDetailsResponse> sellerResponsesById = sellerDetailsById(allSellerOrders);

        return salesOrders.stream().map(so -> {
            List<SellerOrderDetailsResponse> sellerResponses =
                    sellerOrdersByParent.getOrDefault(so.getId(), List.of()).stream()
                            .map(child -> sellerResponsesById.get(child.getId()))
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
        }).toList();
    }

    public SellerOrderDetailsResponse toSellerOrderDetailsResponse(long sellerOrderId) {
        SellerOrderRecord so = orderRepository.findSellerOrderById(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toSellerOrderDetailsResponse(so);
    }

    public SellerOrderDetailsResponse toSellerOrderDetailsResponse(SellerOrderRecord so) {
        return sellerDetailsById(List.of(so)).get(so.getId());
    }

    /**
     * Items, shipments and history for any number of sub-orders: four queries in
     * total, in the order the sub-orders were given.
     */
    private Map<Long, SellerOrderDetailsResponse> sellerDetailsById(List<SellerOrderRecord> sellerOrders) {
        if (sellerOrders.isEmpty()) {
            return Map.of();
        }

        List<Long> sellerOrderIds = sellerOrders.stream().map(SellerOrderRecord::getId).toList();
        Map<Long, List<OrderItemRecord>> itemsBySellerOrder =
                orderRepository.findOrderItemsBySellerOrderIds(sellerOrderIds);
        List<Long> orderItemIds = sellerOrders.stream()
                .flatMap(so -> itemsBySellerOrder.getOrDefault(so.getId(), List.of()).stream())
                .map(OrderItemRecord::getId)
                .toList();
        Map<Long, List<Long>> unitIdsByItem = orderRepository.findUnitIdsByOrderItemIds(orderItemIds);
        Map<Long, List<ShipmentRecord>> shipmentsBySellerOrder =
                orderRepository.findShipmentsBySellerOrderIds(sellerOrderIds);
        Map<Long, List<SellerOrderStatusHistoryRecord>> historyBySellerOrder =
                orderRepository.findStatusHistoryBySellerOrderIds(sellerOrderIds);

        Map<Long, SellerOrderDetailsResponse> responses = new LinkedHashMap<>();
        for (SellerOrderRecord so : sellerOrders) {
            List<OrderItemDetailsResponse> itemResponses =
                    itemsBySellerOrder.getOrDefault(so.getId(), List.of()).stream()
                            .map(oi -> new OrderItemDetailsResponse(
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
                                    unitIdsByItem.getOrDefault(oi.getId(), List.of())))
                            .toList();

            List<ShipmentResponse> shipmentResponses =
                    shipmentsBySellerOrder.getOrDefault(so.getId(), List.of()).stream()
                            .map(s -> new ShipmentResponse(
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
                                    s.getUpdatedAt()))
                            .toList();

            List<OrderStatusHistoryResponse> historyResponses =
                    historyBySellerOrder.getOrDefault(so.getId(), List.of()).stream()
                            .map(h -> new OrderStatusHistoryResponse(
                                    h.getId(),
                                    h.getSellerOrderId(),
                                    h.getFromStatus(),
                                    h.getToStatus(),
                                    h.getChangedBy(),
                                    h.getNote(),
                                    h.getCreatedAt()))
                            .toList();

            responses.put(so.getId(), new SellerOrderDetailsResponse(
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
                    historyResponses));
        }
        return responses;
    }
}
