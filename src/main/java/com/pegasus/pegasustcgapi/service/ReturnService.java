package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.ReturnRequestRecord;
import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.ReturnRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReturnService {

    private final ReturnRepository returns;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final SellerPort sellers;
    private final OrderLifecycleService lifecycle;
    private final RefundService refunds;
    private final InventoryPort inventory;
    private final StorageService storage;
    private final PlatformSettingService settings;
    private final ObjectMapper json;
    private final Clock clock;

    public ReturnService(ReturnRepository returns, OrderRepository orders, PaymentRepository payments,
            SellerPort sellers, OrderLifecycleService lifecycle, RefundService refunds,
            InventoryPort inventory,
            StorageService storage, PlatformSettingService settings, ObjectMapper json, Clock clock) {
        this.returns = returns;
        this.orders = orders;
        this.payments = payments;
        this.sellers = sellers;
        this.lifecycle = lifecycle;
        this.refunds = refunds;
        this.inventory = inventory;
        this.storage = storage;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReturnRequestRecord> buyerReturns(long buyerId, long sellerOrderId) {
        buyerOrder(buyerId, sellerOrderId);
        return returns.forOrder(sellerOrderId);
    }

    @Transactional
    public ReturnRequestRecord request(long buyerId, long sellerOrderId, String reason,
            String description, List<String> evidenceKeys, List<Item> items) {
        var order = buyerOrder(buyerId, sellerOrderId);
        payments.lockOrder(order.getSalesOrderId());
        order = orders.findSellerOrderById(sellerOrderId).orElseThrow();
        if (!List.of("SHIPPED", "DELIVERED").contains(order.getStatus())
                || order.getAutoCompleteAt() == null || !order.getAutoCompleteAt().isAfter(now())
                || order.getDeliveredAt() != null && order.getDeliveredAt().plus(settings.getDays(
                        PlatformSettingService.RETURN_REQUEST_WINDOW_DAYS)).isBefore(now())) {
            throw new ConflictException(ErrorCode.RETURN_WINDOW_CLOSED);
        }
        if (!returns.forOrder(sellerOrderId).isEmpty()) throw new ConflictException(ErrorCode.RETURN_ALREADY_OPEN);
        if (!Set.of("NOT_AS_DESCRIBED", "DAMAGED", "WRONG_ITEM", "NOT_RECEIVED", "COUNTERFEIT", "OTHER")
                .contains(reason) || items == null || items.isEmpty() || evidenceKeys == null
                || evidenceKeys.size() > 10) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        Set<Long> seen = new HashSet<>();
        for (Item item : items) {
            var ordered = returns.orderItem(item.orderItemId(), sellerOrderId)
                    .orElseThrow(() -> new BadRequestException(ErrorCode.VALIDATION_FAILED));
            if (!seen.add(item.orderItemId()) || item.quantity() < 1 || item.quantity() > ordered.getQuantity()) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
            }
        }
        for (String key : evidenceKeys) storage.requireUploadedFor(UploadPurpose.RETURN_EVIDENCE, key);
        var request = returns.create(sellerOrderId, buyerId, reason, description,
                json.writeValueAsString(evidenceKeys));
        for (Item item : items) returns.addItem(request.getId(), item.orderItemId(), item.quantity());
        lifecycle.transitionAfterSales(sellerOrderId, SellerOrderStatus.RETURN_REQUESTED,
                buyerId, "Return requested");
        return request;
    }

    @Transactional(readOnly = true)
    public List<ReturnRequestRecord> sellerReturns(long sellerUserId, String status) {
        return returns.forSeller(sellers.requireProfile(sellerUserId).id(), status);
    }

    @Transactional(readOnly = true)
    public List<EvidenceLink> sellerEvidence(long sellerUserId, long returnId) {
        var request = returns.find(returnId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RETURN_NOT_FOUND));
        var order = orders.findSellerOrderById(request.getSellerOrderId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.RETURN_NOT_FOUND));
        if (sellers.requireProfile(sellerUserId).id() != order.getSellerProfileId()) {
            throw new NotFoundException(ErrorCode.RETURN_NOT_FOUND);
        }
        List<String> keys = com.pegasus.pegasustcgapi.dto.FinanceViews.ReturnRequest.from(request, json)
                .evidenceKeys();
        for (String key : keys) {
            if (!key.startsWith(UploadPurpose.RETURN_EVIDENCE.prefix() + "/")) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
            }
        }
        return java.util.stream.IntStream.range(0, keys.size())
                .mapToObj(index -> new EvidenceLink(index + 1, storage.presignDownload(keys.get(index))))
                .toList();
    }

    @Transactional
    public ReturnRequestRecord approve(long sellerUserId, long returnId, String resolution,
            BigDecimal amount, boolean requireShipBack) {
        var request = sellerReturn(sellerUserId, returnId);
        if (!"REQUESTED".equals(request.getStatus())) throw new ConflictException(ErrorCode.RETURN_STATE);
        var order = orders.findSellerOrderById(request.getSellerOrderId()).orElseThrow();
        if (!Set.of("FULL_REFUND", "PARTIAL_REFUND").contains(resolution)
                || amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(order.getGrandTotal()) > 0
                || "FULL_REFUND".equals(resolution) && amount.compareTo(order.getGrandTotal()) != 0
                || "PARTIAL_REFUND".equals(resolution) && amount.compareTo(order.getGrandTotal()) >= 0) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        returns.approve(returnId, resolution, amount, sellerUserId, now(),
                requireShipBack ? "AWAITING_RETURN_SHIPMENT" : "APPROVED");
        if (!requireShipBack) refunds.onReturn(order.getId(), returnId, "Return approved", amount);
        return returns.lock(returnId).orElseThrow();
    }

    @Transactional
    public ReturnRequestRecord reject(long sellerUserId, long returnId) {
        var request = sellerReturn(sellerUserId, returnId);
        if (!"REQUESTED".equals(request.getStatus())) throw new ConflictException(ErrorCode.RETURN_STATE);
        returns.reject(returnId, sellerUserId, now());
        // A rejection resumes the original release clock; it does not pay the seller early.
        var order = orders.findSellerOrderById(request.getSellerOrderId()).orElseThrow();
        lifecycle.transitionAfterSales(request.getSellerOrderId(),
                order.getDeliveredAt() == null ? SellerOrderStatus.SHIPPED : SellerOrderStatus.DELIVERED,
                sellerUserId, "Return rejected");
        return returns.lock(returnId).orElseThrow();
    }

    @Transactional
    public ReturnRequestRecord ship(long buyerId, long sellerOrderId, long returnId, String tracking) {
        var order = buyerOrder(buyerId, sellerOrderId);
        payments.lockOrder(order.getSalesOrderId());
        var request = returns.lock(returnId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RETURN_NOT_FOUND));
        if (request.getBuyerId() != buyerId || request.getSellerOrderId() != sellerOrderId) {
            throw new NotFoundException(ErrorCode.RETURN_NOT_FOUND);
        }
        if (!"AWAITING_RETURN_SHIPMENT".equals(request.getStatus())) throw new ConflictException(ErrorCode.RETURN_STATE);
        if (tracking == null || tracking.isBlank()) throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        returns.ship(returnId, tracking);
        return returns.lock(returnId).orElseThrow();
    }

    @Transactional
    public ReturnRequestRecord receive(long sellerUserId, long returnId, boolean restock) {
        var request = sellerReturn(sellerUserId, returnId);
        if (!"IN_TRANSIT".equals(request.getStatus())) throw new ConflictException(ErrorCode.RETURN_STATE);
        for (var item : returns.items(returnId)) {
            returns.setRestock(item.getId(), restock);
            if (restock) {
                var units = returns.soldUnits(item.getOrderItemId(), item.getQuantity());
                if (units.size() != item.getQuantity()) throw new ConflictException(ErrorCode.RETURN_STATE);
                for (long unitId : units) inventory.restockReturn(item.getId(), unitId, sellerUserId);
            }
        }
        returns.receive(returnId, now());
        lifecycle.transitionAfterSales(request.getSellerOrderId(), SellerOrderStatus.RETURNED,
                sellerUserId, "Returned items received");
        refunds.onReturn(request.getSellerOrderId(), returnId,
                "Returned items received", request.getRefundAmount());
        return returns.lock(returnId).orElseThrow();
    }

    private com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord buyerOrder(
            long buyerId, long sellerOrderId) {
        var order = orders.findSellerOrderById(sellerOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        var parent = payments.findOrder(order.getSalesOrderId()).orElseThrow();
        if (parent.getBuyerId() != buyerId) throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
        return order;
    }

    private ReturnRequestRecord sellerReturn(long sellerUserId, long returnId) {
        var initial = returns.find(returnId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RETURN_NOT_FOUND));
        var parent = orders.findSellerOrderById(initial.getSellerOrderId()).orElseThrow();
        payments.lockOrder(parent.getSalesOrderId());
        var request = returns.lock(returnId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RETURN_NOT_FOUND));
        var order = orders.findSellerOrderById(request.getSellerOrderId()).orElseThrow();
        if (sellers.requireProfile(sellerUserId).id() != order.getSellerProfileId()) {
            throw new NotFoundException(ErrorCode.RETURN_NOT_FOUND);
        }
        return request;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    public record Item(long orderItemId, int quantity) {
    }

    public record EvidenceLink(int number, String url) {
    }
}
