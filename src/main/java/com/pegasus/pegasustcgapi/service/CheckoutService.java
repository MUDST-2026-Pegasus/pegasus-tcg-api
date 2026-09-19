package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.OrderItemResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.port.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.AddressRepository;
import com.pegasus.pegasustcgapi.repository.CartRepository;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.OrderRepository.ListingSnapshotDetails;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles checkout, converting the active cart into a sales order and seller orders.
 */
@Service
public class CheckoutService {

    private final CartRepository cartRepository;
    private final CartService cartService;
    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final PricingPort pricingPort;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;

    public CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            AddressRepository addressRepository,
            SellerProfileRepository sellerProfileRepository,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort) {
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.addressRepository = addressRepository;
        this.sellerProfileRepository = sellerProfileRepository;
        this.pricingPort = pricingPort;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
    }

    /**
     * Executes the checkout process atomically within a strict transaction.
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckoutResponse checkout(
            AuthPrincipal principal,
            String idempotencyKey,
            String guestSessionKey,
            CheckoutRequest request) {

        if (principal == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }

        // 1. Idempotency validation
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        String trimmedKey = idempotencyKey.trim();
        Optional<SalesOrderRecord> existingOrderOpt = orderRepository.findSalesOrderByIdempotencyKey(trimmedKey);
        if (existingOrderOpt.isPresent()) {
            SalesOrderRecord existingOrder = existingOrderOpt.get();
            if (Objects.equals(existingOrder.getBuyerId(), principal.userId())) {
                return getOrderDetails(existingOrder.getId());
            } else {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
        }

        // If guest session key is present, merge it into permanent user cart first
        if (guestSessionKey != null && !guestSessionKey.isBlank()) {
            cartService.getCart(principal, guestSessionKey);
        }

        // 2. Retrieve user's cart and items
        Cart cart = cartRepository.findByUserId(principal.userId()).orElse(null);
        List<CartItem> items = cart != null ? cartRepository.findItemsByCartId(cart.id()) : List.of();
        if (items.isEmpty()) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }

        // Batch validate pricing and purchasability
        List<Long> listingIds = items.stream().map(CartItem::listingId).distinct().toList();
        Map<Long, ListingOffer> offers = pricingPort.offers(listingIds);

        Optional<SellerProfile> buyerSellerProfile = sellerProfileRepository.findByUserId(principal.userId());
        Long buyerSellerProfileId = buyerSellerProfile.map(SellerProfile::id).orElse(null);

        for (CartItem item : items) {
            ListingOffer offer = offers.get(item.listingId());
            if (offer == null) {
                throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
            }
            if (offer.price().compareTo(item.unitPriceAtAdd()) != 0) {
                throw new ConflictException(ErrorCode.CART_PRICE_CHANGED);
            }
            if (!offer.purchasable()) {
                throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
            }
            if (buyerSellerProfileId != null && buyerSellerProfileId.equals(offer.sellerProfileId())) {
                throw new ConflictException(ErrorCode.CANNOT_BUY_OWN_LISTING);
            }
        }

        // 3. Inventory Reservation (strictly rolls back on failure)
        Map<Long, Integer> quantityByListing = new LinkedHashMap<>();
        for (CartItem item : items) {
            quantityByListing.put(item.listingId(), item.quantity());
        }
        Map<Long, List<ReservedUnit>> heldUnitsByListing = inventoryPort.reserve(quantityByListing);

        // 4. Sales Order Creation
        BigDecimal itemsSubtotal = items.stream()
                .map(i -> i.unitPriceAtAdd().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shippingTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = itemsSubtotal.add(shippingTotal).subtract(discountTotal);

        String orderNumber = "ORD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Long shippingAddressId = request != null ? request.shippingAddressId() : null;
        String buyerNote = request != null ? request.buyerNote() : null;
        JSONB addressSnapshot = createAddressSnapshot(principal.userId(), shippingAddressId);

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

        // 5. Group cart items by sellerProfileId & Create Seller Orders + Order Items
        Map<Long, List<CartItem>> itemsBySeller = new LinkedHashMap<>();
        for (CartItem item : items) {
            ListingOffer offer = offers.get(item.listingId());
            itemsBySeller.computeIfAbsent(offer.sellerProfileId(), k -> new ArrayList<>()).add(item);
        }

        Map<Long, ListingSnapshotDetails> snapshotDetails = orderRepository.findListingDetails(listingIds);

        for (Map.Entry<Long, List<CartItem>> entry : itemsBySeller.entrySet()) {
            long sellerProfileId = entry.getKey();
            List<CartItem> sellerItems = entry.getValue();

            BigDecimal sellerSubtotal = sellerItems.stream()
                    .map(i -> i.unitPriceAtAdd().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sellerShipping = BigDecimal.ZERO;
            BigDecimal sellerDiscount = BigDecimal.ZERO;
            BigDecimal sellerGrandTotal = sellerSubtotal.add(sellerShipping).subtract(sellerDiscount);

            BigDecimal commission = ledgerPort.quoteCommission(sellerSubtotal);
            if (commission == null) {
                commission = BigDecimal.ZERO;
            }
            BigDecimal sellerNet = sellerGrandTotal.subtract(commission);

            String sellerOrderNumber = orderNumber + "-S" + sellerProfileId;

            SellerOrderRecord sellerOrder = orderRepository.insertSellerOrder(
                    salesOrder.getId(),
                    sellerProfileId,
                    sellerOrderNumber,
                    sellerSubtotal,
                    sellerShipping,
                    sellerDiscount,
                    sellerGrandTotal,
                    commission,
                    sellerNet);

            for (CartItem sellerItem : sellerItems) {
                ListingSnapshotDetails details = snapshotDetails.get(sellerItem.listingId());
                String productName = details != null ? details.productName() : "Trading Card";
                String variantLabel = details != null ? details.variantLabel() : "Standard";
                String condition = details != null ? details.condition() : "NM";
                String gameName = details != null ? details.gameName() : null;
                String imageKey = details != null ? details.imageKey() : null;
                long variantId = details != null ? details.catalogVariantId() : 1L;

                BigDecimal lineTotal = sellerItem.unitPriceAtAdd().multiply(BigDecimal.valueOf(sellerItem.quantity()));

                OrderItemRecord orderItem = orderRepository.insertOrderItem(
                        sellerOrder.getId(),
                        sellerItem.listingId(),
                        variantId,
                        sellerItem.quantity(),
                        sellerItem.unitPriceAtAdd(),
                        lineTotal,
                        productName,
                        variantLabel,
                        condition,
                        gameName,
                        imageKey);

                List<ReservedUnit> reservedUnits = heldUnitsByListing.get(sellerItem.listingId());
                List<Long> unitIds = reservedUnits.stream().map(ReservedUnit::unitId).toList();
                orderRepository.insertOrderItemUnits(orderItem.getId(), unitIds);
            }
        }

        // 6. Cleanup User Cart
        cartRepository.deleteCart(cart.id());

        // 7. Return complete order outcome
        return getOrderDetails(salesOrder.getId());
    }

    public CheckoutResponse getOrderDetails(long salesOrderId) {
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderById(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrderId);
        List<SellerOrderResponse> sellerResponses = new ArrayList<>();

        for (SellerOrderRecord so : sellerOrders) {
            List<OrderItemRecord> items = orderRepository.findOrderItemsBySellerOrderId(so.getId());
            List<OrderItemResponse> itemResponses = new ArrayList<>();

            for (OrderItemRecord oi : items) {
                List<Long> unitIds = orderRepository.findUnitIdsByOrderItemId(oi.getId());
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
                        unitIds));
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
                    itemResponses));
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
                sellerResponses);
    }

    private JSONB createAddressSnapshot(long userId, Long shippingAddressId) {
        if (shippingAddressId == null) {
            return JSONB.valueOf("{}");
        }
        Optional<Address> addressOpt = addressRepository.findByIdAndUserId(shippingAddressId, userId);
        if (addressOpt.isEmpty()) {
            return JSONB.valueOf("{}");
        }
        Address a = addressOpt.get();
        String json = """
                {"id":%d,"recipientName":"%s","phone":"%s","line1":"%s","line2":"%s","subdistrict":"%s","district":"%s","province":"%s","postalCode":"%s","countryCode":"%s"}
                """.formatted(
                a.id(),
                escape(a.recipientName()),
                escape(a.phone()),
                escape(a.line1()),
                escape(a.line2()),
                escape(a.subdistrict()),
                escape(a.district()),
                escape(a.province()),
                escape(a.postalCode()),
                escape(a.countryCode()));
        return JSONB.valueOf(json);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
