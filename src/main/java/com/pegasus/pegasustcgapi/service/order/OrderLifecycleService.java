package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.ReorderItemsResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.port.collection.CollectionPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional facade for order queries and lifecycle transitions.
 */
@Service
public class OrderLifecycleService {

    private final OrderSettlementService settlement;
    private final OrderResponseMapper mapper;
    private final OrderQueryService queries;
    private final OrderCancellationService cancellation;
    private final OrderFulfillmentService fulfillment;
    private final OrderPaymentLifecycleService paymentLifecycle;

    public OrderLifecycleService(
            OrderRepository orderRepository,
            SellerPort sellerPort,
            PlatformSettingService platformSettingService,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            CollectionPort collectionPort,
            Clock clock,
            @Autowired(required = false) StorageService storageService,
            AuctionRepository auctionRepository) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        this.settlement = new OrderSettlementService(orderRepository, effectiveClock);
        this.mapper = new OrderResponseMapper(orderRepository, platformSettingService, storageService);
        OrderTransitionSupport support = new OrderTransitionSupport(orderRepository, sellerPort, platformSettingService, effectiveClock);
        this.queries = new OrderQueryService(support, mapper, storageService);
        this.cancellation = new OrderCancellationService(support, inventoryPort, ledgerPort, settlement, mapper);
        this.fulfillment = new OrderFulfillmentService(support, inventoryPort, ledgerPort, collectionPort, settlement, mapper);
        this.paymentLifecycle = new OrderPaymentLifecycleService(support, auctionRepository, settlement, mapper);
    }

    @Autowired
    OrderLifecycleService(OrderSettlementService settlement, OrderResponseMapper mapper,
            OrderQueryService queries, OrderCancellationService cancellation,
            OrderFulfillmentService fulfillment, OrderPaymentLifecycleService paymentLifecycle) {
        this.settlement = settlement;
        this.mapper = mapper;
        this.queries = queries;
        this.cancellation = cancellation;
        this.fulfillment = fulfillment;
        this.paymentLifecycle = paymentLifecycle;
    }

    // ==========================================
    // Buyer Operations
    // ==========================================

    @Transactional(readOnly = true)
    public PageResponse<OrderDetailsResponse> listBuyerOrders(AuthPrincipal principal, int page, int size) {
        return queries.listBuyerOrders(principal, page, size);
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getBuyerOrder(AuthPrincipal principal, long orderId) {
        return queries.getBuyerOrder(principal, orderId);
    }

    @Transactional(readOnly = true)
    public ReorderItemsResponse getReorderItems(AuthPrincipal principal, long orderId) {
        return queries.getReorderItems(principal, orderId);
    }

    @Transactional(readOnly = true)
    public ReorderItemsResponse getReorderItems(Long orderId, Long buyerId) {
        return queries.getReorderItems(orderId, buyerId);
    }

    @Transactional
    public OrderDetailsResponse cancelBuyerOrder(AuthPrincipal principal, long orderId, CancelOrderRequest request) {
        return cancellation.cancelBuyerOrder(principal, orderId, request);
    }

    @Transactional
    public OrderDetailsResponse confirmBuyerOrderReceived(AuthPrincipal principal, long orderId) {
        return fulfillment.confirmBuyerOrderReceived(principal, orderId);
    }

    /** Legacy test helper; production payment goes through PaymentService and its ledger. */
    @Transactional
    public OrderDetailsResponse markOrderPaid(AuthPrincipal principal, long orderId) {
        return paymentLifecycle.markOrderPaid(principal, orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markOrderPaidBySystem(long orderId, Long actorUserId) {
        paymentLifecycle.markOrderPaidBySystem(orderId, actorUserId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionAfterSales(long sellerOrderId, SellerOrderStatus target, Long actorUserId,
            String reason) {
        paymentLifecycle.transitionAfterSales(sellerOrderId, target, actorUserId, reason);
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
        return queries.listSellerOrders(principal, status, page, size);
    }

    @Transactional(readOnly = true)
    public SellerOrderDetailsResponse getSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        return queries.getSellerOrder(principal, sellerOrderId);
    }

    @Transactional
    public SellerOrderDetailsResponse shipSellerOrder(AuthPrincipal principal, long sellerOrderId, ShipOrderRequest request) {
        return fulfillment.shipSellerOrder(principal, sellerOrderId, request);
    }

    /**
     * The seller takes the order on and starts packing: PAID to PREPARING.
     *
     * <p>Optional — shipping is allowed straight from PAID — but it tells the buyer
     * someone is on it, and it is the moment {@code accepted_at} records.
     */
    @Transactional
    public SellerOrderDetailsResponse acceptSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        return fulfillment.acceptSellerOrder(principal, sellerOrderId);
    }

    /**
     * The seller calls the order off before it ships — out of stock, damaged card.
     * The cards go back on sale and any money paid is refunded, the same as when
     * the buyer cancels.
     *
     * <p>Unlike the buyer, the seller is not bound by the cancel window: the window
     * protects the seller from a buyer changing their mind late, not the other way
     * round. A transfer still under review blocks it, since there is nothing settled
     * to refund yet.
     *
     * @param reason shown to the buyer; required, the controller validates it
     */
    @Transactional
    public SellerOrderDetailsResponse cancelSellerOrderBySeller(
            AuthPrincipal principal, long sellerOrderId, String reason) {
        return cancellation.cancelSellerOrderBySeller(principal, sellerOrderId, reason);
    }

    // ==========================================
    // Auto-Release Scheduler Execution
    // ==========================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoCompleteSellerOrder(long sellerOrderId) {
        fulfillment.autoCompleteSellerOrder(sellerOrderId);
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
        cancellation.expireUnpaidSellerOrder(sellerOrderId);
    }

    /** Called inside the auction timeout transaction after the parent order row is locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireUnpaidAuctionOrder(long salesOrderId, long auctionId) {
        return cancellation.expireUnpaidAuctionOrder(salesOrderId, auctionId);
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
        settlement.rollupSalesOrderStatus(salesOrderId);
    }

    @Autowired(required = false)
    void setSaleCheckout(SaleCheckoutService saleCheckout) {
        settlement.setSaleCheckout(saleCheckout);
    }

    /**
     * Optional so the constructors the unit tests use stay as they are; Spring always
     * supplies it.
     */
    @Autowired(required = false)
    void setCheckoutPromotions(CheckoutPromotionService checkoutPromotions) {
        mapper.setCheckoutPromotions(checkoutPromotions);
        settlement.setCheckoutPromotions(checkoutPromotions);
    }

    public OrderDetailsResponse toOrderDetailsResponse(long id) {
        return mapper.toOrderDetailsResponse(id);
    }
    public OrderDetailsResponse toOrderDetailsResponse(SalesOrderRecord order) {
        return mapper.toOrderDetailsResponse(order);
    }
    public List<OrderDetailsResponse> toOrderDetailsResponses(List<SalesOrderRecord> orders) {
        return mapper.toOrderDetailsResponses(orders);
    }
    public SellerOrderDetailsResponse toSellerOrderDetailsResponse(long id) {
        return mapper.toSellerOrderDetailsResponse(id);
    }
    public SellerOrderDetailsResponse toSellerOrderDetailsResponse(SellerOrderRecord order) {
        return mapper.toSellerOrderDetailsResponse(order);
    }
    public Map<Long, SellerOrderDetailsResponse> toSellerOrderDetailsResponses(List<Long> ids) {
        return mapper.toSellerOrderDetailsResponses(ids);
    }

}
