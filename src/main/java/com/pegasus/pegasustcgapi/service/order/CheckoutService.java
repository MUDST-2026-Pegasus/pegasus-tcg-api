package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.common.cart.CartSessionKey;
import com.pegasus.pegasustcgapi.dto.order.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.promotion.CheckoutPromotionViews;
import com.pegasus.pegasustcgapi.dto.promotion.PromoPreviewRequest;
import com.pegasus.pegasustcgapi.dto.shipping.ShippingQuoteRequest;
import com.pegasus.pegasustcgapi.dto.shipping.ShippingQuoteResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.model.cart.Cart;
import com.pegasus.pegasustcgapi.model.cart.CartItem;
import com.pegasus.pegasustcgapi.model.promotion.Promotion;
import com.pegasus.pegasustcgapi.model.shipping.ShippingOption;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.cart.CartRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderTransitionRepository;
import com.pegasus.pegasustcgapi.repository.seller.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.order.CheckoutCartPricingService.CartSelection;
import com.pegasus.pegasustcgapi.service.order.CheckoutOrderPersistenceService.SellerSuborderComputation;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.promotion.PromotionCalculator;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Hold;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.LivePrice;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Plan;
import com.pegasus.pegasustcgapi.service.shipping.ShippingOptionService;
import com.pegasus.pegasustcgapi.service.user.AddressService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final CartService cartService;
    private final CheckoutCartPricingService cartPricing;
    private final CheckoutReservationService reservation;
    private final CheckoutOrderPersistenceService persistence;
    private final LedgerPort ledgerPort;
    private final CheckoutResponseMapper responseMapper;
    private final TransactionTemplate transactionTemplate;

    /** Optional so the constructors the unit tests use stay as they are; Spring always supplies it. */
    @Autowired(required = false)
    void setSaleCheckout(SaleCheckoutService saleCheckout) {
        this.cartPricing.setSaleCheckout(saleCheckout);
        this.reservation.setSaleCheckout(saleCheckout);
    }

    @Autowired
    CheckoutService(CartService cartService, CheckoutCartPricingService cartPricing,
            CheckoutReservationService reservation, CheckoutOrderPersistenceService persistence,
            CheckoutResponseMapper responseMapper, LedgerPort ledgerPort, PlatformTransactionManager transactionManager) {
        this.cartService = cartService;
        this.cartPricing = cartPricing;
        this.reservation = reservation;
        this.persistence = persistence;
        this.responseMapper = responseMapper;
        this.ledgerPort = ledgerPort;
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "a PlatformTransactionManager is required for checkout"));
    }

    /**
     * The transaction manager is required rather than optional: checkout writes a
     * sales order, its sub-orders, their items and the reserved units, then empties
     * the cart. Half of that is not an order, so running these without a transaction
     * is never the right fallback.
     */
    public CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            OrderTransitionRepository orderTransitionRepository,
            OrderQueryRepository orderQueryRepository,
            OrderReferenceQueryRepository orderReferenceQueryRepository,
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
        this(cartRepository, cartService, orderRepository, orderTransitionRepository, orderQueryRepository,
                orderReferenceQueryRepository, addressService, sellerProfileRepository,
                shippingOptionService, storageService, pricingPort, inventoryPort, ledgerPort, json,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "a PlatformTransactionManager is required for checkout")),
                checkoutPromotions);
    }

    public CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            OrderTransitionRepository orderTransitionRepository,
            OrderQueryRepository orderQueryRepository,
            OrderReferenceQueryRepository orderReferenceQueryRepository,
            AddressService addressService,
            SellerProfileRepository sellerProfileRepository,
            ShippingOptionService shippingOptionService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this(cartRepository, cartService, orderRepository, orderTransitionRepository, orderQueryRepository,
                orderReferenceQueryRepository, addressService, sellerProfileRepository,
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
            OrderTransitionRepository orderTransitionRepository,
            OrderQueryRepository orderQueryRepository,
            OrderReferenceQueryRepository orderReferenceQueryRepository,
            AddressService addressService,
            SellerProfileRepository sellerProfileRepository,
            ShippingOptionService shippingOptionService,
            StorageService storageService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            TransactionTemplate transactionTemplate) {
        this(cartRepository, cartService, orderRepository, orderTransitionRepository, orderQueryRepository,
                orderReferenceQueryRepository, addressService, sellerProfileRepository,
                shippingOptionService, storageService, pricingPort, inventoryPort, ledgerPort, json,
                transactionTemplate, null);
    }

    CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            OrderTransitionRepository orderTransitionRepository,
            OrderQueryRepository orderQueryRepository,
            OrderReferenceQueryRepository orderReferenceQueryRepository,
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
        this.cartService = cartService;
        this.cartPricing = new CheckoutCartPricingService(cartRepository, addressService, sellerProfileRepository,
                shippingOptionService, pricingPort, checkoutPromotions);
        this.responseMapper = new CheckoutResponseMapper(orderRepository, orderQueryRepository,
                orderReferenceQueryRepository, storageService, json);
        this.reservation = new CheckoutReservationService(inventoryPort, null, checkoutPromotions);
        this.persistence = new CheckoutOrderPersistenceService(orderRepository, orderTransitionRepository,
                orderReferenceQueryRepository, inventoryPort);
        this.ledgerPort = ledgerPort;
        this.transactionTemplate = transactionTemplate;
    }

    CheckoutService(
            CartRepository cartRepository,
            CartService cartService,
            OrderRepository orderRepository,
            OrderTransitionRepository orderTransitionRepository,
            OrderQueryRepository orderQueryRepository,
            OrderReferenceQueryRepository orderReferenceQueryRepository,
            AddressService addressService,
            SellerProfileRepository sellerProfileRepository,
            ShippingOptionService shippingOptionService,
            PricingPort pricingPort,
            InventoryPort inventoryPort,
            LedgerPort ledgerPort,
            ObjectMapper json,
            TransactionTemplate transactionTemplate) {
        this(cartRepository, cartService, orderRepository, orderTransitionRepository, orderQueryRepository,
                orderReferenceQueryRepository, addressService, sellerProfileRepository,
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
        Optional<SalesOrderRecord> existingOrderOpt = persistence.findByIdempotencyKey(trimmedKey);
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
        } catch (SaleCheckoutService.BudgetShortException e) {
            // The campaign's budget could not cover this order [TCG-680]: it ends now, in a
            // transaction of its own, and the buyer is shown the prices without it.
            cartPricing.endSaleForBudget(e.saleId());
            executeInTransaction(() -> cartPricing.validatedOffers(principal,
                    cartPricing.selectCartItems(principal.userId(), request == null ? null : request.cartItemIds()).items(),
                    null));
            throw new ConflictException(ErrorCode.CART_PRICE_CHANGED);
        } catch (DuplicateKeyException | IntegrityConstraintViolationException e) {
            // Race condition: another concurrent transaction committed with the same key.
            // This transaction rolled back. Re-query winner's committed order.
            return persistence.findByIdempotencyKey(trimmedKey)
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

        CartSelection selection = cartPricing.selectCartItems(principal.userId(), request == null ? null : request.cartItemIds());
        Cart cart = selection.cart();
        List<CartItem> allItems = selection.allItems();
        List<CartItem> items = selection.items();
        Map<Long, LivePrice> live = new HashMap<>();
        Map<Long, ListingOffer> offers = cartPricing.validatedOffers(principal, items, live);
        List<Long> listingIds = items.stream().map(CartItem::listingId).distinct().toList();
        // The order lines: the cart's, with cards past a sale's per-buyer limit split off
        // at the asking price [TCG-680]. Codes, totals and stock all work on these.
        Plan plan = cartPricing.plan(principal.userId(), items, offers, live, true);
        List<CartItem> lines = plan.lines();

        // Delivery prerequisites are validated before any stock is reserved.
        Address address = cartPricing.resolveAddress(principal.userId(), request == null ? null : request.shippingAddressId());
        Long shippingAddressId = address.id();
        // Group and price each seller before any inventory reservation.
        Map<Long, List<CartItem>> itemsBySeller = cartPricing.groupBySeller(lines, offers);

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
            BigDecimal sellerSubtotal = CheckoutPricingCalculator.subtotal(sellerItems);
            int sellerItemCount = sellerItems.stream().mapToInt(CartItem::quantity).sum();

            Long requestedOptionId = (request != null && request.shippingOptionBySeller() != null)
                    ? request.shippingOptionBySeller().get(sellerProfileId)
                    : null;
            ShippingOption option = cartPricing.resolveShipping(sellerProfileId, requestedOptionId);
            BigDecimal sellerShipping = option.feeFor(sellerItemCount, sellerSubtotal);
            drafts.add(new SellerDraft(sellerProfileId, sellerItems, sellerSubtotal, sellerShipping, option.id()));
            shippingBySeller.put(sellerProfileId, sellerShipping);
        }

        // Discount codes are worked out on the real shipping, before any stock is held [TCG-569].
        CheckoutPromotionService.Evaluation promotion = null;
        if (request != null && request.hasCodes()) {
            promotion = cartPricing.evaluateCodes(principal.userId(), lines, offers, shippingBySeller,
                    request.platformCode(), request.sellerCodes());
            promotion.firstFailure().ifPresent(CheckoutService::refuseCode);
        }

        List<CheckoutPricingCalculator.SellerPrice> prices = new ArrayList<>();

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

            // Commission is quoted on the items alone: a seller is not charged for postage,
            // nor for the part of the price their own code or their own sale gave away. A
            // Pegasus code or campaign does not lower it: Pegasus pays that part back to the
            // shop, the campaign's as SALE_SUBSIDY [TCG-680].
            BigDecimal saleSubsidy = plan.pegasusSubsidy(sellerItems);
            CommissionQuote commission = ledgerPort.quoteCommission(sellerProfileId,
                    CheckoutPricingCalculator.commissionBase(sellerSubtotal, saleSubsidy, sellerDiscount),
                    sellerItems.stream().map(i -> offers.get(i.listingId()).catalogVariantId()).toList());
            if (commission == null) {
                commission = CommissionQuote.NONE;
            }
            CheckoutPricingCalculator.SellerPrice price = CheckoutPricingCalculator.seller(
                    sellerSubtotal, sellerShipping, sellerDiscount, platformDiscount, saleSubsidy, commission.amount());
            BigDecimal sellerGrandTotal = price.grandTotal();
            BigDecimal sellerNet = price.netAmount();

            sellerComputations.add(new SellerSuborderComputation(
                    sellerProfileId, sellerItems, sellerSubtotal, sellerShipping, draft.shippingOptionId(),
                    sellerDiscount, platformDiscount, sellerGrandTotal, commission, sellerNet, saleSubsidy));

            prices.add(price);
        }

        CheckoutPricingCalculator.Totals calculated = CheckoutPricingCalculator.totals(prices);
        BigDecimal itemsSubtotal = calculated.itemsSubtotal();
        BigDecimal shippingTotal = calculated.shippingTotal();
        BigDecimal discountTotal = calculated.discountTotal();
        BigDecimal grandTotal = calculated.grandTotal();
        BigDecimal expected = request == null ? null : request.expectedGrandTotal();
        if (expected != null && expected.compareTo(grandTotal) != 0) {
            throw promoChanged(grandTotal, null);
        }

        JSONB addressSnapshot = createAddressSnapshot(address);
        String buyerNote = request != null ? request.buyerNote() : null;
        String orderNumber = persistence.nextOrderNumber();

        // Stock and sale holds precede order inserts; promotion holds remain after them.
        CheckoutReservationService.Stock stock = reservation.reserveStock(lines, plan);
        Map<Long, List<ReservedUnit>> heldUnitsByListing = stock.units();
        Map<Long, Hold> saleHolds = stock.saleHolds();

        CheckoutOrderPersistenceService.Persisted persisted =
                persistence.persist(
                        orderNumber, principal, itemsSubtotal, shippingTotal, discountTotal, grandTotal,
                        shippingAddressId, addressSnapshot, buyerNote, trimmedKey, listingIds, sellerComputations,
                        offers, promotion, plan, saleHolds, heldUnitsByListing);
        SalesOrderRecord salesOrder = persisted.order();
        Map<Long, Long> sellerOrderIdBySeller = persisted.sellerOrderIds();

        // Each code's use is taken last, under its row lock: if another checkout took the
        // last one meanwhile, this whole order rolls back, stock included.
        if (promotion != null) {
            Optional<Promotion> ranOut = reservation.reservePromotions(
                    principal.userId(), salesOrder.getId(), sellerOrderIdBySeller, promotion);
            if (ranOut.isPresent()) {
                CheckoutPromotionService.Evaluation now = cartPricing.evaluateCodes(principal.userId(), lines, offers,
                        shippingBySeller, request.platformCode(), request.sellerCodes());
                throw promoChanged(now.result().grandTotal(), ranOut.get().code());
            }
        }

        // 8. Cleanup: the basket is emptied, not deleted — the buyer keeps using it.
        cartPricing.removeCheckedOut(cart, allItems, items);

        // 9. Return complete order outcome
        return getOrderDetails(salesOrder.getId(), false);
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

    @Transactional(readOnly = true)
    public CheckoutPromotionViews.PromoPreview promoPreview(AuthPrincipal principal, String guestSessionKey,
            PromoPreviewRequest request) {
        return cartPricing.promoPreview(principal, guestSessionKey, request);
    }

    @Transactional(readOnly = true)
    public List<CheckoutPromotionViews.AvailablePromotion> availablePromotions(AuthPrincipal principal,
            String guestSessionKey, List<Long> cartItemIds) {
        return cartPricing.availablePromotions(principal, guestSessionKey, cartItemIds);
    }

    @Transactional(readOnly = true)
    public List<ShippingQuoteResponse> shippingQuotes(AuthPrincipal principal, ShippingQuoteRequest request) {
        return cartPricing.shippingQuotes(principal, request);
    }

    public CheckoutResponse getOrderDetails(long salesOrderId) {
        return getOrderDetails(salesOrderId, false);
    }

    public CheckoutResponse getOrderDetails(long salesOrderId, boolean replayed) {
        return responseMapper.getOrderDetails(salesOrderId, replayed);
    }

    public JSONB createAddressSnapshot(Address address) {
        return responseMapper.createAddressSnapshot(address);
    }

}
