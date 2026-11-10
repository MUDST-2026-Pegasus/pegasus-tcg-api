package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.common.LogSanitizer;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.ReorderItemsResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only order queries, executed inside the facade transaction. */
final class OrderQueryService {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);
    private final OrderTransitionSupport support;
    private final OrderResponseMapper mapper;
    private final StorageService storageService;

    OrderQueryService(OrderTransitionSupport support, OrderResponseMapper mapper, StorageService storageService) {
        this.support = support;
        this.mapper = mapper;
        this.storageService = storageService;
    }

    PageResponse<OrderDetailsResponse> listBuyerOrders(AuthPrincipal principal, int page, int size) {
        support.requireAuthenticated(principal);
        Paging paging = Paging.of(page, size);
        List<SalesOrderRecord> orders = support.orderRepository.findSalesOrdersByBuyerId(
                principal.userId(), paging.size(), paging.offset());
        return PageResponse.of(
                mapper.toOrderDetailsResponses(orders),
                paging.page(),
                paging.size(),
                support.orderRepository.countSalesOrdersByBuyerId(principal.userId()));
    }

    OrderDetailsResponse getBuyerOrder(AuthPrincipal principal, long orderId) {
        support.requireAuthenticated(principal);
        SalesOrderRecord order = support.orderRepository.findSalesOrderByIdAndBuyerId(orderId, principal.userId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return mapper.toOrderDetailsResponse(order);
    }

    ReorderItemsResponse getReorderItems(AuthPrincipal principal, long orderId) {
        support.requireAuthenticated(principal);
        return getReorderItems(orderId, principal.userId());
    }

    ReorderItemsResponse getReorderItems(Long orderId, Long buyerId) {
        if (orderId == null || buyerId == null) {
            throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
        }

        SalesOrderRecord order = support.orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        if (!SellerOrderStatus.COMPLETED.name().equalsIgnoreCase(order.getStatus())) {
            throw new BadRequestException(ErrorCode.INVALID_ORDER_STATUS, "Only completed orders can be reordered");
        }

        List<SellerOrderRecord> sellerOrders = support.orderRepository.findSellerOrdersBySalesOrderId(order.getId());
        if (sellerOrders.isEmpty()) {
            return new ReorderItemsResponse(order.getId(), order.getOrderNumber(), List.of());
        }

        List<Long> sellerOrderIds = sellerOrders.stream().map(SellerOrderRecord::getId).toList();
        Map<Long, List<OrderItemRecord>> itemsBySellerOrder = support.orderRepository.findOrderItemsBySellerOrderIds(sellerOrderIds);

        List<Long> profileIds = sellerOrders.stream().map(SellerOrderRecord::getSellerProfileId).distinct().toList();
        Map<Long, String> sellerNames = support.orderRepository.findSellerNamesByProfileIds(profileIds);

        List<OrderItemRecord> allOrderItems = sellerOrders.stream()
                .filter(so -> so.getStatus() == null || !SellerOrderStatus.CANCELLED.name().equalsIgnoreCase(so.getStatus()))
                .flatMap(so -> itemsBySellerOrder.getOrDefault(so.getId(), List.of()).stream())
                .toList();

        List<Long> variantIds = allOrderItems.stream()
                .map(OrderItemRecord::getCatalogVariantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, OrderRepository.ReorderProductMetadata> metadataMap = variantIds.isEmpty()
                ? Map.of()
                : support.orderRepository.findReorderProductMetadata(variantIds);

        List<ReorderItemsResponse.ReorderItemDto> items = new ArrayList<>();
        for (SellerOrderRecord so : sellerOrders) {
            if (so.getStatus() != null && SellerOrderStatus.CANCELLED.name().equalsIgnoreCase(so.getStatus())) {
                continue;
            }
            String sellerName = sellerNames.get(so.getSellerProfileId());
            List<OrderItemRecord> orderItems = itemsBySellerOrder.getOrDefault(so.getId(), List.of());
            for (OrderItemRecord oi : orderItems) {
                OrderRepository.ReorderProductMetadata meta = metadataMap.get(oi.getCatalogVariantId());
                String productSlug = meta != null ? meta.productSlug() : null;
                String rarity = meta != null ? meta.rarity() : null;

                String imageUrl = null;
                if (storageService != null && oi.getImageKeySnapshot() != null && !oi.getImageKeySnapshot().isBlank()) {
                    try {
                        imageUrl = storageService.presignDownload(oi.getImageKeySnapshot());
                    } catch (Exception e) {
                        log.warn("Failed to presign download for image key='{}'",
                                LogSanitizer.sanitize(oi.getImageKeySnapshot()), e);
                    }
                }

                items.add(new ReorderItemsResponse.ReorderItemDto(
                        oi.getId(),
                        oi.getCatalogVariantId(),
                        productSlug,
                        oi.getProductNameSnapshot(),
                        oi.getVariantLabelSnapshot(),
                        rarity,
                        imageUrl,
                        oi.getConditionSnapshot(),
                        oi.getUnitPrice(),
                        oi.getQuantity(),
                        sellerName
                ));
            }
        }

        return new ReorderItemsResponse(order.getId(), order.getOrderNumber(), items);
    }

    PageResponse<SellerOrderDetailsResponse> listSellerOrders(
            AuthPrincipal principal, String status, int page, int size) {
        support.requireAuthenticated(principal);
        SellerProfile profile = support.requireSellerProfile(principal.userId());
        String filter = normalizeStatusFilter(status);
        Paging paging = Paging.of(page, size);
        List<SellerOrderRecord> orders = support.orderRepository.findSellerOrdersBySellerProfileId(
                profile.id(), filter, paging.size(), paging.offset());
        return PageResponse.of(
                List.copyOf(mapper.sellerDetailsById(orders).values()),
                paging.page(),
                paging.size(),
                support.orderRepository.countSellerOrdersBySellerProfileId(profile.id(), filter));
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

    SellerOrderDetailsResponse getSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        support.requireAuthenticated(principal);
        SellerProfile profile = support.requireSellerProfile(principal.userId());
        SellerOrderRecord sellerOrder = support.orderRepository.findSellerOrderByIdAndSellerProfileId(sellerOrderId, profile.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return mapper.toSellerOrderDetailsResponse(sellerOrder);
    }
}
