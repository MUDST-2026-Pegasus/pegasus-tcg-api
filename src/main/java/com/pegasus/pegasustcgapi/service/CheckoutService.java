package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.CartSessionKey;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.OrderItemResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.CartPriceChangedException;
import com.pegasus.pegasustcgapi.exception.CartPriceChangedException.PriceChange;
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
import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.ShippingOption;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.port.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.port.PricingPort.ListingOffer;
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
import java.util.function.Supplier;
import org.jooq.JSONB;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles checkout, converting the active cart into a sales order and seller orders.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** {@code sales_order.idempotency_key} is varchar(64). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final CartRepository cartRepository;
    private final CartService cartService;
    private final OrderRepository orderRepository;
    private final AddressService addressService;
    private final SellerProfileRepository sellerProfileRepository;
    private final ShippingOptionService shippingOptionService;
    private final PricingPort pricingPort;
    private final InventoryPort inventoryPort;
    private final LedgerPort ledgerPort;
    private final ObjectMapper json;
    private final TransactionTemplate transactionTemplate;

    /**
     * The transaction manager is required rather than optional: checkout writes a
     * sales order, its sub-orders, their items and the reserved units, then empties
     * the cart. Half of that is not an order, so running these without a transaction
     * is never the right fallback.
     */
    @Autowired
    public CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            AddressService addressService,
            SellerProfileRepository sellerProfileRepository,
            @Autowired(required = false) ShippingOptionService shippingOptionService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this(cartRepository, cartService, orderRepository, addressService, sellerProfileRepository,
                shippingOptionService, pricingPort, inventoryPort, ledgerPort, json,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "a PlatformTransactionManager is required for checkout")));
    }

    /**
     * Test seam. Production wiring goes through the annotated constructor, which
     * insists on a transaction manager.
     */
    CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            AddressService addressService,
            SellerProfileRepository sellerProfileRepository,
            ShippingOptionService shippingOptionService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            TransactionTemplate transactionTemplate) {
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.addressService = addressService;
        this.sellerProfileRepository = sellerProfileRepository;
        this.shippingOptionService = shippingOptionService;
        this.pricingPort = pricingPort;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.json = json != null ? json : new ObjectMapper();
        this.transactionTemplate = transactionTemplate;
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
        if (trimmedKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            // The column is varchar(64); letting it through turns a bad header into a
            // Postgres 22001 that no catch below recognises, so a 400 is raised here.
            throw new BadRequestException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }

        String sessionKey = CartSessionKey.normalize(guestSessionKey);

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
            return executeInTransaction(() -> executeCheckout(principal, trimmedKey, sessionKey, request));
        } catch (CartPriceChangedException e) {
            // The checkout transaction rolled back, taking the refreshed prices with
            // it, so they are written again in one of their own. Without this the
            // buyer would be told the price moved and then meet the same refusal on
            // every retry.
            repinCartPrices(principal, e.changes());
            throw e;
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

    private void repinCartPrices(AuthPrincipal principal, List<PriceChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        executeInTransaction(() -> {
            cartRepository.findByUserId(principal.userId()).ifPresent(cart ->
                    changes.forEach(change ->
                            cartRepository.updateItemPrice(change.cartItemId(), cart.id(), change.newPrice())));
            return null;
        });
    }

    private <T> T executeInTransaction(Supplier<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> action.get());
        }
        // Only a unit test built this service without one; Spring cannot.
        log.warn("Running checkout without a transaction: no TransactionTemplate was configured");
        return action.get();
    }

    private CheckoutResponse executeCheckout(
            AuthPrincipal principal,
            String trimmedKey,
            String guestSessionKey,
            CheckoutRequest request) {

        // If guest session key is present, merge it into permanent user cart first
        cartService.mergeGuestCartIfPresent(principal, guestSessionKey);

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

        List<PriceChange> priceChanges = new ArrayList<>();
        for (CartItem item : items) {
            ListingOffer offer = offers.get(item.listingId());
            if (offer == null) {
                throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
            }
            if (!offer.purchasable()) {
                throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
            }
            if (buyerSellerProfileId != null && buyerSellerProfileId.equals(offer.sellerProfileId())) {
                throw new ConflictException(ErrorCode.CANNOT_BUY_OWN_LISTING);
            }
            if (offer.price().compareTo(item.unitPriceAtAdd()) != 0) {
                // Collected rather than thrown on the first one: the buyer should see
                // every line that moved, not be sent back one at a time.
                priceChanges.add(new PriceChange(
                        item.id(), item.listingId(), item.unitPriceAtAdd(), offer.price()));
            }
        }
        if (!priceChanges.isEmpty()) {
            throw new CartPriceChangedException(priceChanges);
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
                Long shippingOptionId,
                BigDecimal discount,
                BigDecimal grandTotal,
                CommissionQuote commission,
                BigDecimal netAmount) {}

        List<SellerSuborderComputation> sellerComputations = new ArrayList<>();

        for (Map.Entry<Long, List<CartItem>> entry : itemsBySeller.entrySet()) {
            long sellerProfileId = entry.getKey();
            List<CartItem> sellerItems = entry.getValue();

            BigDecimal sellerSubtotal = sellerItems.stream()
                    .map(i -> i.unitPriceAtAdd().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int sellerItemCount = sellerItems.stream().mapToInt(CartItem::quantity).sum();

            Long requestedOptionId = (request != null && request.shippingOptionBySeller() != null)
                    ? request.shippingOptionBySeller().get(sellerProfileId)
                    : null;
            Long optionId = resolveShippingOptionId(sellerProfileId, requestedOptionId);
            BigDecimal sellerShipping = shippingFor(sellerProfileId, requestedOptionId, optionId,
                    sellerItemCount, sellerSubtotal);

            BigDecimal sellerDiscount = BigDecimal.ZERO;
            BigDecimal sellerGrandTotal = sellerSubtotal.add(sellerShipping).subtract(sellerDiscount);

            // Commission is quoted on the items alone: a seller is not charged for postage.
            CommissionQuote commission = ledgerPort.quoteCommission(sellerProfileId, sellerSubtotal);
            if (commission == null) {
                commission = CommissionQuote.NONE;
            }
            BigDecimal sellerNet = sellerGrandTotal.subtract(commission.amount());

            sellerComputations.add(new SellerSuborderComputation(
                    sellerProfileId, sellerItems, sellerSubtotal, sellerShipping, optionId,
                    sellerDiscount, sellerGrandTotal, commission, sellerNet));

            itemsSubtotal = itemsSubtotal.add(sellerSubtotal);
            shippingTotal = shippingTotal.add(sellerShipping);
            discountTotal = discountTotal.add(sellerDiscount);
        }

        BigDecimal grandTotal = itemsSubtotal.add(shippingTotal).subtract(discountTotal);

        // 5. Address resolution & snapshot
        Long shippingAddressId = request != null ? request.shippingAddressId() : null;
        Address address;
        if (shippingAddressId != null) {
            address = addressService.get(principal.userId(), shippingAddressId);
        } else {
            List<Address> userAddresses = addressService.list(principal.userId());
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
        String orderNumber = orderRepository.nextOrderNumber();

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
                    sc.commission().ratePercent(),
                    sc.commission().amount(),
                    sc.netAmount(),
                    sc.shippingOptionId());

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
                BigDecimal lineTotal = sellerItem.unitPriceAtAdd().multiply(BigDecimal.valueOf(sellerItem.quantity()));
                BigDecimal unitCost = inventoryPort.averageUnitCost(
                        sc.sellerProfileId(), details.catalogVariantId(), offer.condition());

                OrderItemRecord orderItem = orderRepository.insertOrderItem(
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
                        details.imageKey());

                List<ReservedUnit> reservedUnits = heldUnitsByListing.get(sellerItem.listingId());
                List<Long> unitIds = reservedUnits != null ? reservedUnits.stream().map(ReservedUnit::unitId).toList() : List.of();
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

        // 8. Cleanup: the basket is emptied, not deleted — the buyer keeps using it.
        cartRepository.deleteItemsByCartId(cart.id());

        // 9. Return complete order outcome
        return getOrderDetails(salesOrder.getId(), false);
    }

    /**
     * Which of the seller's delivery choices this sub-order is posted with.
     *
     * <p>Resolved here as well as inside {@code feeFor} so the id can be frozen
     * onto {@code seller_order.shipping_option_id}: otherwise an order records what
     * postage cost but not what the buyer actually picked.
     */
    private Long resolveShippingOptionId(long sellerProfileId, Long requestedOptionId) {
        if (requestedOptionId != null || shippingOptionService == null) {
            return requestedOptionId;
        }
        return shippingOptionService.listActive(sellerProfileId).stream()
                .findFirst()
                .map(ShippingOption::id)
                .orElse(null);
    }

    /**
     * What this seller charges to post their part of the basket.
     *
     * <p>The two ways {@code feeFor} can fail are not the same failure. A buyer who
     * named an option that is not this seller's gets a 400 — silently charging zero
     * would let anyone post for free. A seller who has configured none is a gap in
     * their shop, not in the request, so the order goes through at zero and says so
     * in the log.
     */
    private BigDecimal shippingFor(
            long sellerProfileId, Long requestedOptionId, Long optionId, int itemCount, BigDecimal itemsSubtotal) {
        if (shippingOptionService == null) {
            return BigDecimal.ZERO;
        }
        try {
            return shippingOptionService.feeFor(sellerProfileId, optionId, itemCount, itemsSubtotal);
        } catch (NotFoundException e) {
            if (requestedOptionId != null) {
                throw new BadRequestException(ErrorCode.SHIPPING_OPTION_INVALID,
                        "Shipping option " + requestedOptionId + " is not offered by seller " + sellerProfileId);
            }
            log.warn("Seller {} has no active shipping option; charging no shipping on this sub-order",
                    sellerProfileId);
            return BigDecimal.ZERO;
        }
    }

    public CheckoutResponse getOrderDetails(long salesOrderId) {
        return getOrderDetails(salesOrderId, false);
    }

    public CheckoutResponse getOrderDetails(long salesOrderId, boolean replayed) {
        SalesOrderRecord salesOrder = orderRepository.findSalesOrderById(salesOrderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        List<SellerOrderRecord> sellerOrders = orderRepository.findSellerOrdersBySalesOrderId(salesOrderId);

        // Three queries whatever the basket holds: one per level, not one per row.
        List<Long> sellerOrderIds = sellerOrders.stream().map(SellerOrderRecord::getId).toList();
        Map<Long, List<OrderItemRecord>> itemsBySellerOrder =
                orderRepository.findOrderItemsBySellerOrderIds(sellerOrderIds);
        List<Long> orderItemIds = sellerOrders.stream()
                .flatMap(so -> itemsBySellerOrder.getOrDefault(so.getId(), List.of()).stream())
                .map(OrderItemRecord::getId)
                .toList();
        Map<Long, List<Long>> unitIdsByItem = orderRepository.findUnitIdsByOrderItemIds(orderItemIds);

        List<SellerOrderResponse> sellerResponses = new ArrayList<>();
        for (SellerOrderRecord so : sellerOrders) {
            List<OrderItemResponse> itemResponses = new ArrayList<>();

            for (OrderItemRecord oi : itemsBySellerOrder.getOrDefault(so.getId(), List.of())) {
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
                        unitIdsByItem.getOrDefault(oi.getId(), List.of())));
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

    /**
     * The address exactly as it read when the order was placed.
     *
     * <p>Built by the JSON writer rather than by string concatenation: a buyer's
     * address is free text, and a backslash or a newline in it would otherwise
     * either break the document — Postgres rejects a malformed jsonb and the buyer
     * can never check out again — or quietly change what the stored record says.
     */
    private JSONB createAddressSnapshot(Address a) {
        if (a == null) {
            return JSONB.valueOf("{}");
        }
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
