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
import java.util.function.Supplier;
import org.jooq.JSONB;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final ShippingOptionService shippingOptionService;
    private final PricingPort pricingPort;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            AddressRepository addressRepository,
            SellerProfileRepository sellerProfileRepository,
            @Autowired(required = false) ShippingOptionService shippingOptionService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.addressRepository = addressRepository;
        this.sellerProfileRepository = sellerProfileRepository;
        this.shippingOptionService = shippingOptionService;
        this.pricingPort = pricingPort;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            AddressRepository addressRepository,
            SellerProfileRepository sellerProfileRepository,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort) {
        this(cartRepository, cartService, orderRepository, addressRepository, sellerProfileRepository,
                null, pricingPort, inventoryPort, ledgerPort, null);
    }

    /**
     * Executes the checkout process atomically.
     * Replays existing orders for the same buyer, rejects key conflicts from others,
     * and recovers gracefully from concurrent race conditions under the same idempotency key.
     */
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

        // Fast-path lookup
        Optional<SalesOrderRecord> existingOrderOpt = orderRepository.findSalesOrderByIdempotencyKey(trimmedKey);
        if (existingOrderOpt.isPresent()) {
            SalesOrderRecord existingOrder = existingOrderOpt.get();
            if (Objects.equals(existingOrder.getBuyerId(), principal.userId())) {
                return getOrderDetails(existingOrder.getId(), true);
            } else {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
        }

        // 2. Atomic checkout execution
        try {
            return executeInTransaction(() -> executeCheckout(principal, trimmedKey, guestSessionKey, request));
        } catch (DuplicateKeyException | IntegrityConstraintViolationException e) {
            // Race condition: another concurrent transaction committed with the same key.
            // This transaction rolled back. Re-query winner's committed order.
            return orderRepository.findSalesOrderByIdempotencyKey(trimmedKey)
                    .map(winnerOrder -> {
                        if (Objects.equals(winnerOrder.getBuyerId(), principal.userId())) {
                            return getOrderDetails(winnerOrder.getId(), true);
                        } else {
                            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                        }
                    })
                    .orElseThrow(() -> e);
        }
    }

    private <T> T executeInTransaction(Supplier<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private CheckoutResponse executeCheckout(
            AuthPrincipal principal,
            String trimmedKey,
            String guestSessionKey,
            CheckoutRequest request) {

        // If guest session key is present, merge it into permanent user cart first
        if (guestSessionKey != null && !guestSessionKey.isBlank()) {
            cartService.getCart(principal, guestSessionKey);
        }

        // 1. Retrieve user's cart and items
        Cart cart = cartRepository.findByUserId(principal.userId()).orElse(null);
        if (cart == null) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }
        List<CartItem> items = cartRepository.findItemsByCartId(cart.id());
        if (items.isEmpty()) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }

        // 2. Batch validate pricing and purchasability
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
            quantityByListing.merge(item.listingId(), item.quantity(), Integer::sum);
        }
        Map<Long, List<ReservedUnit>> heldUnitsByListing = inventoryPort.reserve(quantityByListing);

        // 4. Group cart items by sellerProfileId & calculate subtotals, shipping, commission
        Map<Long, List<CartItem>> itemsBySeller = new LinkedHashMap<>();
        for (CartItem item : items) {
            ListingOffer offer = offers.get(item.listingId());
            itemsBySeller.computeIfAbsent(offer.sellerProfileId(), k -> new ArrayList<>()).add(item);
        }

        BigDecimal itemsSubtotal = BigDecimal.ZERO;
        BigDecimal shippingTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;

        record SellerSuborderComputation(
                long sellerProfileId,
                List<CartItem> items,
                BigDecimal subtotal,
                BigDecimal shippingFee,
                BigDecimal discount,
                BigDecimal grandTotal,
                BigDecimal commission,
                BigDecimal netAmount) {}

        List<SellerSuborderComputation> sellerComputations = new ArrayList<>();

        for (Map.Entry<Long, List<CartItem>> entry : itemsBySeller.entrySet()) {
            long sellerProfileId = entry.getKey();
            List<CartItem> sellerItems = entry.getValue();

            BigDecimal sellerSubtotal = sellerItems.stream()
                    .map(i -> i.unitPriceAtAdd().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int sellerItemCount = sellerItems.stream().mapToInt(CartItem::quantity).sum();

            BigDecimal sellerShipping = BigDecimal.ZERO;
            Long optionId = (request != null && request.shippingOptionBySeller() != null)
                    ? request.shippingOptionBySeller().get(sellerProfileId)
                    : null;
            if (shippingOptionService != null) {
                try {
                    sellerShipping = shippingOptionService.feeFor(sellerProfileId, optionId, sellerItemCount, sellerSubtotal);
                } catch (NotFoundException e) {
                    sellerShipping = BigDecimal.ZERO;
                }
            }
            BigDecimal sellerDiscount = BigDecimal.ZERO;
            BigDecimal sellerGrandTotal = sellerSubtotal.add(sellerShipping).subtract(sellerDiscount);

            BigDecimal commission = ledgerPort.quoteCommission(sellerSubtotal);
            if (commission == null) {
                commission = BigDecimal.ZERO;
            }
            BigDecimal sellerNet = sellerGrandTotal.subtract(commission);

            sellerComputations.add(new SellerSuborderComputation(
                    sellerProfileId, sellerItems, sellerSubtotal, sellerShipping, sellerDiscount, sellerGrandTotal, commission, sellerNet));

            itemsSubtotal = itemsSubtotal.add(sellerSubtotal);
            shippingTotal = shippingTotal.add(sellerShipping);
            discountTotal = discountTotal.add(sellerDiscount);
        }

        BigDecimal grandTotal = itemsSubtotal.add(shippingTotal).subtract(discountTotal);

        // 5. Address resolution & snapshot
        Long shippingAddressId = request != null ? request.shippingAddressId() : null;
        Address address = null;
        if (shippingAddressId != null) {
            address = addressRepository.findByIdAndUserId(shippingAddressId, principal.userId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND));
        } else {
            List<Address> userAddresses = addressRepository.findByUserId(principal.userId());
            address = userAddresses.stream()
                    .filter(Address::defaultShipping)
                    .findFirst()
                    .or(() -> userAddresses.stream().findFirst())
                    .orElse(null);
            if (address != null) {
                shippingAddressId = address.id();
            }
        }
        JSONB addressSnapshot = createAddressSnapshot(address);
        String buyerNote = request != null ? request.buyerNote() : null;
        String orderNumber = "ORD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

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

        for (SellerSuborderComputation sc : sellerComputations) {
            String sellerOrderNumber = orderNumber + "-S" + sc.sellerProfileId();

            SellerOrderRecord sellerOrder = orderRepository.insertSellerOrder(
                    salesOrder.getId(),
                    sc.sellerProfileId(),
                    sellerOrderNumber,
                    sc.subtotal(),
                    sc.shippingFee(),
                    sc.discount(),
                    sc.grandTotal(),
                    sc.commission(),
                    sc.netAmount());

            for (CartItem sellerItem : sc.items()) {
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
                List<Long> unitIds = reservedUnits != null ? reservedUnits.stream().map(ReservedUnit::unitId).toList() : List.of();
                orderRepository.insertOrderItemUnits(orderItem.getId(), unitIds);
            }
        }

        // 8. Cleanup User Cart
        if (cart != null) {
            cartRepository.deleteCart(cart.id());
        }

        // 9. Return complete order outcome
        return getOrderDetails(salesOrder.getId(), false);
    }

    public CheckoutResponse getOrderDetails(long salesOrderId) {
        return getOrderDetails(salesOrderId, false);
    }

    public CheckoutResponse getOrderDetails(long salesOrderId, boolean replayed) {
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
                sellerResponses,
                replayed);
    }

    private JSONB createAddressSnapshot(Address a) {
        if (a == null) {
            return JSONB.valueOf("{}");
        }
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
