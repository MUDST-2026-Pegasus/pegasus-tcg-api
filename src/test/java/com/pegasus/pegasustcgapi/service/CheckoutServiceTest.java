package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
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
@DisplayName("CheckoutService")
class CheckoutServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartService cartService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @Mock
    private PricingPort pricingPort;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private LedgerPort ledgerPort;

    @InjectMocks
    private CheckoutService checkoutService;

    private static AuthPrincipal principal(long userId) {
        return new AuthPrincipal(userId, "buyer@example.com", "buyer", Set.of(RoleCode.BUYER));
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

    private static SalesOrderRecord salesOrderRecord(long id, long buyerId, String orderNumber, String idempotencyKey) {
        SalesOrderRecord record = new SalesOrderRecord();
        record.setId(id);
        record.setBuyerId(buyerId);
        record.setOrderNumber(orderNumber);
        record.setStatus("PENDING_PAYMENT");
        record.setCurrency("THB");
        record.setItemsSubtotal(new BigDecimal("300.00"));
        record.setShippingTotal(BigDecimal.ZERO);
        record.setDiscountTotal(BigDecimal.ZERO);
        record.setGrandTotal(new BigDecimal("300.00"));
        record.setPlacedAt(OffsetDateTime.now());
        record.setIdempotencyKey(idempotencyKey);
        return record;
    }

    private static SellerOrderRecord sellerOrderRecord(long id, long salesOrderId, long sellerProfileId, String sellerOrderNumber) {
        SellerOrderRecord record = new SellerOrderRecord();
        record.setId(id);
        record.setSalesOrderId(salesOrderId);
        record.setSellerProfileId(sellerProfileId);
        record.setSellerOrderNumber(sellerOrderNumber);
        record.setStatus("PENDING_PAYMENT");
        record.setItemsSubtotal(new BigDecimal("300.00"));
        record.setShippingFee(BigDecimal.ZERO);
        record.setDiscountAmount(BigDecimal.ZERO);
        record.setGrandTotal(new BigDecimal("300.00"));
        record.setCommissionAmount(new BigDecimal("30.00"));
        record.setSellerNetAmount(new BigDecimal("270.00"));
        return record;
    }

    private static OrderItemRecord orderItemRecord(long id, long sellerOrderId, long listingId, int quantity, BigDecimal unitPrice) {
        OrderItemRecord record = new OrderItemRecord();
        record.setId(id);
        record.setSellerOrderId(sellerOrderId);
        record.setListingId(listingId);
        record.setCatalogVariantId(1L);
        record.setQuantity(quantity);
        record.setUnitPrice(unitPrice);
        record.setLineTotal(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        record.setProductNameSnapshot("Charizard EX");
        record.setVariantLabelSnapshot("EN / Foil");
        record.setConditionSnapshot("NM");
        return record;
    }

    @Nested
    @DisplayName("Authentication & Authorization")
    class AuthTests {

        @Test
        @DisplayName("throws UNAUTHENTICATED when principal is null")
        void nullPrincipalThrows() {
            assertThatThrownBy(() -> checkoutService.checkout(null, "idem-1", null, null))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("throws IDEMPOTENCY_KEY_REQUIRED when key is null or blank")
        void nullOrBlankKeyThrows() {
            AuthPrincipal buyer = principal(42L);

            assertThatThrownBy(() -> checkoutService.checkout(buyer, null, null, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "   ", null, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        }

        @Test
        @DisplayName("returns existing order immediately when idempotency key belongs to same buyer")
        void returnsExistingOrderForSameBuyer() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-key-123";
            SalesOrderRecord existingOrder = salesOrderRecord(1L, 42L, "ORD-123", idemKey);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.of(existingOrder));
            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(existingOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of());

            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, null);

            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(1L);
            assertThat(response.orderNumber()).isEqualTo("ORD-123");
            verify(cartRepository, never()).findByUserId(anyLong());
            verify(inventoryPort, never()).reserve(any());
        }

        @Test
        @DisplayName("throws IDEMPOTENCY_KEY_CONFLICT when key belongs to different buyer")
        void keyConflictThrowsForDifferentBuyer() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-key-used-by-other";
            SalesOrderRecord otherUserOrder = salesOrderRecord(1L, 99L, "ORD-999", idemKey);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.of(otherUserOrder));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, idemKey, null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        }
    }

    @Nested
    @DisplayName("Cart & Item Validation")
    class ValidationTests {

        @Test
        @DisplayName("merges guest cart first if guestSessionKey is provided")
        void mergesGuestCartIfSessionKeyPresent() {
            AuthPrincipal buyer = principal(42L);
            String guestKey = "guest-session-abc";
            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", guestKey, null))
                    .isInstanceOf(ConflictException.class);

            verify(cartService).getCart(buyer, guestKey);
        }

        @Test
        @DisplayName("throws CART_EMPTY when user has no cart or cart items are empty")
        void emptyCartThrows() {
            AuthPrincipal buyer = principal(42L);
            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_EMPTY));

            Cart emptyCart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(emptyCart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_EMPTY));
        }

        @Test
        @DisplayName("throws CART_PRICE_CHANGED when offer price differs from unit price at add")
        void priceMismatchThrows() {
            AuthPrincipal buyer = principal(42L);
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 2, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offerWithNewPrice = offer(100L, 88L, new BigDecimal("120.00"), true);

            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offerWithNewPrice));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_PRICE_CHANGED));
        }

        @Test
        @DisplayName("throws LISTING_NOT_PURCHASABLE when offer is missing or purchasable is false")
        void unpurchasableListingThrows() {
            AuthPrincipal buyer = principal(42L);
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 2, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer unpurchasableOffer = offer(100L, 88L, new BigDecimal("100.00"), false);

            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, unpurchasableOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LISTING_NOT_PURCHASABLE));

            given(pricingPort.offers(List.of(100L))).willReturn(Map.of());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LISTING_NOT_PURCHASABLE));
        }

        @Test
        @DisplayName("throws CANNOT_BUY_OWN_LISTING when buyer owns the listing")
        void buyerCannotBuyOwnListing() {
            AuthPrincipal buyer = principal(42L);
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer myOffer = offer(100L, 77L, new BigDecimal("100.00"), true);
            SellerProfile myProfile = new SellerProfile(
                    77L, 42L, SellerStatus.VERIFIED, OffsetDateTime.now(), null, (short) 1, false, true, OffsetDateTime.now());

            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, myOffer));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.of(myProfile));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CANNOT_BUY_OWN_LISTING));
        }
    }

    @Nested
    @DisplayName("Order Creation & Transaction Execution")
    class OrderExecutionTests {

        @Test
        @DisplayName("successfully converts cart into sales order and seller orders")
        void successfulCheckout() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-key-checkout-1";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item1 = new CartItem(10L, 5L, 100L, 2, new BigDecimal("150.00"), OffsetDateTime.now(), OffsetDateTime.now());
            CartItem item2 = new CartItem(11L, 5L, 101L, 1, new BigDecimal("80.00"), OffsetDateTime.now(), OffsetDateTime.now());

            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("150.00"), true);
            ListingOffer offer2 = offer(101L, 88L, new BigDecimal("80.00"), true);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item1, item2));
            given(pricingPort.offers(List.of(100L, 101L))).willReturn(Map.of(100L, offer1, 101L, offer2));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            // Reserved units
            ReservedUnit unit1 = new ReservedUnit(1001L, UUID.randomUUID());
            ReservedUnit unit2 = new ReservedUnit(1002L, UUID.randomUUID());
            ReservedUnit unit3 = new ReservedUnit(1003L, UUID.randomUUID());
            given(inventoryPort.reserve(Map.of(100L, 2, 101L, 1)))
                    .willReturn(Map.of(100L, List.of(unit1, unit2), 101L, List.of(unit3)));

            // Listing snapshot details
            ListingSnapshotDetails details1 = new ListingSnapshotDetails(100L, 1L, "NM", "Charizard", "EN / Foil", "Pokemon", "img1.jpg");
            ListingSnapshotDetails details2 = new ListingSnapshotDetails(101L, 2L, "LP", "Pikachu", "JP / Normal", "Pokemon", "img2.jpg");
            given(orderRepository.findListingDetails(List.of(100L, 101L))).willReturn(Map.of(100L, details1, 101L, details2));

            // Commission calculation
            given(ledgerPort.quoteCommission(new BigDecimal("300.00"))).willReturn(new BigDecimal("30.00"));
            given(ledgerPort.quoteCommission(new BigDecimal("80.00"))).willReturn(new BigDecimal("8.00"));

            // Insert records
            SalesOrderRecord salesOrder = salesOrderRecord(1L, 42L, "ORD-123", idemKey);
            SellerOrderRecord sellerOrder1 = sellerOrderRecord(10L, 1L, 77L, "ORD-123-S77");
            SellerOrderRecord sellerOrder2 = sellerOrderRecord(11L, 1L, 88L, "ORD-123-S88");
            OrderItemRecord orderItem1 = orderItemRecord(1000L, 10L, 100L, 2, new BigDecimal("150.00"));
            OrderItemRecord orderItem2 = orderItemRecord(1001L, 11L, 101L, 1, new BigDecimal("80.00"));

            given(orderRepository.insertSalesOrder(anyString(), eq(42L), eq("THB"),
                    eq(new BigDecimal("380.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("380.00")), eq(null), any(), eq(null), eq(idemKey)))
                    .willReturn(salesOrder);

            given(orderRepository.insertSellerOrder(eq(1L), eq(77L), anyString(),
                    eq(new BigDecimal("300.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("300.00")), eq(new BigDecimal("30.00")), eq(new BigDecimal("270.00"))))
                    .willReturn(sellerOrder1);

            given(orderRepository.insertSellerOrder(eq(1L), eq(88L), anyString(),
                    eq(new BigDecimal("80.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("80.00")), eq(new BigDecimal("8.00")), eq(new BigDecimal("72.00"))))
                    .willReturn(sellerOrder2);

            given(orderRepository.insertOrderItem(eq(10L), eq(100L), eq(1L), eq(2),
                    eq(new BigDecimal("150.00")), eq(new BigDecimal("300.00")),
                    eq("Charizard"), eq("EN / Foil"), eq("NM"), eq("Pokemon"), eq("img1.jpg")))
                    .willReturn(orderItem1);

            given(orderRepository.insertOrderItem(eq(11L), eq(101L), eq(2L), eq(1),
                    eq(new BigDecimal("80.00")), eq(new BigDecimal("80.00")),
                    eq("Pikachu"), eq("JP / Normal"), eq("LP"), eq("Pokemon"), eq("img2.jpg")))
                    .willReturn(orderItem2);

            // Fetch order details query mocks
            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(salesOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of(sellerOrder1, sellerOrder2));
            given(orderRepository.findOrderItemsBySellerOrderId(10L)).willReturn(List.of(orderItem1));
            given(orderRepository.findOrderItemsBySellerOrderId(11L)).willReturn(List.of(orderItem2));
            given(orderRepository.findUnitIdsByOrderItemId(1000L)).willReturn(List.of(1001L, 1002L));
            given(orderRepository.findUnitIdsByOrderItemId(1001L)).willReturn(List.of(1003L));

            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, null);

            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(1L);
            assertThat(response.sellerOrders()).hasSize(2);
            assertThat(response.sellerOrders().get(0).items()).hasSize(1);
            assertThat(response.sellerOrders().get(0).items().get(0).unitIds()).containsExactly(1001L, 1002L);
            assertThat(response.sellerOrders().get(1).items().get(0).unitIds()).containsExactly(1003L);

            // Inventory and cart cleanup verified
            verify(orderRepository).insertOrderItemUnits(1000L, List.of(1001L, 1002L));
            verify(orderRepository).insertOrderItemUnits(1001L, List.of(1003L));
            verify(cartRepository).deleteCart(5L);
        }

        @Test
        @DisplayName("snapshots valid shipping address when shippingAddressId is provided")
        void snapshotsShippingAddress() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-addr-test";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);
            Address address = new Address(99L, 42L, "Home", "Somchai", "0812345678", "123 Sukhumvit", "Apt 4B",
                    "Khlong Toei", "Khlong Toei", "Bangkok", "10110", "TH", true, true, OffsetDateTime.now());

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer1));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());
            given(addressRepository.findByIdAndUserId(99L, 42L)).willReturn(Optional.of(address));

            ReservedUnit unit = new ReservedUnit(1001L, UUID.randomUUID());
            given(inventoryPort.reserve(any())).willReturn(Map.of(100L, List.of(unit)));
            given(ledgerPort.quoteCommission(any())).willReturn(BigDecimal.TEN);

            SalesOrderRecord salesOrder = salesOrderRecord(1L, 42L, "ORD-123", idemKey);
            SellerOrderRecord sellerOrder = sellerOrderRecord(10L, 1L, 77L, "ORD-123-S77");
            OrderItemRecord orderItem = orderItemRecord(1000L, 10L, 100L, 1, new BigDecimal("100.00"));

            given(orderRepository.insertSalesOrder(anyString(), eq(42L), anyString(), any(), any(), any(), any(), eq(99L), any(), any(), eq(idemKey)))
                    .willReturn(salesOrder);
            given(orderRepository.insertSellerOrder(anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(), any()))
                    .willReturn(sellerOrder);
            given(orderRepository.insertOrderItem(anyLong(), anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(orderItem);

            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(salesOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of(sellerOrder));
            given(orderRepository.findOrderItemsBySellerOrderId(10L)).willReturn(List.of(orderItem));
            given(orderRepository.findUnitIdsByOrderItemId(1000L)).willReturn(List.of(1001L));

            CheckoutRequest req = new CheckoutRequest(99L, "Please handle with care");
            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, req);

            assertThat(response).isNotNull();
            verify(addressRepository).findByIdAndUserId(99L, 42L);
        }

        @Test
        @DisplayName("failure during inventory reservation halts order creation and leaves cart untouched")
        void inventoryFailureRollsBack() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-fail-inv";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 5, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer1));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            given(inventoryPort.reserve(any()))
                    .willThrow(new ConflictException(ErrorCode.INSUFFICIENT_STOCK));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, idemKey, null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

            verify(orderRepository, never()).insertSalesOrder(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), anyString());
            verify(cartRepository, never()).deleteCart(anyLong());
        }

        @Test
        @DisplayName("failure during commission calculation halts order creation and leaves cart untouched")
        void ledgerFailureRollsBack() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-fail-ledger";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer1));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            ReservedUnit unit = new ReservedUnit(1001L, UUID.randomUUID());
            given(inventoryPort.reserve(any())).willReturn(Map.of(100L, List.of(unit)));

            SalesOrderRecord salesOrder = salesOrderRecord(1L, 42L, "ORD-123", idemKey);
            given(orderRepository.insertSalesOrder(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                    .willReturn(salesOrder);

            given(ledgerPort.quoteCommission(any())).willThrow(new IllegalStateException("Ledger communication failure"));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, idemKey, null, null))
                    .isInstanceOf(IllegalStateException.class);

            verify(cartRepository, never()).deleteCart(anyLong());
        }
    }
}
