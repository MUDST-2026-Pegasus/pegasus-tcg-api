package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CartItemResponse;
import com.pegasus.pegasustcgapi.dto.CartResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.port.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.CartRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages shopping carts for both guest sessions and authenticated users.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final PricingPort pricingPort;
    private final SellerProfileRepository sellerProfileRepository;

    public CartService(
            CartRepository cartRepository,
            PricingPort pricingPort,
            SellerProfileRepository sellerProfileRepository) {
        this.cartRepository = cartRepository;
        this.pricingPort = pricingPort;
        this.sellerProfileRepository = sellerProfileRepository;
    }

    public record AddResult(CartItemResponse item, String sessionKey) {
    }

    /**
     * Adds or updates an item in the cart.
     * On first POST for a guest, generates a 128-bit session key and returns it.
     */
    @Transactional
    public AddResult addItem(AuthPrincipal principal, String sessionKey, CartItemRequest request) {
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

        Cart cart;
        String returnSessionKey = null;

        if (principal != null) {
            cart = resolveCartForUser(principal.userId(), sessionKey);
        } else {
            String guestKey = sessionKey;
            if (guestKey != null && !guestKey.isBlank()) {
                cart = cartRepository.findBySessionKey(guestKey)
                        .orElseGet(() -> cartRepository.createForSession(guestKey));
                returnSessionKey = guestKey;
            } else {
                String newKey = UUID.randomUUID().toString();
                cart = cartRepository.createForSession(newKey);
                returnSessionKey = newKey;
            }
        }

        int quantity = request.resolvedQuantity();
        CartItem item = cartRepository.upsertItem(cart.id(), request.listingId(), quantity, offer.price());

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
                item.updatedAt());

        return new AddResult(response, returnSessionKey);
    }

    /**
     * Retrieves all items in the caller's cart, enriched with current batch-queried pricing.
     */
    @Transactional
    public List<CartItemResponse> getCartItems(AuthPrincipal principal, String sessionKey) {
        if (principal != null) {
            Cart cart = resolveCartForUser(principal.userId(), sessionKey);
            return findAndEnrichItems(cart.id());
        }

        if (sessionKey == null || sessionKey.isBlank()) {
            return List.of();
        }

        Optional<Cart> guestCart = cartRepository.findBySessionKey(sessionKey);
        if (guestCart.isEmpty()) {
            return List.of();
        }

        return findAndEnrichItems(guestCart.get().id());
    }

    /**
     * Retrieves the full cart with items and financial summary.
     */
    @Transactional
    public CartResponse getCart(AuthPrincipal principal, String sessionKey) {
        Cart cart;
        if (principal != null) {
            cart = resolveCartForUser(principal.userId(), sessionKey);
        } else {
            if (sessionKey == null || sessionKey.isBlank()) {
                return new CartResponse(0L, null, null, "THB", List.of(), 0, BigDecimal.ZERO);
            }
            cart = cartRepository.findBySessionKey(sessionKey)
                    .orElseGet(() -> new Cart(0L, null, sessionKey, "THB", null, null, null));
            if (cart.id() == 0L) {
                return new CartResponse(0L, null, sessionKey, "THB", List.of(), 0, BigDecimal.ZERO);
            }
        }

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
                subtotal);
    }

    /**
     * Removes an item from the caller's cart.
     * Throws CART_ITEM_NOT_FOUND if the item does not belong to the current cart.
     */
    @Transactional
    public void removeItem(AuthPrincipal principal, String sessionKey, long itemId) {
        Cart cart;
        if (principal != null) {
            cart = resolveCartForUser(principal.userId(), sessionKey);
        } else {
            if (sessionKey == null || sessionKey.isBlank()) {
                throw new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND);
            }
            cart = cartRepository.findBySessionKey(sessionKey)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND));
        }

        boolean deleted = cartRepository.deleteItem(itemId, cart.id());
        if (!deleted) {
            throw new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    private Cart resolveCartForUser(long userId, String guestSessionKey) {
        Cart userCart = cartRepository.getOrCreateForUser(userId);
        if (guestSessionKey != null && !guestSessionKey.isBlank()) {
            cartRepository.findBySessionKey(guestSessionKey)
                    .ifPresent(guestCart -> {
                        if (guestCart.id() != userCart.id()) {
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

        return items.stream().map(item -> {
            ListingOffer offer = offers.get(item.listingId());
            BigDecimal currentPrice = offer != null ? offer.price() : null;
            boolean priceChanged = offer == null || offer.price() == null
                    || item.unitPriceAtAdd().compareTo(offer.price()) != 0;
            boolean purchasable = offer != null && offer.purchasable();

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
                    item.updatedAt());
        }).toList();
    }
}
