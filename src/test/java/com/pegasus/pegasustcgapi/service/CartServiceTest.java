package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CartItemResponse;
import com.pegasus.pegasustcgapi.dto.CartResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.port.PricingPort.ListingOffer;
import com.pegasus.pegasustcgapi.repository.CartRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CartService")
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private PricingPort pricingPort;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @InjectMocks
    private CartService cartService;

    private static AuthPrincipal principal(long userId) {
        return new AuthPrincipal(userId, "user@example.com", "testuser", Set.of(RoleCode.BUYER));
    }

    private static ListingOffer offer(long listingId, long sellerProfileId, BigDecimal price, boolean purchasable) {
        return new ListingOffer(
                listingId,
                sellerProfileId,
                1L,
                CardCondition.NM,
                price,
                "THB",
                10,
                ListingStatus.ACTIVE,
                purchasable);
    }

    @Nested
    @DisplayName("Session Management & Adding Items")
    class AddItemTests {

        @Test
        @DisplayName("generates 128-bit session key on first POST for guest")
        void guestFirstPostGeneratesSessionKey() {
            CartItemRequest request = new CartItemRequest(10L, 2);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("150.00"), true);

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(cartRepository.createForSession(anyString())).willAnswer(inv ->
                    new Cart(1L, null, inv.getArgument(0), "THB", OffsetDateTime.now(), OffsetDateTime.now(), null));
            given(cartRepository.upsertItem(eq(1L), eq(10L), eq(2), eq(new BigDecimal("150.00"))))
                    .willReturn(new CartItem(100L, 1L, 10L, 2, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now()));

            CartService.AddResult result = cartService.addItem(null, null, request);

            assertThat(result.sessionKey()).isNotNull();
            assertThat(UUID.fromString(result.sessionKey())).isNotNull(); // Valid 128-bit UUID
            assertThat(result.item().listingId()).isEqualTo(10L);
            assertThat(result.item().quantity()).isEqualTo(2);
            assertThat(result.item().unitPriceAtAdd()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("reuses existing session key for returning guest")
        void guestReusesExistingSessionKey() {
            CartItemRequest request = new CartItemRequest(10L, 1);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("150.00"), true);
            String existingKey = UUID.randomUUID().toString();
            Cart existingCart = new Cart(1L, null, existingKey, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(cartRepository.findBySessionKey(existingKey)).willReturn(Optional.of(existingCart));
            given(cartRepository.upsertItem(eq(1L), eq(10L), eq(1), eq(new BigDecimal("150.00"))))
                    .willReturn(new CartItem(100L, 1L, 10L, 1, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now()));

            CartService.AddResult result = cartService.addItem(null, existingKey, request);

            assertThat(result.sessionKey()).isEqualTo(existingKey);
            verify(cartRepository, never()).createForSession(any());
        }

        @Test
        @DisplayName("authenticated user uses user_id and returns no session key header")
        void authenticatedUserUsesPermanentCart() {
            AuthPrincipal user = principal(42L);
            CartItemRequest request = new CartItemRequest(10L, 3);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("200.00"), true);
            Cart userCart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());
            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.upsertItem(eq(5L), eq(10L), eq(3), eq(new BigDecimal("200.00"))))
                    .willReturn(new CartItem(101L, 5L, 10L, 3, new BigDecimal("200.00"), OffsetDateTime.now(), OffsetDateTime.now()));

            CartService.AddResult result = cartService.addItem(user, null, request);

            assertThat(result.sessionKey()).isNull();
            assertThat(result.item().quantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("throws LISTING_NOT_PURCHASABLE when listing is not purchasable")
        void unpurchasableListingThrows() {
            CartItemRequest request = new CartItemRequest(10L, 1);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("100.00"), false);

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));

            assertThatThrownBy(() -> cartService.addItem(null, null, request))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LISTING_NOT_PURCHASABLE));
        }

        @Test
        @DisplayName("throws CANNOT_BUY_OWN_LISTING when seller attempts to buy their own listing")
        void sellerCannotBuyOwnListing() {
            AuthPrincipal seller = principal(42L);
            CartItemRequest request = new CartItemRequest(10L, 1);
            ListingOffer listingOffer = offer(10L, 77L, new BigDecimal("100.00"), true);
            SellerProfile profile = new SellerProfile(
                    77L, 42L, SellerStatus.VERIFIED, OffsetDateTime.now(), null, (short) 1, false, true, OffsetDateTime.now());

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.of(profile));

            assertThatThrownBy(() -> cartService.addItem(seller, null, request))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_BUY_OWN_LISTING));
        }

        @Test
        @DisplayName("throws INSUFFICIENT_STOCK when requested quantity exceeds available stock on new item")
        void requestedQuantityExceedsAvailableStockThrows() {
            CartItemRequest request = new CartItemRequest(10L, 15);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("100.00"), true); // available = 10

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(cartRepository.createForSession(anyString())).willAnswer(inv ->
                    new Cart(1L, null, inv.getArgument(0), "THB", OffsetDateTime.now(), OffsetDateTime.now(), null));

            assertThatThrownBy(() -> cartService.addItem(null, null, request))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        }

        @Test
        @DisplayName("throws INSUFFICIENT_STOCK when cumulative quantity exceeds available stock on existing item")
        void cumulativeQuantityExceedsAvailableStockThrows() {
            CartItemRequest request = new CartItemRequest(10L, 5);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("100.00"), true); // available = 10
            Cart cart = new Cart(1L, null, "guest-key", "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 1L, 10L, 8, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(cartRepository.findBySessionKey("guest-key")).willReturn(Optional.of(cart));
            given(cartRepository.findItemByCartIdAndListingId(1L, 10L)).willReturn(Optional.of(existingItem));

            // 8 + 5 = 13 > 10
            assertThatThrownBy(() -> cartService.addItem(null, "guest-key", request))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        }

        @Test
        @DisplayName("adds cumulative quantity when existing item and added quantity within available stock")
        void addsCumulativeQuantityWithinStock() {
            CartItemRequest request = new CartItemRequest(10L, 3);
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("100.00"), true); // available = 10
            Cart cart = new Cart(1L, null, "guest-key", "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 1L, 10L, 4, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());

            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(cartRepository.findBySessionKey("guest-key")).willReturn(Optional.of(cart));
            given(cartRepository.findItemByCartIdAndListingId(1L, 10L)).willReturn(Optional.of(existingItem));
            given(cartRepository.upsertItem(eq(1L), eq(10L), eq(7), eq(new BigDecimal("100.00"))))
                    .willReturn(new CartItem(100L, 1L, 10L, 7, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now()));

            CartService.AddResult result = cartService.addItem(null, "guest-key", request);

            assertThat(result.item().quantity()).isEqualTo(7);
            verify(cartRepository).upsertItem(1L, 10L, 7, new BigDecimal("100.00"));
        }
    }

    @Nested
    @DisplayName("Merge Logic")
    class MergeLogicTests {

        @Test
        @DisplayName("merges guest cart into user permanent cart when session key header is present")
        void mergesGuestCartIntoUserCart() {
            AuthPrincipal user = principal(42L);
            String guestKey = "guest-session-123";
            Cart guestCart = new Cart(1L, null, guestKey, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findBySessionKey(guestKey)).willReturn(Optional.of(guestCart));
            given(cartRepository.findItemsByCartId(2L)).willReturn(List.of());

            cartService.getCartItems(user, guestKey);

            verify(cartRepository).mergeGuestCartIntoUserCart(1L, 2L);
        }
    }

    @Nested
    @DisplayName("View Cart & Batch Pricing")
    class ViewCartTests {

        @Test
        @DisplayName("calls pricingPort.offers EXACTLY ONCE and flags price changes")
        void batchQueriesPricingAndFlagsPriceChange() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item1 = new CartItem(1L, 2L, 10L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            CartItem item2 = new CartItem(2L, 2L, 20L, 2, new BigDecimal("50.00"), OffsetDateTime.now(), OffsetDateTime.now());

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemsByCartId(2L)).willReturn(List.of(item1, item2));

            // Item 1 price changed to 120.00; Item 2 price unchanged at 50.00
            ListingOffer offer1 = offer(10L, 99L, new BigDecimal("120.00"), true);
            ListingOffer offer2 = offer(20L, 99L, new BigDecimal("50.00"), true);

            given(pricingPort.offers(List.of(10L, 20L))).willReturn(Map.of(10L, offer1, 20L, offer2));

            List<CartItemResponse> items = cartService.getCartItems(user, null);

            // Verify PricingPort.offers batch call was executed EXACTLY ONCE
            verify(pricingPort, times(1)).offers(any());
            verify(pricingPort, never()).offer(anyLong());

            assertThat(items).hasSize(2);
            CartItemResponse res1 = items.stream().filter(i -> i.listingId() == 10L).findFirst().orElseThrow();
            CartItemResponse res2 = items.stream().filter(i -> i.listingId() == 20L).findFirst().orElseThrow();

            assertThat(res1.priceChanged()).isTrue();
            assertThat(res1.currentPrice()).isEqualByComparingTo("120.00");
            assertThat(res1.unitPriceAtAdd()).isEqualByComparingTo("100.00");

            assertThat(res2.priceChanged()).isFalse();
            assertThat(res2.currentPrice()).isEqualByComparingTo("50.00");
            assertThat(res2.unitPriceAtAdd()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("empty cart makes no batch pricing call")
        void emptyCartMakesNoPricingCall() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemsByCartId(2L)).willReturn(List.of());

            List<CartItemResponse> items = cartService.getCartItems(user, null);

            assertThat(items).isEmpty();
            verify(pricingPort, never()).offers(any());
        }

        @Test
        @DisplayName("getCart includes expiresAt for guest cart")
        void getCartIncludesExpiresAtForGuestCart() {
            String guestKey = "guest-session-456";
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(1);
            Cart guestCart = new Cart(1L, null, guestKey, "THB", OffsetDateTime.now(), OffsetDateTime.now(), expiresAt);

            given(cartRepository.findBySessionKey(guestKey)).willReturn(Optional.of(guestCart));
            given(cartRepository.findItemsByCartId(1L)).willReturn(List.of());

            CartResponse response = cartService.getCart(null, guestKey);

            assertThat(response.expiresAt()).isEqualTo(expiresAt);
            assertThat(response.sessionKey()).isEqualTo(guestKey);
            assertThat(response.userId()).isNull();
        }
    }

    @Nested
    @DisplayName("Delete Items")
    class DeleteItemTests {

        @Test
        @DisplayName("successfully removes item from cart")
        void successfullyRemovesItem() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.deleteItem(100L, 2L)).willReturn(true);

            cartService.removeItem(user, null, 100L);

            verify(cartRepository).deleteItem(100L, 2L);
        }

        @Test
        @DisplayName("throws CART_ITEM_NOT_FOUND when item is not in current cart")
        void itemNotInCartThrowsNotFound() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.deleteItem(999L, 2L)).willReturn(false);

            assertThatThrownBy(() -> cartService.removeItem(user, null, 999L))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND));
        }

        @Test
        @DisplayName("guest without session header throws CART_ITEM_NOT_FOUND on delete")
        void guestWithoutSessionThrowsNotFound() {
            assertThatThrownBy(() -> cartService.removeItem(null, null, 100L))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("Update Item Quantity")
    class UpdateItemTests {

        @Test
        @DisplayName("successfully updates item quantity for authenticated user")
        void updatesQuantityForAuthenticatedUser() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 2L, 10L, 1, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("150.00"), true); // available = 10

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemByIdAndCartId(100L, 2L)).willReturn(Optional.of(existingItem));
            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());
            given(cartRepository.updateItemQuantity(100L, 2L, 5))
                    .willReturn(new CartItem(100L, 2L, 10L, 5, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now()));

            CartItemResponse response = cartService.updateItemQuantity(user, null, 100L, 5);

            assertThat(response.id()).isEqualTo(100L);
            assertThat(response.quantity()).isEqualTo(5);
            verify(cartRepository).updateItemQuantity(100L, 2L, 5);
        }

        @Test
        @DisplayName("successfully updates item quantity for guest session")
        void updatesQuantityForGuestSession() {
            String guestKey = "guest-key-123";
            Cart guestCart = new Cart(1L, null, guestKey, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 1L, 10L, 2, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("150.00"), true); // available = 10

            given(cartRepository.findBySessionKey(guestKey)).willReturn(Optional.of(guestCart));
            given(cartRepository.findItemByIdAndCartId(100L, 1L)).willReturn(Optional.of(existingItem));
            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(cartRepository.updateItemQuantity(100L, 1L, 4))
                    .willReturn(new CartItem(100L, 1L, 10L, 4, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now()));

            CartItemResponse response = cartService.updateItemQuantity(null, guestKey, 100L, 4);

            assertThat(response.id()).isEqualTo(100L);
            assertThat(response.quantity()).isEqualTo(4);
            verify(cartRepository).updateItemQuantity(100L, 1L, 4);
        }

        @Test
        @DisplayName("throws VALIDATION_FAILED when quantity < 1")
        void quantityLessThanOneThrows() {
            assertThatThrownBy(() -> cartService.updateItemQuantity(null, "guest-key", 100L, 0))
                    .isInstanceOfSatisfying(com.pegasus.pegasustcgapi.exception.BadRequestException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("throws CART_ITEM_NOT_FOUND when guest session is missing")
        void guestWithoutSessionThrowsNotFound() {
            assertThatThrownBy(() -> cartService.updateItemQuantity(null, null, 100L, 2))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND));
        }

        @Test
        @DisplayName("throws CART_ITEM_NOT_FOUND when item not in cart")
        void itemNotInCartThrowsNotFound() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemByIdAndCartId(999L, 2L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.updateItemQuantity(user, null, 999L, 2))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND));
        }

        @Test
        @DisplayName("throws INSUFFICIENT_STOCK when updated quantity exceeds available stock")
        void quantityExceedsStockThrows() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 2L, 10L, 1, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("150.00"), true); // available = 10

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemByIdAndCartId(100L, 2L)).willReturn(Optional.of(existingItem));
            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.updateItemQuantity(user, null, 100L, 15))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        }

        @Test
        @DisplayName("throws LISTING_NOT_PURCHASABLE when listing is no longer purchasable")
        void unpurchasableListingThrows() {
            AuthPrincipal user = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 2L, 10L, 1, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer listingOffer = offer(10L, 99L, new BigDecimal("150.00"), false);

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemByIdAndCartId(100L, 2L)).willReturn(Optional.of(existingItem));
            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));

            assertThatThrownBy(() -> cartService.updateItemQuantity(user, null, 100L, 2))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LISTING_NOT_PURCHASABLE));
        }

        @Test
        @DisplayName("throws CANNOT_BUY_OWN_LISTING when seller attempts to update own listing")
        void sellerCannotUpdateOwnListing() {
            AuthPrincipal seller = principal(42L);
            Cart userCart = new Cart(2L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem existingItem = new CartItem(100L, 2L, 10L, 1, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer listingOffer = offer(10L, 77L, new BigDecimal("150.00"), true);
            SellerProfile profile = new SellerProfile(
                    77L, 42L, SellerStatus.VERIFIED, OffsetDateTime.now(), null, (short) 1, false, true, OffsetDateTime.now());

            given(cartRepository.getOrCreateForUser(42L)).willReturn(userCart);
            given(cartRepository.findItemByIdAndCartId(100L, 2L)).willReturn(Optional.of(existingItem));
            given(pricingPort.offer(10L)).willReturn(Optional.of(listingOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.of(profile));

            assertThatThrownBy(() -> cartService.updateItemQuantity(seller, null, 100L, 2))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_BUY_OWN_LISTING));
        }
    }
}
