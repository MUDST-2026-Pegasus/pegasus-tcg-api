package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse.OrderItemResponse;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.dto.order.SaleDiscount;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.JSONB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Maps persisted checkout snapshots without changing their response shape. */
@Service
class CheckoutResponseMapper {
    private final OrderRepository orderRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final OrderReferenceQueryRepository orderReferenceQueryRepository;
    private final StorageService storageService;
    private final ObjectMapper json;

    CheckoutResponseMapper(OrderRepository orderRepository, OrderQueryRepository orderQueryRepository,
            OrderReferenceQueryRepository orderReferenceQueryRepository,
            @Autowired(required = false) StorageService storageService, ObjectMapper json) {
        this.orderRepository = orderRepository;
        this.orderQueryRepository = orderQueryRepository;
        this.orderReferenceQueryRepository = orderReferenceQueryRepository;
        this.storageService = storageService;
        this.json = json != null ? json : new ObjectMapper();
    }

    CheckoutResponse getOrderDetails(long salesOrderId, boolean replayed) {
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderById(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        List<SellerOrderRecord> sellerOrders = orderQueryRepository.findSellerOrdersBySalesOrderId(salesOrderId);

        // Three queries whatever the basket holds: one per level, not one per row.
        List<Long> sellerOrderIds = sellerOrders.stream().map(SellerOrderRecord::getId).toList();
        Map<Long, List<OrderItemRecord>> itemsBySellerOrder =
                orderQueryRepository.findOrderItemsBySellerOrderIds(sellerOrderIds);
        List<Long> orderItemIds = sellerOrders.stream()
                .flatMap(so -> itemsBySellerOrder.getOrDefault(so.getId(), List.of()).stream())
                .map(OrderItemRecord::getId)
                .toList();
        Map<Long, List<Long>> unitIdsByItem = orderQueryRepository.findUnitIdsByOrderItemIds(orderItemIds);
        List<Long> profileIds = sellerOrders.stream().map(SellerOrderRecord::getSellerProfileId).distinct().toList();
        Map<Long, String> sellerNames = orderReferenceQueryRepository.findSellerNamesByProfileIds(profileIds);

        List<SellerOrderResponse> sellerResponses = new ArrayList<>();
        for (SellerOrderRecord so : sellerOrders) {
            List<OrderItemResponse> itemResponses = new ArrayList<>();

            for (OrderItemRecord oi : itemsBySellerOrder.getOrDefault(so.getId(), List.of())) {
                String imageUrl = null;
                if (storageService != null && oi.getImageKeySnapshot() != null && !oi.getImageKeySnapshot().isBlank()) {
                    try {
                        imageUrl = storageService.presignDownload(oi.getImageKeySnapshot());
                    } catch (Exception e) {
                        // ignore storage errors
                    }
                }
                itemResponses.add(new OrderItemResponse(
                        oi.getId(),
                        oi.getListingId(),
                        oi.getCatalogVariantId(),
                        oi.getProductNameSnapshot(),
                        oi.getVariantLabelSnapshot(),
                        oi.getConditionSnapshot(),
                        oi.getQuantity(),
                        oi.getUnitPrice(),
                        oi.getLineTotal(),
                        unitIdsByItem.getOrDefault(oi.getId(), List.of()),
                        imageUrl,
                        SaleDiscount.listPrice(oi),
                        SaleDiscount.of(oi)));
            }

            sellerResponses.add(new SellerOrderResponse(
                    so.getId(),
                    so.getSellerProfileId(),
                    so.getSellerOrderNumber(),
                    so.getStatus(),
                    so.getItemsSubtotal(),
                    so.getShippingFee(),
                    so.getGrandTotal(),
                    so.getCommissionAmount(),
                    so.getSellerNetAmount(),
                    itemResponses,
                    sellerNames.get(so.getSellerProfileId())));
        }

        return new CheckoutResponse(
                salesOrder.getId(),
                salesOrder.getOrderNumber(),
                salesOrder.getBuyerId(),
                salesOrder.getStatus(),
                salesOrder.getCurrency(),
                salesOrder.getItemsSubtotal(),
                salesOrder.getShippingTotal(),
                salesOrder.getDiscountTotal(),
                salesOrder.getGrandTotal(),
                salesOrder.getPlacedAt(),
                sellerResponses,
                replayed);
    }

    JSONB createAddressSnapshot(Address a) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", a.id());
        snapshot.put("recipientName", a.recipientName());
        snapshot.put("phone", a.phone());
        snapshot.put("line1", a.line1());
        snapshot.put("line2", a.line2());
        snapshot.put("subdistrict", a.subdistrict());
        snapshot.put("district", a.district());
        snapshot.put("province", a.province());
        snapshot.put("postalCode", a.postalCode());
        snapshot.put("countryCode", a.countryCode());
        return JSONB.valueOf(json.writeValueAsString(snapshot));
    }
}
