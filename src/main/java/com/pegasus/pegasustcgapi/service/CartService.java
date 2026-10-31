package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.CartSessionKey;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CartItemResponse;
import com.pegasus.pegasustcgapi.dto.CartResponse;
import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.port.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.CartRepository;
import com.pegasus.pegasustcgapi.repository.CartRepository.CartListingDetails;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages shopping carts for both guest sessions and authenticated users.
 *
 * <p>Only the write paths create or merge carts. Reading a cart is a GET, and a GET
 * that inserts a row is one browser prefetch or proxy retry away from filling the
 * table with carts nobody asked for — so the readers below return an empty cart
 * rather than making one.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepository;
    private final PricingPort pricingPort;
    private final SellerProfileRepository sellerProfileRepository;
    private final ListingCardRenderer cards;
    private final StorageService storageService;

    @Autowired
    public CartService(
            CartRepository cartRepository,
            PricingPort pricingPort,
            SellerProfileRepository sellerProfileRepository,
            ListingCardRenderer cards,
            @Autowired(required = false) StorageService storageService) {
        this.cartRepository = cartRepository;
        this.pricingPort = pricingPort;
        this.sellerProfileRepository = sellerProfileRepository;
        this.cards = cards;
        this.storageService = storageService;
    }

    public record AddResult(CartItemResponse item, String sessionKey) {
    }

    /**
     * Adds or updates an item in the cart.
     * On first POST for a guest, generates a 128-bit session key and returns it.
     */
    @Transactional
    public AddResult addItem(AuthPrincipal principal, String sessionKey, CartItemRequest request) {
        String guestKey = CartSessionKey.normalize(sessionKey);

        ListingOffer offer = pricingPort.offer(request.listingId())
                .orElseThrow(() -> new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE));

        if (!offer.purchasable()) {
            throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
        }

        if (principal != null) {
            Optional<SellerProfile> sellerProfile = sellerProfileRepository.findByUserId(principal.userId());
            if (sellerProfile.isPresent() && sellerProfile.get().id() == offer.sellerProfileId()) {
                throw new ConflictException(ErrorCode.CANNOT_BUY_OWN_LISTING);
            }
        }

        // Checked before a cart row exists, so a rejected request leaves nothing behind.
        int requestedQuantity = request.resolvedQuantity();
        if (requestedQuantity < 1) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Quantity must be at least 1");
        }

        Cart cart;
        String returnSessionKey = null;

        if (principal != null) {
            cart = resolveCartForUser(principal.userId(), guestKey);
        } else {
            if (guestKey != null) {
                final String key = guestKey;
                cart = cartRepository.findBySessionKey(key)
                        .orElseGet(() -> cartRepository.createForSession(key));
                returnSessionKey = key;
            } else {
                String newKey = CartSessionKey.issue();
                cart = cartRepository.createForSession(newKey);
                returnSessionKey = newKey;
            }
        }

        Optional<CartItem> existingItem = cartRepository.findItemByCartIdAndListingId(cart.id(), request.listingId());
        int cumulativeQuantity;
        if (existingItem.isPresent()) {
            try {
                cumulativeQuantity = Math.addExact(existingItem.get().quantity(), requestedQuantity);
            } catch (ArithmeticException e) {
                throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK, "Requested quantity causes integer overflow");
            }
        } else {
            cumulativeQuantity = requestedQuantity;
        }

        if (cumulativeQuantity > offer.quantityAvailable()) {
            throw new ConflictException(
                    ErrorCode.INSUFFICIENT_STOCK,
                    "Requested quantity (" + cumulativeQuantity + ") exceeds available stock (" + offer.quantityAvailable() + ")");
        }

        CartItem item = cartRepository.upsertItem(cart.id(), request.listingId(), cumulativeQuantity, offer.price());
        ListingCard card = cards.card(offer.catalogVariantId());

        CartListingDetails details = cartRepository.findCartListingDetails(List.of(request.listingId())).get(request.listingId());
        String imageUrl = resolveImageUrl(details);

        CartItemResponse response = new CartItemResponse(
                item.id(),
                item.cartId(),
                item.listingId(),
                item.quantity(),
                item.unitPriceAtAdd(),
                offer.price(),
                false,
                offer.purchasable(),
                offer.quantityAvailable(),
                offer.sellerProfileId(),
                offer.catalogVariantId(),
                offer.condition(),
                offer.currency(),
                item.addedAt(),
                item.updatedAt(),
                details != null ? details.productName() : card == null ? null : card.productName(),
                details != null ? details.variantLabel() : card == null ? null : card.variantLabel(),
                details != null ? details.sellerName() : offer.sellerName(),
                imageUrl != null ? imageUrl : card == null ? null : card.officialImageUrl(),
                card == null ? null : card.productId(),
                details == null ? null : details.sellerUsername());

        return new AddResult(response, returnSessionKey);
    }

    /**
     * Folds a signed-out basket into the caller's own, if they brought one.
     *
     * <p>Its own method rather than a side effect of reading the cart: checkout has
     * to merge before it prices anything, and a merge hidden inside a getter is a
     * line someone deletes as dead code without knowing checkout depended on it.
     */
    @Transactional
    public void mergeGuestCartIfPresent(AuthPrincipal principal, String sessionKey) {
        if (principal == null) {
            return;
        }
        String guestKey = CartSessionKey.normalize(sessionKey);
        if (guestKey == null) {
            return;
        }
        resolveCartForUser(principal.userId(), guestKey);
    }

    @Transactional
    public CartResponse mergeCart(AuthPrincipal principal, String sessionKey) {
        if (principal == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
        String guestKey = CartSessionKey.normalize(sessionKey);
        if (guestKey != null && !guestKey.isBlank()) {
            resolveCartForUser(principal.userId(), guestKey);
        }
        return getCart(principal, null);
    }

    /**
     * Retrieves all items in the caller's cart, enriched with current batch-queried pricing.
     */
    @Transactional(readOnly = true)
    public List<CartItemResponse> getCartItems(AuthPrincipal principal, String sessionKey) {
        Optional<Cart> cart = findCart(principal, sessionKey);
        return cart.map(c -> findAndEnrichItems(c.id())).orElseGet(List::of);
    }

    /**
     * Retrieves the full cart with items and financial summary.
     * Auto-merges guest cart if both authenticated principal and sessionKey are passed.
     */
    @Transactional
    public CartResponse getCart(AuthPrincipal principal, String sessionKey) {
        if (principal != null && sessionKey != null && !sessionKey.isBlank()) {
            mergeGuestCartIfPresent(principal, sessionKey);
        }
        String guestKey = principal == null ? CartSessionKey.normalize(sessionKey) : null;
        Optional<Cart> found = findCart(principal, sessionKey);

        if (found.isEmpty()) {
            return new CartResponse(0L, principal != null ? principal.userId() : null,
                    guestKey, "THB", List.of(), 0, BigDecimal.ZERO);
        }

        Cart cart = found.get();
        return buildCartResponse(cart);
    }

    /**
     * Atomically adds multiple items to the user's cart in a single transaction.
     * If any item is invalid or not purchasable, the entire batch is rolled back.
     */
    @Transactional
    public CartResponse addBatchItems(Long userId, List<CartItemRequest> requests) {
        if (userId == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
        if (requests == null || requests.isEmpty()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Item list must not be empty");
        }

        Cart cart = cartRepository.getOrCreateForUser(userId);
        Optional<SellerProfile> sellerProfile = sellerProfileRepository.findByUserId(userId);

        for (CartItemRequest request : requests) {
            if (request == null || request.listingId() == null || request.listingId() <= 0) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Listing ID is required and must be positive");
            }
            int requestedQuantity = request.resolvedQuantity();
            if (requestedQuantity < 1) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Quantity must be at least 1");
            }

            ListingOffer offer = pricingPort.offer(request.listingId())
                    .orElseThrow(() -> new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE));

            if (!offer.purchasable()) {
                throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
            }

            if (sellerProfile.isPresent() && sellerProfile.get().id() == offer.sellerProfileId()) {
                throw new ConflictException(ErrorCode.CANNOT_BUY_OWN_LISTING);
            }

            Optional<CartItem> existingItem = cartRepository.findItemByCartIdAndListingId(cart.id(), request.listingId());
            int cumulativeQuantity;
            if (existingItem.isPresent()) {
                try {
                    cumulativeQuantity = Math.addExact(existingItem.get().quantity(), requestedQuantity);
                } catch (ArithmeticException e) {
                    throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK, "Requested quantity causes integer overflow");
                }
            } else {
                cumulativeQuantity = requestedQuantity;
            }

            if (cumulativeQuantity > offer.quantityAvailable()) {
                throw new ConflictException(
                        ErrorCode.INSUFFICIENT_STOCK,
                        "Requested quantity (" + cumulativeQuantity + ") exceeds available stock (" + offer.quantityAvailable() + ")");
            }

            cartRepository.upsertItem(cart.id(), request.listingId(), cumulativeQuantity, offer.price());
        }

        return buildCartResponse(cart);
    }

    private CartResponse buildCartResponse(Cart cart) {
        List<CartItemResponse> items = findAndEnrichItems(cart.id());
        int totalQuantity = items.stream().mapToInt(CartItemResponse::quantity).sum();
        BigDecimal subtotal = items.stream()
                .map(item -> (item.currentPrice() != null ? item.currentPrice() : item.unitPriceAtAdd())
                        .multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(
                cart.id(),
                cart.userId(),
                cart.sessionKey(),
                cart.currency(),
                items,
                totalQuantity,
                subtotal,
                cart.expiresAt());
    }

    /**
     * Updates the quantity of an item in the caller's cart.
     * Throws CART_ITEM_NOT_FOUND if the item does not belong to the current cart.
     * Throws INSUFFICIENT_STOCK if the quantity exceeds available stock.
     */
    @Transactional
    public CartItemResponse updateItemQuantity(
            AuthPrincipal principal, String sessionKey, long itemId, int quantity) {
        if (quantity < 1) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Quantity must be at least 1");
        }

        Cart cart = requireWritableCart(principal, sessionKey);

        CartItem existingItem = cartRepository.findItemByIdAndCartId(itemId, cart.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND));

        ListingOffer offer = pricingPort.offer(existingItem.listingId())
                .orElseThrow(() -> new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE));

        if (!offer.purchasable()) {
            throw new ConflictException(ErrorCode.LISTING_NOT_PURCHASABLE);
        }

        if (principal != null) {
            Optional<SellerProfile> sellerProfile = sellerProfileRepository.findByUserId(principal.userId());
            if (sellerProfile.isPresent() && sellerProfile.get().id() == offer.sellerProfileId()) {
                throw new ConflictException(ErrorCode.CANNOT_BUY_OWN_LISTING);
            }
        }

        if (quantity > offer.quantityAvailable()) {
            throw new ConflictException(
                    ErrorCode.INSUFFICIENT_STOCK,
                    "Requested quantity (" + quantity + ") exceeds available stock (" + offer.quantityAvailable() + ")");
        }

        CartItem updated = cartRepository.updateItemQuantity(itemId, cart.id(), quantity);
        if (updated == null) {
            throw new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND);
        }

        boolean priceChanged = updated.unitPriceAtAdd().compareTo(offer.price()) != 0;
        ListingCard card = cards.card(offer.catalogVariantId());

        CartListingDetails details = cartRepository.findCartListingDetails(List.of(existingItem.listingId())).get(existingItem.listingId());
        String imageUrl = resolveImageUrl(details);

        return new CartItemResponse(
                updated.id(),
                updated.cartId(),
                updated.listingId(),
                updated.quantity(),
                updated.unitPriceAtAdd(),
                offer.price(),
                priceChanged,
                offer.purchasable(),
                offer.quantityAvailable(),
                offer.sellerProfileId(),
                offer.catalogVariantId(),
                offer.condition(),
                offer.currency(),
                updated.addedAt(),
                updated.updatedAt(),
                details != null ? details.productName() : card == null ? null : card.productName(),
                details != null ? details.variantLabel() : card == null ? null : card.variantLabel(),
                details != null ? details.sellerName() : offer.sellerName(),
                imageUrl != null ? imageUrl : card == null ? null : card.officialImageUrl(),
                card == null ? null : card.productId(),
                details == null ? null : details.sellerUsername());
    }

    /** Only this explicit action accepts changed prices; quantity edits and checkout never do. */
    @Transactional
    public CartResponse acceptPrices(AuthPrincipal principal, String sessionKey, List<Long> cartItemIds) {
        Cart cart = requireWritableCart(principal, sessionKey);
        List<CartItem> items = cartRepository.findItemsByCartId(cart.id());
        if (cartItemIds != null) {
            Set<Long> requested = new HashSet<>(cartItemIds);
            Set<Long> owned = items.stream().map(CartItem::id).collect(Collectors.toSet());
            // Validate the entire selection before writing any prices.
            if (!owned.containsAll(requested)) {
                throw new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND);
            }
            items = items.stream().filter(item -> requested.contains(item.id())).toList();
        }
        if (!items.isEmpty()) {
            Map<Long, ListingOffer> offers = pricingPort.offers(
                    items.stream().map(CartItem::listingId).distinct().toList());
            for (CartItem item : items) {
                ListingOffer offer = offers.get(item.listingId());
                // Unavailable listings stay in the cart for the buyer to remove or deselect.
                if (offer != null && offer.purchasable() && offer.price() != null
                        && item.unitPriceAtAdd().compareTo(offer.price()) != 0) {
                    cartRepository.updateItemPrice(item.id(), cart.id(), offer.price());
                }
            }
        }
        return getCart(principal, principal == null ? sessionKey : null);
    }

    /**
     * Removes an item from the caller's cart.
     * Throws CART_ITEM_NOT_FOUND if the item does not belong to the current cart.
     */
    @Transactional
    public void removeItem(AuthPrincipal principal, String sessionKey, long itemId) {
        Cart cart = requireWritableCart(principal, sessionKey);

        boolean deleted = cartRepository.deleteItem(itemId, cart.id());
        if (!deleted) {
            throw new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    /** The cart a write targets; for a signed-in caller that cart is created if missing. */
    private Cart requireWritableCart(AuthPrincipal principal, String sessionKey) {
        String guestKey = CartSessionKey.normalize(sessionKey);
        if (principal != null) {
            return resolveCartForUser(principal.userId(), guestKey);
        }
        if (guestKey == null) {
            throw new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return cartRepository.findBySessionKey(guestKey)
                .orElseThrow(() -> new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND));
    }

    /** Read-only lookup: never creates, never merges. */
    private Optional<Cart> findCart(AuthPrincipal principal, String sessionKey) {
        if (principal != null) {
            return cartRepository.findByUserId(principal.userId());
        }
        String guestKey = CartSessionKey.normalize(sessionKey);
        return guestKey == null ? Optional.empty() : cartRepository.findBySessionKey(guestKey);
    }

    private Cart resolveCartForUser(long userId, String guestSessionKey) {
        Cart userCart = cartRepository.getOrCreateForUser(userId);
        if (guestSessionKey != null && !guestSessionKey.isBlank()) {
            cartRepository.findBySessionKey(guestSessionKey)
                    .ifPresent(guestCart -> {
                        if (guestCart.id() != userCart.id()) {
                            // The key travels in a header that proxies and access logs record,
                            // so every merge is written down with the account it landed in.
                            log.info("Merging guest cart {} into cart {} of user {}",
                                    guestCart.id(), userCart.id(), userId);
                            cartRepository.mergeGuestCartIntoUserCart(guestCart.id(), userCart.id());
                        }
                    });
        }
        return userCart;
    }

    private List<CartItemResponse> findAndEnrichItems(long cartId) {
        List<CartItem> items = cartRepository.findItemsByCartId(cartId);
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> listingIds = items.stream().map(CartItem::listingId).distinct().toList();
        Map<Long, ListingOffer> offers = pricingPort.offers(listingIds);
        List<Long> variantIds = offers.values().stream().map(ListingOffer::catalogVariantId).distinct().toList();
        Map<Long, ListingCard> cardsByVariant = variantIds.isEmpty() ? Map.of() : cards.cards(variantIds);

        Map<Long, CartListingDetails> listingDetails = cartRepository.findCartListingDetails(listingIds);

        return items.stream().map(item -> {
            ListingOffer offer = offers.get(item.listingId());
            BigDecimal currentPrice = offer != null ? offer.price() : null;
            boolean priceChanged = offer == null || offer.price() == null
                    || item.unitPriceAtAdd().compareTo(offer.price()) != 0;
            boolean purchasable = offer != null && offer.purchasable();
            ListingCard card = offer == null ? null : cardsByVariant.get(offer.catalogVariantId());

            CartListingDetails details = listingDetails.get(item.listingId());
            String imageUrl = resolveImageUrl(details);

            return new CartItemResponse(
                    item.id(),
                    item.cartId(),
                    item.listingId(),
                    item.quantity(),
                    item.unitPriceAtAdd(),
                    currentPrice,
                    priceChanged,
                    purchasable,
                    offer != null ? offer.quantityAvailable() : null,
                    offer != null ? offer.sellerProfileId() : null,
                    offer != null ? offer.catalogVariantId() : null,
                    offer != null ? offer.condition() : null,
                    offer != null ? offer.currency() : "THB",
                    item.addedAt(),
                    item.updatedAt(),
                    details != null ? details.productName() : card == null ? null : card.productName(),
                    details != null ? details.variantLabel() : card == null ? null : card.variantLabel(),
                    details != null ? details.sellerName() : offer != null ? offer.sellerName() : null,
                    imageUrl != null ? imageUrl : card == null ? null : card.officialImageUrl(),
                    card == null ? null : card.productId(),
                    details == null ? null : details.sellerUsername());
        }).toList();
    }

    private String resolveImageUrl(CartListingDetails details) {
        if (details == null || details.imageKey() == null || storageService == null) {
            return null;
        }
        try {
            return storageService.presignDownload(details.imageKey());
        } catch (Exception e) {
            log.warn("Failed to presign download for image {}: {}", details.imageKey(), e.getMessage());
            return null;
        }
    }
}
