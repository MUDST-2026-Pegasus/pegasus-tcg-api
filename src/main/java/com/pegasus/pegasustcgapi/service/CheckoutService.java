package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.CartSessionKey;
import com.pegasus.pegasustcgapi.dto.CheckoutPromotionViews;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.PromoPreviewRequest;
import com.pegasus.pegasustcgapi.dto.ShippingQuoteRequest;
import com.pegasus.pegasustcgapi.dto.ShippingQuoteResponse;
import com.pegasus.pegasustcgapi.dto.ShippingQuoteOptionResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.OrderItemResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
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
import com.pegasus.pegasustcgapi.model.Promotion;
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
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jooq.JSONB;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
    private final StorageService storageService;
    private final ObjectMapper json;
    private final TransactionTemplate transactionTemplate;
    /** Null only in unit tests built without it; such a service refuses discount codes. */
    private final CheckoutPromotionService checkoutPromotions;

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
            ShippingOptionService shippingOptionService,
            @Autowired(required = false) StorageService storageService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            CheckoutPromotionService checkoutPromotions) {
        this(cartRepository, cartService, orderRepository, addressService, sellerProfileRepository,
                shippingOptionService, storageService, pricingPort, inventoryPort, ledgerPort, json,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "a PlatformTransactionManager is required for checkout")),
                checkoutPromotions);
    }

    public CheckoutService(
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
            PlatformTransactionManager transactionManager) {
        this(cartRepository, cartService, orderRepository, addressService, sellerProfileRepository,
                shippingOptionService, null, pricingPort, inventoryPort, ledgerPort, json, transactionManager,
                null);
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
            StorageService storageService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            TransactionTemplate transactionTemplate) {
        this(cartRepository, cartService, orderRepository, addressService, sellerProfileRepository,
                shippingOptionService, storageService, pricingPort, inventoryPort, ledgerPort, json,
                transactionTemplate, null);
    }

    CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            AddressService addressService,
            SellerProfileRepository sellerProfileRepository,
            ShippingOptionService shippingOptionService,
            StorageService storageService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            TransactionTemplate transactionTemplate,
            CheckoutPromotionService checkoutPromotions) {
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.addressService = addressService;
        this.sellerProfileRepository = sellerProfileRepository;
        this.shippingOptionService = Objects.requireNonNull(shippingOptionService, "ShippingOptionService is required for checkout");
        this.storageService = storageService;
        this.pricingPort = pricingPort;
        this.inventoryPort = inventoryPort;
        this.ledgerPort = ledgerPort;
        this.json = json != null ? json : new ObjectMapper();
        this.transactionTemplate = transactionTemplate;
        this.checkoutPromotions = checkoutPromotions;
    }

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
        this(cartRepository, cartService, orderRepository, addressService, sellerProfileRepository,
                shippingOptionService, null, pricingPort, inventoryPort, ledgerPort, json, transactionTemplate);
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

        CartSelection selection = selectCartItems(principal.userId(), request == null ? null : request.cartItemIds());
        Cart cart = selection.cart();
        List<CartItem> allItems = selection.allItems();
        List<CartItem> items = selection.items();
        Map<Long, ListingOffer> offers = validatedOffers(principal, items);
        List<Long> listingIds = items.stream().map(CartItem::listingId).distinct().toList();

        // Delivery prerequisites are validated before any stock is reserved.
        Address address = resolveAddress(principal.userId(), request == null ? null : request.shippingAddressId());
        Long shippingAddressId = address.id();
        // Group and price each seller before any inventory reservation.
        Map<Long, List<CartItem>> itemsBySeller = groupBySeller(items, offers);

        record SellerDraft(
                long sellerProfileId,
                List<CartItem> items,
                BigDecimal subtotal,
                BigDecimal shippingFee,
                Long shippingOptionId) {}

        List<SellerDraft> drafts = new ArrayList<>();
        Map<Long, BigDecimal> shippingBySeller = new LinkedHashMap<>();
        for (Map.Entry<Long, List<CartItem>> entry : itemsBySeller.entrySet()) {
            long sellerProfileId = entry.getKey();
            List<CartItem> sellerItems = entry.getValue();
            BigDecimal sellerSubtotal = sellerItems.stream()
                    .map(CheckoutService::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int sellerItemCount = sellerItems.stream().mapToInt(CartItem::quantity).sum();

            Long requestedOptionId = (request != null && request.shippingOptionBySeller() != null)
                    ? request.shippingOptionBySeller().get(sellerProfileId)
                    : null;
            ShippingOption option = shippingOptionService.resolveForCheckout(sellerProfileId, requestedOptionId);
            BigDecimal sellerShipping = option.feeFor(sellerItemCount, sellerSubtotal);
            drafts.add(new SellerDraft(sellerProfileId, sellerItems, sellerSubtotal, sellerShipping, option.id()));
            shippingBySeller.put(sellerProfileId, sellerShipping);
        }

        // Discount codes are worked out on the real shipping, before any stock is held [TCG-569].
        CheckoutPromotionService.Evaluation promotion = null;
        if (request != null && request.hasCodes()) {
            promotion = evaluateCodes(principal.userId(), items, offers, shippingBySeller,
                    request.platformCode(), request.sellerCodes());
            promotion.firstFailure().ifPresent(CheckoutService::refuseCode);
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
                BigDecimal sellerDiscount,
                BigDecimal platformDiscount,
                BigDecimal grandTotal,
                CommissionQuote commission,
                BigDecimal netAmount) {}

        List<SellerSuborderComputation> sellerComputations = new ArrayList<>();

        for (SellerDraft draft : drafts) {
            long sellerProfileId = draft.sellerProfileId();
            List<CartItem> sellerItems = draft.items();
            BigDecimal sellerSubtotal = draft.subtotal();
            BigDecimal sellerShipping = draft.shippingFee();
            PromotionCalculator.SellerTotals totals = promotion == null ? null
                    : promotion.result().sellers().stream()
                            .filter(t -> t.sellerProfileId() == sellerProfileId).findFirst().orElseThrow();
            BigDecimal sellerDiscount = totals == null ? BigDecimal.ZERO : totals.sellerDiscount();
            BigDecimal platformDiscount = totals == null ? BigDecimal.ZERO : totals.platformDiscount();
            BigDecimal sellerGrandTotal = sellerSubtotal.add(sellerShipping)
                    .subtract(sellerDiscount).subtract(platformDiscount);

            // Commission is quoted on the items alone: a seller is not charged for postage,
            // nor for the part of the price their own code gave away. A Pegasus code does
            // not lower it: Pegasus pays that part back to the shop.
            CommissionQuote commission = ledgerPort.quoteCommission(sellerProfileId,
                    sellerSubtotal.subtract(sellerDiscount),
                    sellerItems.stream().map(i -> offers.get(i.listingId()).catalogVariantId()).toList());
            if (commission == null) {
                commission = CommissionQuote.NONE;
            }
            BigDecimal sellerNet = sellerGrandTotal.add(platformDiscount).subtract(commission.amount());

            sellerComputations.add(new SellerSuborderComputation(
                    sellerProfileId, sellerItems, sellerSubtotal, sellerShipping, draft.shippingOptionId(),
                    sellerDiscount, platformDiscount, sellerGrandTotal, commission, sellerNet));

            itemsSubtotal = itemsSubtotal.add(sellerSubtotal);
            shippingTotal = shippingTotal.add(sellerShipping);
            discountTotal = discountTotal.add(sellerDiscount).add(platformDiscount);
        }

        BigDecimal grandTotal = itemsSubtotal.add(shippingTotal).subtract(discountTotal);
        BigDecimal expected = request == null ? null : request.expectedGrandTotal();
        if (expected != null && expected.compareTo(grandTotal) != 0) {
            throw promoChanged(grandTotal, null);
        }

        JSONB addressSnapshot = createAddressSnapshot(address);
        String buyerNote = request != null ? request.buyerNote() : null;
        String orderNumber = orderRepository.nextOrderNumber();

        // Reserve only after every seller can ship and the buyer has a valid address (strictly rolls back on failure)
        Map<Long, Integer> quantityByListing = new LinkedHashMap<>();
        for (CartItem item : items) {
            quantityByListing.merge(item.listingId(), item.quantity(), Integer::sum);
        }
        Map<Long, List<ReservedUnit>> heldUnitsByListing = inventoryPort.reserve(quantityByListing);

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

        for (SellerSuborderComputation sc : sellerComputations) {
            String sellerOrderNumber = orderNumber + "-S" + sc.sellerProfileId();

            boolean discounted = sc.sellerDiscount().signum() != 0 || sc.platformDiscount().signum() != 0;
            // Without a code this is the insert checkout has always made.
            SellerOrderRecord sellerOrder = !discounted
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
                BigDecimal lineTotal = lineTotal(sellerItem);
                BigDecimal unitCost = inventoryPort.averageUnitCost(
                        sc.sellerProfileId(), details.catalogVariantId(), offer.condition());
                BigDecimal allocated = promotion == null ? BigDecimal.ZERO
                        : promotion.result().discountByLine().getOrDefault(sellerItem.id(), BigDecimal.ZERO);

                BigDecimal lineDiscount = promotion != null
                        ? promotion.result().sellerDiscountByLine().getOrDefault(sellerItem.id(), BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                OrderItemRecord orderItem = allocated.signum() == 0
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

        // Each code's use is taken last, under its row lock: if another checkout took the
        // last one meanwhile, this whole order rolls back, stock included.
        if (promotion != null) {
            Optional<Promotion> ranOut = checkoutPromotions.reserve(
                    principal.userId(), salesOrder.getId(), sellerOrderIdBySeller, promotion);
            if (ranOut.isPresent()) {
                CheckoutPromotionService.Evaluation now = evaluateCodes(principal.userId(), items, offers,
                        shippingBySeller, request.platformCode(), request.sellerCodes());
                throw promoChanged(now.result().grandTotal(), ranOut.get().code());
            }
        }

        // 8. Cleanup: the basket is emptied, not deleted — the buyer keeps using it.
        if (items.size() == allItems.size()) {
            cartRepository.deleteItemsByCartId(cart.id());
        } else {
            for (CartItem item : items) {
                cartRepository.deleteItem(item.id(), cart.id());
            }
        }

        // 9. Return complete order outcome
        return getOrderDetails(salesOrder.getId(), false);
    }

    private static BigDecimal lineTotal(CartItem item) {
        return item.unitPriceAtAdd().multiply(BigDecimal.valueOf(item.quantity()));
    }

    private CheckoutPromotionService.Evaluation evaluateCodes(Long buyerId, List<CartItem> items,
            Map<Long, ListingOffer> offers, Map<Long, BigDecimal> shippingBySeller, String platformCode,
            Map<Long, String> sellerCodes) {
        return requirePromotions().evaluate(buyerId, basket(items, offers, shippingBySeller),
                platformCode, sellerCodes);
    }

    private PromotionCalculator.Basket basket(List<CartItem> items, Map<Long, ListingOffer> offers,
            Map<Long, BigDecimal> shippingBySeller) {
        List<CheckoutPromotionService.BasketLine> lines = items.stream()
                .map(i -> {
                    ListingOffer offer = offers.get(i.listingId());
                    return new CheckoutPromotionService.BasketLine(
                            i.id(), offer.sellerProfileId(), offer.catalogVariantId(), lineTotal(i));
                })
                .toList();
        return requirePromotions().basket(lines, shippingBySeller);
    }

    private CheckoutPromotionService requirePromotions() {
        if (checkoutPromotions == null) {
            throw new IllegalStateException("This CheckoutService was built without discount code support");
        }
        return checkoutPromotions;
    }

    /** A code the buyer sent cannot go on the order: say which and why, and place nothing. */
    private static void refuseCode(CheckoutPromotionService.CodeCheck failed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", failed.code());
        if (failed.shopSlot() != null) details.put("sellerProfileId", failed.shopSlot());
        if (failed.shortfall() != null) details.put("shortfall", failed.shortfall());
        throw new ApiException(failed.error(), failed.error().defaultMessage(), details);
    }

    /** The total the buyer saw is no longer the total; nothing is placed. */
    private static ConflictException promoChanged(BigDecimal grandTotal, String code) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("grandTotal", grandTotal);
        if (code != null) details.put("code", code);
        return new ConflictException(ErrorCode.PROMO_CHANGED, ErrorCode.PROMO_CHANGED.defaultMessage(), details);
    }

    private record CartSelection(Cart cart, List<CartItem> allItems, List<CartItem> items) {}

    /** Both quote and checkout select exclusively from this buyer's cart. */
    private CartSelection selectCartItems(long userId, List<Long> cartItemIds) {
        // 1. Retrieve user's cart and items
        Cart cart = cartRepository.findByUserId(userId).orElse(null);
        if (cart == null) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }
        List<CartItem> allItems = cartRepository.findItemsByCartId(cart.id());
        if (allItems.isEmpty()) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }
        return new CartSelection(cart, allItems, selectItems(allItems, cartItemIds));
    }

    private static List<CartItem> selectItems(List<CartItem> allItems, List<Long> cartItemIds) {
        if (allItems.isEmpty()) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return allItems;
        }
        Set<Long> allowedIds = new HashSet<>(cartItemIds);
        List<CartItem> items = allItems.stream().filter(item -> allowedIds.contains(item.id())).toList();
        if (items.isEmpty()) {
            throw new ConflictException(ErrorCode.CART_EMPTY);
        }
        return items;
    }

    private Map<Long, ListingOffer> validatedOffers(AuthPrincipal principal, List<CartItem> items) {
        // 2. Batch validate pricing and purchasability
        List<Long> listingIds = items.stream().map(CartItem::listingId).distinct().toList();
        Map<Long, ListingOffer> offers = pricingPort.offers(listingIds);

        // A guest previewing codes owns no shop.
        Long buyerSellerProfileId = principal == null ? null
                : sellerProfileRepository.findByUserId(principal.userId()).map(SellerProfile::id).orElse(null);

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

        return offers;
    }

    private Map<Long, List<CartItem>> groupBySeller(List<CartItem> items, Map<Long, ListingOffer> offers) {
        Map<Long, List<CartItem>> grouped = new LinkedHashMap<>();
        for (CartItem item : items) {
            grouped.computeIfAbsent(offers.get(item.listingId()).sellerProfileId(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private Address resolveAddress(long userId, Long addressId) {
        Address address;
        if (addressId != null) {
            address = addressService.get(userId, addressId);
        } else {
            List<Address> addresses = addressService.list(userId);
            address = addresses.stream().filter(Address::defaultShipping).findFirst()
                    .or(() -> addresses.stream().findFirst()).orElse(null);
        }
        if (address == null) throw new BadRequestException(ErrorCode.SHIPPING_ADDRESS_REQUIRED);
        return address;
    }

    /**
     * What the selected cart lines cost with these codes [TCG-569]. Reserves nothing
     * and works for a guest (by cart session) too. Without shipping choices each
     * shop's default delivery is used and the totals leave shipping out.
     */
    @Transactional(readOnly = true)
    public CheckoutPromotionViews.PromoPreview promoPreview(AuthPrincipal principal, String guestSessionKey,
            PromoPreviewRequest request) {
        PromoPreviewRequest r = request == null ? new PromoPreviewRequest(null, null, null, null, null) : request;
        PricedCart cart = pricedCart(principal, guestSessionKey, r.cartItemIds(), r.shippingOptionBySeller());
        Long buyerId = principal == null ? null : principal.userId();
        CheckoutPromotionService promotions = requirePromotions();
        PromotionCalculator.Basket basket = basket(cart.items(), cart.offers(), cart.shippingBySeller());
        if (r.autoPickOrFalse()) {
            PromotionCalculator.Pick pick = promotions.bestPick(buyerId, basket, cart.sellerNames().keySet());
            String platform = pick.promotions().stream()
                    .filter(p -> p.ownerType() == Promotion.OwnerType.PLATFORM)
                    .map(Promotion::code).findFirst().orElse(null);
            Map<Long, String> shops = new LinkedHashMap<>();
            pick.promotions().stream().filter(p -> p.ownerType() == Promotion.OwnerType.SELLER)
                    .forEach(p -> shops.put(p.sellerProfileId(), p.code()));
            return promotions.preview(promotions.evaluate(basket, pick), cart.sellerNames(),
                    cart.shippingEstimated(), new CheckoutPromotionViews.BestPick(platform, shops));
        }
        return promotions.preview(promotions.evaluate(buyerId, basket, r.platformCode(), r.sellerCodes()),
                cart.sellerNames(), cart.shippingEstimated(), null);
    }

    /**
     * The code picker for the selected cart lines: codes the buyer collected, public
     * codes still to collect, and other public codes for Pegasus and these shops,
     * each tried on the basket alone with each shop's default delivery.
     */
    @Transactional(readOnly = true)
    public List<CheckoutPromotionViews.AvailablePromotion> availablePromotions(AuthPrincipal principal,
            String guestSessionKey, List<Long> cartItemIds) {
        PricedCart cart = pricedCart(principal, guestSessionKey, cartItemIds, null);
        return requirePromotions().available(principal == null ? null : principal.userId(),
                basket(cart.items(), cart.offers(), cart.shippingBySeller()),
                cart.sellerNames().keySet(), cart.sellerNames());
    }

    /** Selected lines, priced, with a fee per shop. */
    private record PricedCart(List<CartItem> items, Map<Long, ListingOffer> offers,
            Map<Long, BigDecimal> shippingBySeller, Map<Long, String> sellerNames, boolean shippingEstimated) {}

    private PricedCart pricedCart(AuthPrincipal principal, String guestSessionKey, List<Long> cartItemIds,
            Map<Long, Long> shippingOptionBySeller) {
        List<CartItem> items;
        if (principal != null) {
            items = selectCartItems(principal.userId(), cartItemIds).items();
        } else {
            String session = CartSessionKey.normalize(guestSessionKey);
            Cart cart = session == null ? null : cartRepository.findBySessionKey(session).orElse(null);
            if (cart == null) throw new ConflictException(ErrorCode.CART_EMPTY);
            items = selectItems(cartRepository.findItemsByCartId(cart.id()), cartItemIds);
        }
        Map<Long, ListingOffer> offers = validatedOffers(principal, items);
        boolean estimated = shippingOptionBySeller == null || shippingOptionBySeller.isEmpty();
        Map<Long, BigDecimal> shipping = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        groupBySeller(items, offers).forEach((sellerId, sellerItems) -> {
            names.put(sellerId, offers.get(sellerItems.getFirst().listingId()).sellerName());
            int count = sellerItems.stream().mapToInt(CartItem::quantity).sum();
            BigDecimal subtotal = sellerItems.stream().map(CheckoutService::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fee;
            if (estimated) {
                // The cart has not picked delivery yet; a shop with none set up still previews.
                fee = shippingOptionService.listActive(sellerId).stream().findFirst()
                        .map(option -> option.feeFor(count, subtotal)).orElse(BigDecimal.ZERO);
            } else {
                fee = shippingOptionService.resolveForCheckout(sellerId, shippingOptionBySeller.get(sellerId))
                        .feeFor(count, subtotal);
            }
            shipping.put(sellerId, fee);
        });
        return new PricedCart(items, offers, shipping, names, estimated);
    }

    @Transactional(readOnly = true)
    public List<ShippingQuoteResponse> shippingQuotes(AuthPrincipal principal, ShippingQuoteRequest request) {
        if (principal == null) throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        List<CartItem> items = selectCartItems(principal.userId(), request == null ? null : request.cartItemIds()).items();
        Map<Long, ListingOffer> offers = validatedOffers(principal, items);
        List<ShippingQuoteResponse> quotes = new ArrayList<>();
        for (var entry : groupBySeller(items, offers).entrySet()) {
            long sellerId = entry.getKey();
            List<CartItem> sellerItems = entry.getValue();
            int count = sellerItems.stream().mapToInt(CartItem::quantity).sum();
            BigDecimal subtotal = sellerItems.stream()
                    .map(item -> item.unitPriceAtAdd().multiply(BigDecimal.valueOf(item.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<ShippingQuoteOptionResponse> options = shippingOptionService.listActive(sellerId).stream()
                    .map(option -> ShippingQuoteOptionResponse.from(option, count, subtotal)).toList();
            quotes.add(new ShippingQuoteResponse(sellerId, offers.get(sellerItems.getFirst().listingId()).sellerName(),
                    count, subtotal, options.isEmpty() ? null : options.getFirst().id(), options));
        }
        return quotes;
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
        List<Long> profileIds = sellerOrders.stream().map(SellerOrderRecord::getSellerProfileId).distinct().toList();
        Map<Long, String> sellerNames = orderRepository.findSellerNamesByProfileIds(profileIds);

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
                        imageUrl));
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

    /**
     * The address exactly as it read when the order was placed.
     *
     * <p>Built by the JSON writer rather than by string concatenation: a buyer's
     * address is free text, and a backslash or a newline in it would otherwise
     * either break the document — Postgres rejects a malformed jsonb and the buyer
     * can never check out again — or quietly change what the stored record says.
     */
    private JSONB createAddressSnapshot(Address a) {
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
