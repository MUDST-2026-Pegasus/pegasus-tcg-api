package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.common.cart.CartSessionKey;
import com.pegasus.pegasustcgapi.dto.promotion.CheckoutPromotionViews;
import com.pegasus.pegasustcgapi.dto.promotion.PromoPreviewRequest;
import com.pegasus.pegasustcgapi.dto.sale.SalePricing;
import com.pegasus.pegasustcgapi.dto.shipping.ShippingQuoteOptionResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShippingQuoteRequest;
import com.pegasus.pegasustcgapi.dto.shipping.ShippingQuoteResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.CartPriceChangedException;
import com.pegasus.pegasustcgapi.exception.CartPriceChangedException.PriceChange;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.cart.Cart;
import com.pegasus.pegasustcgapi.model.cart.CartItem;
import com.pegasus.pegasustcgapi.model.promotion.Promotion;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.model.shipping.ShippingOption;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.cart.CartRepository;
import com.pegasus.pegasustcgapi.repository.seller.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.promotion.PromotionCalculator;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.LivePrice;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Plan;
import com.pegasus.pegasustcgapi.service.shipping.ShippingOptionService;
import com.pegasus.pegasustcgapi.service.user.AddressService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Resolves cart, sale, delivery and promotion snapshots for checkout and previews. */
@Service
class CheckoutCartPricingService {
    private final CartRepository cartRepository;
    private final AddressService addressService;
    private final SellerProfileRepository sellerProfileRepository;
    private final ShippingOptionService shippingOptionService;
    private final PricingPort pricingPort;
    private final CheckoutPromotionService checkoutPromotions;
    private SaleCheckoutService saleCheckout;

    CheckoutCartPricingService(CartRepository cartRepository, AddressService addressService,
            SellerProfileRepository sellerProfileRepository, ShippingOptionService shippingOptionService,
            PricingPort pricingPort, CheckoutPromotionService checkoutPromotions) {
        this.cartRepository = cartRepository;
        this.addressService = addressService;
        this.sellerProfileRepository = sellerProfileRepository;
        this.shippingOptionService = Objects.requireNonNull(shippingOptionService, "ShippingOptionService is required for checkout");
        this.pricingPort = pricingPort;
        this.checkoutPromotions = checkoutPromotions;
    }

    @Autowired(required = false)
    void setSaleCheckout(SaleCheckoutService saleCheckout) { this.saleCheckout = saleCheckout; }

    void endSaleForBudget(long saleId) { saleCheckout.endForBudget(saleId); }

    void removeCheckedOut(Cart cart, List<CartItem> allItems, List<CartItem> items) {
        if (items.size() == allItems.size()) {
            cartRepository.deleteItemsByCartId(cart.id());
        } else {
            for (CartItem item : items) cartRepository.deleteItem(item.id(), cart.id());
        }
    }

    ShippingOption resolveShipping(long sellerId, Long optionId) {
        return shippingOptionService.resolveForCheckout(sellerId, optionId);
    }

    private static BigDecimal lineTotal(CartItem item) {
        return CheckoutPricingCalculator.lineTotal(item);
    }

    CheckoutPromotionService.Evaluation evaluateCodes(Long buyerId, List<CartItem> items,
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

    record CartSelection(Cart cart, List<CartItem> allItems, List<CartItem> items) {}

    /** Both quote and checkout select exclusively from this buyer's cart. */
    CartSelection selectCartItems(long userId, List<Long> cartItemIds) {
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

    /**
     * @param liveOut filled with the sale each listing sells under now, when given
     */
    Map<Long, ListingOffer> validatedOffers(AuthPrincipal principal, List<CartItem> items,
            Map<Long, LivePrice> liveOut) {
        // 2. Batch validate pricing and purchasability
        List<Long> listingIds = items.stream().map(CartItem::listingId).distinct().toList();
        Map<Long, ListingOffer> offers = pricingPort.offers(listingIds);
        if (saleCheckout != null) {
            // The price is worked out afresh from the sales, not read from the page cache
            // [TCG-680]: what the buyer saw must still hold now, or nothing is placed.
            Map<Long, LivePrice> live = saleCheckout.resolve(listingIds);
            if (liveOut != null) {
                liveOut.putAll(live);
            }
            Map<Long, ListingOffer> repriced = new LinkedHashMap<>();
            offers.forEach((id, offer) -> {
                LivePrice sale = live.get(id);
                repriced.put(id, offer.priced(sale == null
                        ? SalePricing.none(offer.listPrice())
                        : sale.pricing(offer.listPrice())));
            });
            offers = repriced;
        }

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

    /** The order lines for these cart lines; without sale support, the cart lines themselves. */
    Plan plan(Long buyerId, List<CartItem> items, Map<Long, ListingOffer> offers,
            Map<Long, LivePrice> live, boolean lock) {
        if (saleCheckout == null) {
            return new Plan(items, Map.of());
        }
        Map<Long, BigDecimal> listPrices = new HashMap<>();
        offers.forEach((id, offer) -> listPrices.put(id, offer.listPrice()));
        return saleCheckout.plan(buyerId, items, live, listPrices, lock);
    }

    Map<Long, List<CartItem>> groupBySeller(List<CartItem> items, Map<Long, ListingOffer> offers) {
        return CheckoutPricingCalculator.groupBySeller(items, offers);
    }

    Address resolveAddress(long userId, Long addressId) {
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
        Map<Long, LivePrice> live = new HashMap<>();
        Map<Long, ListingOffer> offers = validatedOffers(principal, items, live);
        items = plan(principal == null ? null : principal.userId(), items, offers, live, false).lines();
        boolean estimated = shippingOptionBySeller == null || shippingOptionBySeller.isEmpty();
        Map<Long, BigDecimal> shipping = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        groupBySeller(items, offers).forEach((sellerId, sellerItems) -> {
            names.put(sellerId, offers.get(sellerItems.getFirst().listingId()).sellerName());
            int count = sellerItems.stream().mapToInt(CartItem::quantity).sum();
            BigDecimal subtotal = CheckoutPricingCalculator.subtotal(sellerItems);
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

    public List<ShippingQuoteResponse> shippingQuotes(AuthPrincipal principal, ShippingQuoteRequest request) {
        if (principal == null) throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        List<CartItem> cartItems = selectCartItems(principal.userId(), request == null ? null : request.cartItemIds()).items();
        Map<Long, LivePrice> live = new HashMap<>();
        Map<Long, ListingOffer> offers = validatedOffers(principal, cartItems, live);
        List<CartItem> items = plan(principal.userId(), cartItems, offers, live, false).lines();
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
}
