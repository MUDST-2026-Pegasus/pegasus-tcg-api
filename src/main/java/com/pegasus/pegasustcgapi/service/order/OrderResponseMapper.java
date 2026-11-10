package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;

import com.pegasus.pegasustcgapi.dto.promotion.AppliedPromotion;
import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.OrderItemDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.OrderStatusHistoryResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipmentResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ShipmentRecord;
import com.pegasus.pegasustcgapi.model.promotion.Promotion;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.promotion.PromotionRedemptionRepository;
import com.pegasus.pegasustcgapi.dto.order.SaleDiscount;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds order responses without changing the caller's transaction or query order. */
final class OrderResponseMapper {
    private final OrderRepository orderRepository;
    private final PlatformSettingService platformSettingService;
    private final StorageService storageService;
    private CheckoutPromotionService checkoutPromotions;

    OrderResponseMapper(OrderRepository orderRepository, PlatformSettingService platformSettingService, StorageService storageService) {
        this.orderRepository = orderRepository;
        this.platformSettingService = platformSettingService;
        this.storageService = storageService;
    }

    void setCheckoutPromotions(CheckoutPromotionService checkoutPromotions) {
        this.checkoutPromotions = checkoutPromotions;
    }

    private Map<Long, List<PromotionRedemptionRepository.Applied>> appliedPromotions(List<Long> salesOrderIds) {
        return checkoutPromotions == null ? Map.of() : checkoutPromotions.appliedBySalesOrder(salesOrderIds);
    }

    private static List<AppliedPromotion> shopShare(SellerOrderRecord so,
            List<PromotionRedemptionRepository.Applied> applied) {
        List<AppliedPromotion> out = new ArrayList<>();
        for (PromotionRedemptionRepository.Applied a : applied) {
            if ("SELLER".equals(a.ownerType()) && Objects.equals(a.sellerOrderId(), so.getId())) {
                out.add(toApplied(a));
            } else if ("PLATFORM".equals(a.ownerType()) && money(so.getPlatformDiscountAmount()).signum() > 0) {
                out.add(new AppliedPromotion(a.code(), Promotion.OwnerType.PLATFORM, null,
                        money(so.getPlatformDiscountAmount()), a.status()));
            }
        }
        return out;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private static AppliedPromotion toApplied(PromotionRedemptionRepository.Applied a) {
        return new AppliedPromotion(a.code(), Promotion.OwnerType.valueOf(a.ownerType()), a.sellerProfileId(),
                a.amount(), a.status());
    }

    public OrderDetailsResponse toOrderDetailsResponse(long salesOrderId) {
        SalesOrderRecord so = orderRepository.findSalesOrderById(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        return toOrderDetailsResponse(so);
    }

    public OrderDetailsResponse toOrderDetailsResponse(SalesOrderRecord so) {
        return toOrderDetailsResponses(List.of(so)).getFirst();
    }

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
        Set<Long> underReview = orderRepository.findSalesOrderIdsWithPaymentUnderReview(salesOrderIds);
        Map<Long, List<PromotionRedemptionRepository.Applied>> appliedBySalesOrder = appliedPromotions(salesOrderIds);
        Map<Long, String> auctionNumbers = orderRepository.findAuctionNumbers(salesOrders.stream()
                .map(SalesOrderRecord::getAuctionId).filter(java.util.Objects::nonNull).toList());
        Duration cancelWindow = platformSettingService.getHours(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS);

        return salesOrders.stream().map(so -> {
            List<SellerOrderDetailsResponse> sellerResponses =
                    sellerOrdersByParent.getOrDefault(so.getId(), List.of()).stream()
                            .map(child -> sellerResponsesById.get(child.getId()))
                            .toList();
            List<AppliedPromotion> promotions = appliedBySalesOrder.getOrDefault(so.getId(), List.of()).stream()
                    .map(OrderResponseMapper::toApplied)
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
                    sellerResponses,
                    underReview.contains(so.getId()),
                    so.getPaidAt() != null ? so.getPaidAt().plus(cancelWindow) : null,
                    sellerResponses.stream().map(SellerOrderDetailsResponse::sellerDiscount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    sellerResponses.stream().map(SellerOrderDetailsResponse::platformDiscount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    promotions,
                    so.getPaidAt() == null && "PENDING_PAYMENT".equals(so.getStatus()) && !promotions.isEmpty(),
                    so.getSource(), so.getAuctionId(),
                    so.getAuctionId() == null ? null : auctionNumbers.get(so.getAuctionId()));
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

    public Map<Long, SellerOrderDetailsResponse> toSellerOrderDetailsResponses(List<Long> sellerOrderIds) {
        return sellerDetailsById(orderRepository.findSellerOrdersByIds(sellerOrderIds));
    }

    Map<Long, SellerOrderDetailsResponse> sellerDetailsById(List<SellerOrderRecord> sellerOrders) {
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

        List<Long> profileIds = sellerOrders.stream().map(SellerOrderRecord::getSellerProfileId).distinct().toList();
        Map<Long, OrderRepository.SellerIdentity> sellerIdentities = orderRepository.findSellerIdentitiesByProfileIds(profileIds);
        Map<Long, List<PromotionRedemptionRepository.Applied>> appliedBySalesOrder = appliedPromotions(
                sellerOrders.stream().map(SellerOrderRecord::getSalesOrderId).distinct().toList());

        Map<Long, SellerOrderDetailsResponse> responses = new LinkedHashMap<>();
        for (SellerOrderRecord so : sellerOrders) {
            List<OrderItemDetailsResponse> itemResponses =
                    itemsBySellerOrder.getOrDefault(so.getId(), List.of()).stream()
                            .map(oi -> {
                                String imageUrl = null;
                                if (storageService != null && oi.getImageKeySnapshot() != null && !oi.getImageKeySnapshot().isBlank()) {
                                    try {
                                        imageUrl = storageService.presignDownload(oi.getImageKeySnapshot());
                                    } catch (Exception e) {
                                        // Ignore storage failure, keep imageUrl null
                                    }
                                }
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
                                        imageUrl,
                                        unitIdsByItem.getOrDefault(oi.getId(), List.of()),
                                        money(oi.getDiscountAllocated()),
                                        money(oi.getSellerDiscountAllocated()),
                                        money(oi.getDiscountAllocated())
                                                .subtract(money(oi.getSellerDiscountAllocated())),
                                        oi.getLineTotal().subtract(money(oi.getDiscountAllocated()))
                                                .divide(BigDecimal.valueOf(oi.getQuantity()), 2,
                                                        RoundingMode.HALF_UP),
                                        SaleDiscount.listPrice(oi),
                                        SaleDiscount.of(oi));
                            })
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

            List<AppliedPromotion> promotions = shopShare(so,
                    appliedBySalesOrder.getOrDefault(so.getSalesOrderId(), List.of()));
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
                    historyResponses,
                    sellerIdentities.containsKey(so.getSellerProfileId())
                            ? Objects.requireNonNullElse(sellerIdentities.get(so.getSellerProfileId()).displayName(),
                                    sellerIdentities.get(so.getSellerProfileId()).username()) : null,
                    money(so.getSellerDiscountAmount()),
                    money(so.getPlatformDiscountAmount()),
                    // A Pegasus code and a Pegasus campaign are both paid back at release [TCG-680].
                    money(so.getPlatformDiscountAmount()).add(money(so.getSaleSubsidyAmount())),
                    so.getItemsSubtotal().add(money(so.getSaleSubsidyAmount()))
                            .subtract(money(so.getSellerDiscountAmount())),
                    promotions,
                    SellerOrderStatus.PENDING_PAYMENT.name().equals(so.getStatus()) && !promotions.isEmpty(),
                    sellerIdentities.containsKey(so.getSellerProfileId())
                            ? sellerIdentities.get(so.getSellerProfileId()).username() : null));
        }
        return responses;
    }
}
