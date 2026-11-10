package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.cart.CartItem;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository.ListingSnapshotDetails;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository.SaleSnapshot;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Hold;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.LivePrice;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Plan;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;

/** Persists the priced checkout inside the caller's transaction. */
@Service
class CheckoutOrderPersistenceService {
    private final OrderRepository orderRepository;
    private final InventoryPort inventoryPort;

    CheckoutOrderPersistenceService(OrderRepository orderRepository, InventoryPort inventoryPort) {
        this.orderRepository = orderRepository;
        this.inventoryPort = inventoryPort;
    }

    record SellerSuborderComputation(
            long sellerProfileId,
            List<CartItem> items,
            BigDecimal subtotal,
            BigDecimal shippingFee,
            Long shippingOptionId,
            BigDecimal sellerDiscount,
            BigDecimal platformDiscount,
            BigDecimal grandTotal,
            CommissionQuote commission,
            BigDecimal netAmount,
            BigDecimal saleSubsidy) {}

    Optional<SalesOrderRecord> findByIdempotencyKey(String key) {
        return orderRepository.findSalesOrderByIdempotencyKey(key);
    }

    String nextOrderNumber() {
        return orderRepository.nextOrderNumber();
    }

    record Persisted(SalesOrderRecord order, Map<Long, Long> sellerOrderIds) {}

    Persisted persist(String orderNumber, AuthPrincipal principal, BigDecimal itemsSubtotal,
            BigDecimal shippingTotal, BigDecimal discountTotal, BigDecimal grandTotal,
            Long shippingAddressId, JSONB addressSnapshot, String buyerNote, String trimmedKey,
            List<Long> listingIds, List<SellerSuborderComputation> sellerComputations,
            Map<Long, ListingOffer> offers, CheckoutPromotionService.Evaluation promotion,
            Plan plan, Map<Long, Hold> saleHolds, Map<Long, List<ReservedUnit>> heldUnitsByListing) {
        // 6. Insert Sales Order
        SalesOrderRecord salesOrder = orderRepository.insertSalesOrder(
                orderNumber,
                principal.userId(),
                "THB",
                itemsSubtotal,
                shippingTotal,
                discountTotal,
                grandTotal,
                shippingAddressId,
                addressSnapshot,
                buyerNote,
                trimmedKey);

        // 7. Insert Seller Orders & Order Items & Units
        Map<Long, ListingSnapshotDetails> snapshotDetails = orderRepository.findListingDetails(listingIds);
        Map<Long, Long> sellerOrderIdBySeller = new LinkedHashMap<>();
        // A listing split over two lines hands each its own cards, in reservation order.
        Map<Long, Integer> unitsTaken = new HashMap<>();

        for (SellerSuborderComputation sc : sellerComputations) {
            String sellerOrderNumber = orderNumber + "-S" + sc.sellerProfileId();

            boolean discounted = sc.sellerDiscount().signum() != 0 || sc.platformDiscount().signum() != 0;
            // Without a code or a Pegasus campaign this is the insert checkout has always made.
            SellerOrderRecord sellerOrder = sc.saleSubsidy().signum() != 0
                    ? orderRepository.insertSellerOrder(
                            salesOrder.getId(),
                            sc.sellerProfileId(),
                            sellerOrderNumber,
                            sc.subtotal(),
                            sc.shippingFee(),
                            sc.sellerDiscount(),
                            sc.platformDiscount(),
                            sc.saleSubsidy(),
                            sc.grandTotal(),
                            sc.commission().ratePercent(),
                            sc.commission().amount(),
                            sc.netAmount(),
                            sc.shippingOptionId())
                    : !discounted
                    ? orderRepository.insertSellerOrder(
                            salesOrder.getId(),
                            sc.sellerProfileId(),
                            sellerOrderNumber,
                            sc.subtotal(),
                            sc.shippingFee(),
                            BigDecimal.ZERO,
                            sc.grandTotal(),
                            sc.commission().ratePercent(),
                            sc.commission().amount(),
                            sc.netAmount(),
                            sc.shippingOptionId())
                    : orderRepository.insertSellerOrder(
                            salesOrder.getId(),
                            sc.sellerProfileId(),
                            sellerOrderNumber,
                            sc.subtotal(),
                            sc.shippingFee(),
                            sc.sellerDiscount(),
                            sc.platformDiscount(),
                            sc.grandTotal(),
                            sc.commission().ratePercent(),
                            sc.commission().amount(),
                            sc.netAmount(),
                            sc.shippingOptionId());
            sellerOrderIdBySeller.put(sc.sellerProfileId(), sellerOrder.getId());

            // The state machine starts here, so the audit trail does too [CR-7].
            orderRepository.insertStatusHistory(
                    sellerOrder.getId(),
                    null,
                    SellerOrderStatus.PENDING_PAYMENT.name(),
                    principal.userId(),
                    "Order placed");

            for (CartItem sellerItem : sc.items()) {
                ListingSnapshotDetails details = snapshotDetails.get(sellerItem.listingId());
                if (details == null) {
                    // The listing priced and reserved a moment ago has no catalogue row.
                    // Guessing a variant id here would record the buyer as having bought a
                    // different card — and later hand them that card. Roll the order back.
                    throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE,
                            "Listing " + sellerItem.listingId() + " has no catalogue details to snapshot");
                }

                ListingOffer offer = offers.get(sellerItem.listingId());
                BigDecimal lineTotal = CheckoutPricingCalculator.lineTotal(sellerItem);
                BigDecimal unitCost = inventoryPort.averageUnitCost(
                        sc.sellerProfileId(), details.catalogVariantId(), offer.condition());
                BigDecimal allocated = promotion == null ? BigDecimal.ZERO
                        : promotion.result().discountByLine().getOrDefault(sellerItem.id(), BigDecimal.ZERO);

                BigDecimal lineDiscount = promotion != null
                        ? promotion.result().sellerDiscountByLine().getOrDefault(sellerItem.id(), BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                LivePrice sale = plan.saleOf(sellerItem);
                SaleSnapshot snapshot = sale == null ? null : new SaleSnapshot(
                        offer.listPrice(),
                        sale.price().discountAmount(),
                        sale.sale().id(),
                        sale.price().fundedBy().name(),
                        saleHolds.getOrDefault(sellerItem.id(), Hold.NONE).quota(),
                        saleHolds.getOrDefault(sellerItem.id(), Hold.NONE).subsidy());
                OrderItemRecord orderItem = snapshot != null
                        ? orderRepository.insertOrderItem(
                                sellerOrder.getId(),
                                sellerItem.listingId(),
                                details.catalogVariantId(),
                                sellerItem.quantity(),
                                sellerItem.unitPriceAtAdd(),
                                lineTotal,
                                allocated,
                                lineDiscount,
                                unitCost,
                                details.productName(),
                                details.variantLabel(),
                                details.condition(),
                                details.gameName(),
                                details.imageKey(),
                                snapshot)
                        : allocated.signum() == 0
                        ? orderRepository.insertOrderItem(
                                sellerOrder.getId(),
                                sellerItem.listingId(),
                                details.catalogVariantId(),
                                sellerItem.quantity(),
                                sellerItem.unitPriceAtAdd(),
                                lineTotal,
                                unitCost,
                                details.productName(),
                                details.variantLabel(),
                                details.condition(),
                                details.gameName(),
                                details.imageKey())
                        : orderRepository.insertOrderItem(
                                sellerOrder.getId(),
                                sellerItem.listingId(),
                                details.catalogVariantId(),
                                sellerItem.quantity(),
                                sellerItem.unitPriceAtAdd(),
                                lineTotal,
                                allocated,
                                lineDiscount,
                                unitCost,
                                details.productName(),
                                details.variantLabel(),
                                details.condition(),
                                details.gameName(),
                                details.imageKey());

                List<ReservedUnit> reservedUnits = heldUnitsByListing.get(sellerItem.listingId());
                List<Long> listingUnits = reservedUnits != null
                        ? reservedUnits.stream().map(ReservedUnit::unitId).toList() : List.of();
                int fromUnit = unitsTaken.getOrDefault(sellerItem.listingId(), 0);
                int toUnit = Math.min(fromUnit + sellerItem.quantity(), listingUnits.size());
                List<Long> unitIds = listingUnits.subList(fromUnit, toUnit);
                unitsTaken.put(sellerItem.listingId(), toUnit);
                if (unitIds.size() != sellerItem.quantity()) {
                    // order_item_unit names the exact card each line is for [RQ-9]; a line
                    // with the wrong number of cards is an order nobody can fulfil, and no
                    // constraint can catch it, so it is checked here.
                    throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK,
                            "Listing " + sellerItem.listingId() + " held " + unitIds.size()
                                    + " cards for an order line of " + sellerItem.quantity());
                }
                orderRepository.insertOrderItemUnits(orderItem.getId(), unitIds);
            }
        }

        return new Persisted(salesOrder, sellerOrderIdBySeller);
    }
}
