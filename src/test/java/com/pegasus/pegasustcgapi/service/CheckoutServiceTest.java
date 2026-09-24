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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.CartPriceChangedException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import org.springframework.dao.DuplicateKeyException;
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
import com.pegasus.pegasustcgapi.port.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.PricingPort;
import com.pegasus.pegasustcgapi.port.PricingPort.ListingOffer;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
    private AddressService addressService;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @Mock
    private PricingPort pricingPort;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private LedgerPort ledgerPort;

    private CheckoutService checkoutService;

    @BeforeEach
    void buildService() {
        // The number comes from a sequence in the database, so it is stubbed rather
        // than generated here.
        org.mockito.Mockito.lenient().when(orderRepository.nextOrderNumber())
                .thenReturn("PGS-20260920-000123");
        // Built by hand rather than with @InjectMocks: the production constructor
        // insists on a real transaction manager, and the JSON writer and clock are
        // collaborators the tests want to pin down rather than leave null.
        checkoutService = new CheckoutService(
                cartRepository,
                cartService,
                orderRepository,
                addressService,
                sellerProfileRepository,
                null,
                pricingPort,
                inventoryPort,
                ledgerPort,
                new ObjectMapper(),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    /** The platform default from platform_setting, as a percent. */
    private static final BigDecimal RATE = new BigDecimal("5.0");

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

        @Test
        @DisplayName("recovers from DuplicateKeyException race condition when winner belongs to same buyer")
        void recoversFromDuplicateKeyRaceConditionSameBuyer() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-race-1";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);
            ReservedUnit unit = new ReservedUnit(1001L, UUID.randomUUID());
            SalesOrderRecord winnerOrder = salesOrderRecord(999L, 42L, "ORD-WINNER", idemKey);

            // Fast path misses because both requests raced concurrently
            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey))
                    .willReturn(Optional.empty())
                    .willReturn(Optional.of(winnerOrder));

            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer1));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());
            given(inventoryPort.reserve(any())).willReturn(Map.of(100L, List.of(unit)));
            given(ledgerPort.quoteCommission(anyLong(), any()))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), BigDecimal.TEN));

            // Insert throws DuplicateKeyException due to concurrent duplicate key
            given(orderRepository.insertSalesOrder(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), eq(idemKey)))
                    .willThrow(new DuplicateKeyException("duplicate key value"));

            given(orderRepository.findSalesOrderById(999L)).willReturn(Optional.of(winnerOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(999L)).willReturn(List.of());

            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, null);

            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(999L);
            assertThat(response.orderNumber()).isEqualTo("ORD-WINNER");
            assertThat(response.replayed()).isTrue();
        }

        @Test
        @DisplayName("throws IDEMPOTENCY_KEY_CONFLICT when DuplicateKeyException occurs and winner belongs to another buyer")
        void duplicateKeyRaceConditionDifferentBuyerThrowsConflict() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-race-conflict";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);
            ReservedUnit unit = new ReservedUnit(1001L, UUID.randomUUID());
            SalesOrderRecord otherBuyerWinner = salesOrderRecord(999L, 888L, "ORD-OTHER", idemKey);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey))
                    .willReturn(Optional.empty())
                    .willReturn(Optional.of(otherBuyerWinner));

            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer1));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());
            given(inventoryPort.reserve(any())).willReturn(Map.of(100L, List.of(unit)));
            given(ledgerPort.quoteCommission(anyLong(), any()))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), BigDecimal.TEN));

            given(orderRepository.insertSalesOrder(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), eq(idemKey)))
                    .willThrow(new DuplicateKeyException("duplicate key value"));

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
            String guestKey = "11111111-2222-4333-8444-555555555555";
            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", guestKey, null))
                    .isInstanceOf(ConflictException.class);

            verify(cartService).mergeGuestCartIfPresent(buyer, guestKey);
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
        @DisplayName("throws CART_PRICE_CHANGED naming every line that moved, and re-pins the cart to the new prices")
        void priceMismatchThrows() {
            AuthPrincipal buyer = principal(42L);
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem cheaper = new CartItem(10L, 5L, 100L, 2, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            CartItem unchanged = new CartItem(11L, 5L, 101L, 1, new BigDecimal("80.00"), OffsetDateTime.now(), OffsetDateTime.now());

            given(orderRepository.findSalesOrderByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(cheaper, unchanged));
            given(pricingPort.offers(List.of(100L, 101L))).willReturn(Map.of(
                    100L, offer(100L, 88L, new BigDecimal("120.00"), true),
                    101L, offer(101L, 88L, new BigDecimal("80.00"), true)));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> checkoutService.checkout(buyer, "idem-1", null, null))
                    .isInstanceOfSatisfying(CartPriceChangedException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_PRICE_CHANGED);
                        assertThat(e.changes()).singleElement().satisfies(change -> {
                            assertThat(change.listingId()).isEqualTo(100L);
                            assertThat(change.oldPrice()).isEqualByComparingTo("100.00");
                            assertThat(change.newPrice()).isEqualByComparingTo("120.00");
                        });
                    });

            // Written outside the rolled-back checkout transaction, or the buyer would
            // meet the same refusal on every retry.
            verify(cartRepository).updateItemPrice(10L, 5L, new BigDecimal("120.00"));
            verify(cartRepository, never()).updateItemPrice(eq(11L), anyLong(), any());
            verify(inventoryPort, never()).reserve(any());
        }

        @Test
        @DisplayName("rolls back when the reservation hands back fewer cards than the line needs")
        void unitCountMustMatchLineQuantity() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-unit-count";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 2, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer(100L, 77L, new BigDecimal("100.00"), true)));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            // Two cards were asked for and only one came back.
            given(inventoryPort.reserve(any()))
                    .willReturn(Map.of(100L, List.of(new ReservedUnit(1001L, UUID.randomUUID()))));
            given(ledgerPort.quoteCommission(anyLong(), any()))
                    .willReturn(new CommissionQuote(RATE, BigDecimal.TEN));
            given(orderRepository.findListingDetails(List.of(100L)))
                    .willReturn(Map.of(100L, new ListingSnapshotDetails(100L, 1L, "NM", "Charizard", "EN / Foil", "Pokemon", "img1.jpg")));
            given(orderRepository.insertSalesOrder(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), eq(idemKey)))
                    .willReturn(salesOrderRecord(1L, 42L, "PGS-20260920-000123", idemKey));
            given(orderRepository.insertSellerOrder(anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(sellerOrderRecord(10L, 1L, 77L, "PGS-20260920-000123-S77"));
            given(orderRepository.insertOrderItem(anyLong(), anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(orderItemRecord(1000L, 10L, 100L, 2, new BigDecimal("100.00")));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, idemKey, null, null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

            verify(orderRepository, never()).insertOrderItemUnits(anyLong(), any());
            verify(cartRepository, never()).deleteItemsByCartId(anyLong());
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

            // The seller's average cost, frozen into order_item.unit_cost_snapshot
            given(inventoryPort.averageUnitCost(77L, 1L, CardCondition.NM)).willReturn(new BigDecimal("90.0000"));
            given(inventoryPort.averageUnitCost(88L, 2L, CardCondition.NM)).willReturn(new BigDecimal("40.0000"));

            // Commission calculation
            given(ledgerPort.quoteCommission(anyLong(), eq(new BigDecimal("300.00"))))
                    .willReturn(new CommissionQuote(RATE, new BigDecimal("30.00")));
            given(ledgerPort.quoteCommission(anyLong(), eq(new BigDecimal("80.00"))))
                    .willReturn(new CommissionQuote(RATE, new BigDecimal("8.00")));

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
                    eq(new BigDecimal("300.00")), eq(RATE), eq(new BigDecimal("30.00")), eq(new BigDecimal("270.00")), eq(null)))
                    .willReturn(sellerOrder1);

            given(orderRepository.insertSellerOrder(eq(1L), eq(88L), anyString(),
                    eq(new BigDecimal("80.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("80.00")), eq(RATE), eq(new BigDecimal("8.00")), eq(new BigDecimal("72.00")), eq(null)))
                    .willReturn(sellerOrder2);

            given(orderRepository.insertOrderItem(eq(10L), eq(100L), eq(1L), eq(2),
                    eq(new BigDecimal("150.00")), eq(new BigDecimal("300.00")), eq(new BigDecimal("90.0000")),
                    eq("Charizard"), eq("EN / Foil"), eq("NM"), eq("Pokemon"), eq("img1.jpg")))
                    .willReturn(orderItem1);

            given(orderRepository.insertOrderItem(eq(11L), eq(101L), eq(2L), eq(1),
                    eq(new BigDecimal("80.00")), eq(new BigDecimal("80.00")), eq(new BigDecimal("40.0000")),
                    eq("Pikachu"), eq("JP / Normal"), eq("LP"), eq("Pokemon"), eq("img2.jpg")))
                    .willReturn(orderItem2);

            // Fetch order details query mocks
            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(salesOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of(sellerOrder1, sellerOrder2));
            given(orderRepository.findOrderItemsBySellerOrderIds(List.of(10L, 11L)))
                    .willReturn(Map.of(10L, List.of(orderItem1), 11L, List.of(orderItem2)));
            given(orderRepository.findUnitIdsByOrderItemIds(List.of(1000L, 1001L)))
                    .willReturn(Map.of(1000L, List.of(1001L, 1002L), 1001L, List.of(1003L)));

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
            verify(cartRepository).deleteItemsByCartId(5L);
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
            given(addressService.get(42L, 99L)).willReturn(address);

            ReservedUnit unit = new ReservedUnit(1001L, UUID.randomUUID());
            given(inventoryPort.reserve(any())).willReturn(Map.of(100L, List.of(unit)));
            given(ledgerPort.quoteCommission(anyLong(), any()))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), BigDecimal.TEN));
            given(orderRepository.findListingDetails(List.of(100L)))
                    .willReturn(Map.of(100L, new ListingSnapshotDetails(100L, 1L, "NM", "Charizard", "EN / Foil", "Pokemon", "img1.jpg")));

            SalesOrderRecord salesOrder = salesOrderRecord(1L, 42L, "ORD-123", idemKey);
            SellerOrderRecord sellerOrder = sellerOrderRecord(10L, 1L, 77L, "ORD-123-S77");
            OrderItemRecord orderItem = orderItemRecord(1000L, 10L, 100L, 1, new BigDecimal("100.00"));

            given(orderRepository.insertSalesOrder(anyString(), eq(42L), anyString(), any(), any(), any(), any(), eq(99L), any(), any(), eq(idemKey)))
                    .willReturn(salesOrder);
            given(orderRepository.insertSellerOrder(anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(sellerOrder);
            given(orderRepository.insertOrderItem(anyLong(), anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(orderItem);

            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(salesOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of(sellerOrder));
            given(orderRepository.findOrderItemsBySellerOrderIds(List.of(10L)))
                    .willReturn(Map.of(10L, List.of(orderItem)));
            given(orderRepository.findUnitIdsByOrderItemIds(List.of(1000L)))
                    .willReturn(Map.of(1000L, List.of(1001L)));

            CheckoutRequest req = new CheckoutRequest(99L, "Please handle with care");
            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, req);

            assertThat(response).isNotNull();
            verify(addressService).get(42L, 99L);
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
            verify(cartRepository, never()).deleteItemsByCartId(anyLong());
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

            given(ledgerPort.quoteCommission(anyLong(), any())).willThrow(new IllegalStateException("Ledger communication failure"));

            assertThatThrownBy(() -> checkoutService.checkout(buyer, idemKey, null, null))
                    .isInstanceOf(IllegalStateException.class);

            verify(orderRepository, never()).insertSalesOrder(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), anyString());
            verify(cartRepository, never()).deleteItemsByCartId(anyLong());
        }

        @Test
        @DisplayName("multi-seller cart with 3 sellers generates 1 sales order and 3 distinct seller orders")
        void multiSellerOrderSplitting3Sellers() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-3-sellers";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item1 = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            CartItem item2 = new CartItem(11L, 5L, 101L, 1, new BigDecimal("200.00"), OffsetDateTime.now(), OffsetDateTime.now());
            CartItem item3 = new CartItem(12L, 5L, 102L, 1, new BigDecimal("300.00"), OffsetDateTime.now(), OffsetDateTime.now());

            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);
            ListingOffer offer2 = offer(101L, 88L, new BigDecimal("200.00"), true);
            ListingOffer offer3 = offer(102L, 99L, new BigDecimal("300.00"), true);

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item1, item2, item3));
            given(pricingPort.offers(List.of(100L, 101L, 102L))).willReturn(Map.of(100L, offer1, 101L, offer2, 102L, offer3));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());

            ReservedUnit unit1 = new ReservedUnit(1001L, UUID.randomUUID());
            ReservedUnit unit2 = new ReservedUnit(1002L, UUID.randomUUID());
            ReservedUnit unit3 = new ReservedUnit(1003L, UUID.randomUUID());
            given(inventoryPort.reserve(Map.of(100L, 1, 101L, 1, 102L, 1)))
                    .willReturn(Map.of(100L, List.of(unit1), 101L, List.of(unit2), 102L, List.of(unit3)));

            ListingSnapshotDetails d1 = new ListingSnapshotDetails(100L, 1L, "NM", "Card A", "EN", "Pokemon", null);
            ListingSnapshotDetails d2 = new ListingSnapshotDetails(101L, 2L, "NM", "Card B", "EN", "Pokemon", null);
            ListingSnapshotDetails d3 = new ListingSnapshotDetails(102L, 3L, "NM", "Card C", "EN", "Pokemon", null);
            given(orderRepository.findListingDetails(List.of(100L, 101L, 102L))).willReturn(Map.of(100L, d1, 101L, d2, 102L, d3));

            given(ledgerPort.quoteCommission(anyLong(), eq(new BigDecimal("100.00"))))
                    .willReturn(new CommissionQuote(RATE, new BigDecimal("10.00")));
            given(ledgerPort.quoteCommission(anyLong(), eq(new BigDecimal("200.00"))))
                    .willReturn(new CommissionQuote(RATE, new BigDecimal("20.00")));
            given(ledgerPort.quoteCommission(anyLong(), eq(new BigDecimal("300.00"))))
                    .willReturn(new CommissionQuote(RATE, new BigDecimal("30.00")));

            SalesOrderRecord salesOrder = salesOrderRecord(1L, 42L, "ORD-3S", idemKey);
            salesOrder.setGrandTotal(new BigDecimal("600.00"));
            SellerOrderRecord so1 = sellerOrderRecord(10L, 1L, 77L, "ORD-3S-S77");
            SellerOrderRecord so2 = sellerOrderRecord(11L, 1L, 88L, "ORD-3S-S88");
            SellerOrderRecord so3 = sellerOrderRecord(12L, 1L, 99L, "ORD-3S-S99");

            OrderItemRecord oi1 = orderItemRecord(1000L, 10L, 100L, 1, new BigDecimal("100.00"));
            OrderItemRecord oi2 = orderItemRecord(1001L, 11L, 101L, 1, new BigDecimal("200.00"));
            OrderItemRecord oi3 = orderItemRecord(1002L, 12L, 102L, 1, new BigDecimal("300.00"));

            given(orderRepository.insertSalesOrder(anyString(), eq(42L), eq("THB"),
                    eq(new BigDecimal("600.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("600.00")), eq(null), any(), eq(null), eq(idemKey)))
                    .willReturn(salesOrder);

            given(orderRepository.insertSellerOrder(eq(1L), eq(77L), anyString(),
                    eq(new BigDecimal("100.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("100.00")), eq(RATE), eq(new BigDecimal("10.00")), eq(new BigDecimal("90.00")), eq(null)))
                    .willReturn(so1);
            given(orderRepository.insertSellerOrder(eq(1L), eq(88L), anyString(),
                    eq(new BigDecimal("200.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("200.00")), eq(RATE), eq(new BigDecimal("20.00")), eq(new BigDecimal("180.00")), eq(null)))
                    .willReturn(so2);
            given(orderRepository.insertSellerOrder(eq(1L), eq(99L), anyString(),
                    eq(new BigDecimal("300.00")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                    eq(new BigDecimal("300.00")), eq(RATE), eq(new BigDecimal("30.00")), eq(new BigDecimal("270.00")), eq(null)))
                    .willReturn(so3);

            given(orderRepository.insertOrderItem(eq(10L), eq(100L), eq(1L), eq(1), eq(new BigDecimal("100.00")), eq(new BigDecimal("100.00")), any(), anyString(), anyString(), anyString(), any(), any()))
                    .willReturn(oi1);
            given(orderRepository.insertOrderItem(eq(11L), eq(101L), eq(2L), eq(1), eq(new BigDecimal("200.00")), eq(new BigDecimal("200.00")), any(), anyString(), anyString(), anyString(), any(), any()))
                    .willReturn(oi2);
            given(orderRepository.insertOrderItem(eq(12L), eq(102L), eq(3L), eq(1), eq(new BigDecimal("300.00")), eq(new BigDecimal("300.00")), any(), anyString(), anyString(), anyString(), any(), any()))
                    .willReturn(oi3);

            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(salesOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of(so1, so2, so3));
            given(orderRepository.findOrderItemsBySellerOrderIds(List.of(10L, 11L, 12L)))
                    .willReturn(Map.of(10L, List.of(oi1), 11L, List.of(oi2), 12L, List.of(oi3)));
            given(orderRepository.findUnitIdsByOrderItemIds(List.of(1000L, 1001L, 1002L)))
                    .willReturn(Map.of(1000L, List.of(1001L), 1001L, List.of(1002L), 1002L, List.of(1003L)));

            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, null);

            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(1L);
            assertThat(response.sellerOrders()).hasSize(3);
            verify(orderRepository).insertSellerOrder(eq(1L), eq(77L), anyString(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(orderRepository).insertSellerOrder(eq(1L), eq(88L), anyString(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(orderRepository).insertSellerOrder(eq(1L), eq(99L), anyString(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(cartRepository).deleteItemsByCartId(5L);
        }

        @Test
        @DisplayName("falls back to default shipping address when not explicitly specified in request")
        void fallbackToDefaultAddressWhenNotSpecified() {
            AuthPrincipal buyer = principal(42L);
            String idemKey = "idem-addr-fallback";
            Cart cart = new Cart(5L, 42L, null, "THB", OffsetDateTime.now(), OffsetDateTime.now(), null);
            CartItem item = new CartItem(10L, 5L, 100L, 1, new BigDecimal("100.00"), OffsetDateTime.now(), OffsetDateTime.now());
            ListingOffer offer1 = offer(100L, 77L, new BigDecimal("100.00"), true);
            Address defaultAddr = new Address(55L, 42L, "Home", "Default Recipient", "0812345678", "Line 1", null,
                    "Subdistrict", "District", "Bangkok", "10110", "TH", true, false, OffsetDateTime.now());

            given(orderRepository.findSalesOrderByIdempotencyKey(idemKey)).willReturn(Optional.empty());
            given(cartRepository.findByUserId(42L)).willReturn(Optional.of(cart));
            given(cartRepository.findItemsByCartId(5L)).willReturn(List.of(item));
            given(pricingPort.offers(List.of(100L))).willReturn(Map.of(100L, offer1));
            given(sellerProfileRepository.findByUserId(42L)).willReturn(Optional.empty());
            given(addressService.list(42L)).willReturn(List.of(defaultAddr));

            ReservedUnit unit = new ReservedUnit(1001L, UUID.randomUUID());
            given(inventoryPort.reserve(any())).willReturn(Map.of(100L, List.of(unit)));
            given(ledgerPort.quoteCommission(anyLong(), any()))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), BigDecimal.TEN));
            given(orderRepository.findListingDetails(List.of(100L)))
                    .willReturn(Map.of(100L, new ListingSnapshotDetails(100L, 1L, "NM", "Charizard", "EN / Foil", "Pokemon", "img1.jpg")));

            SalesOrderRecord salesOrder = salesOrderRecord(1L, 42L, "ORD-DEF-ADDR", idemKey);
            SellerOrderRecord sellerOrder = sellerOrderRecord(10L, 1L, 77L, "ORD-DEF-ADDR-S77");
            OrderItemRecord orderItem = orderItemRecord(1000L, 10L, 100L, 1, new BigDecimal("100.00"));

            given(orderRepository.insertSalesOrder(anyString(), eq(42L), anyString(), any(), any(), any(), any(), eq(55L), any(), any(), eq(idemKey)))
                    .willReturn(salesOrder);
            given(orderRepository.insertSellerOrder(anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(sellerOrder);
            given(orderRepository.insertOrderItem(anyLong(), anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(orderItem);

            given(orderRepository.findSalesOrderById(1L)).willReturn(Optional.of(salesOrder));
            given(orderRepository.findSellerOrdersBySalesOrderId(1L)).willReturn(List.of(sellerOrder));
            given(orderRepository.findOrderItemsBySellerOrderIds(List.of(10L)))
                    .willReturn(Map.of(10L, List.of(orderItem)));
            given(orderRepository.findUnitIdsByOrderItemIds(List.of(1000L)))
                    .willReturn(Map.of(1000L, List.of(1001L)));

            CheckoutResponse response = checkoutService.checkout(buyer, idemKey, null, null);

            assertThat(response).isNotNull();
            verify(orderRepository).insertSalesOrder(anyString(), eq(42L), anyString(), any(), any(), any(), any(), eq(55L), any(), any(), eq(idemKey));
        }
    }
}
