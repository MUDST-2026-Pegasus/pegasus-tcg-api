package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.port.CollectionPort;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderLifecycleUnitTest — State Matrix & Aggregate Rollup")
class OrderLifecycleUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @Mock
    private PlatformSettingService platformSettingService;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private LedgerPort ledgerPort;

    @Mock
    private CollectionPort collectionPort;

    @InjectMocks
    private OrderLifecycleService service;

    private final AuthPrincipal buyerPrincipal = new AuthPrincipal(100L, "buyer@example.com", "buyer", Set.of(RoleCode.BUYER));
    private final AuthPrincipal sellerPrincipal = new AuthPrincipal(200L, "seller@example.com", "seller", Set.of(RoleCode.SELLER));
    private final SellerProfile sellerProfile = new SellerProfile(10L, sellerPrincipal.userId(), SellerStatus.VERIFIED, OffsetDateTime.now(), null, (short) 1, false, true, OffsetDateTime.now());

    @BeforeEach
    void setUp() {
        when(platformSettingService.getInt(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS)).thenReturn(24);
        when(platformSettingService.getInt(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS)).thenReturn(7);
        when(sellerProfileRepository.findByUserId(sellerPrincipal.userId())).thenReturn(Optional.of(sellerProfile));
    }

    private SalesOrderRecord mockSalesOrder(long id, String status) {
        SalesOrderRecord so = mock(SalesOrderRecord.class);
        when(so.getId()).thenReturn(id);
        when(so.getBuyerId()).thenReturn(buyerPrincipal.userId());
        when(so.getStatus()).thenReturn(status);
        when(so.getOrderNumber()).thenReturn("ORD-" + id);
        when(so.getCurrency()).thenReturn("THB");
        when(so.getItemsSubtotal()).thenReturn(new BigDecimal("200.00"));
        when(so.getShippingTotal()).thenReturn(new BigDecimal("20.00"));
        when(so.getDiscountTotal()).thenReturn(BigDecimal.ZERO);
        when(so.getGrandTotal()).thenReturn(new BigDecimal("220.00"));
        when(so.getPlacedAt()).thenReturn(OffsetDateTime.now().minusMinutes(30));
        return so;
    }

    private SellerOrderRecord mockSellerOrder(long id, long salesOrderId, String status) {
        SellerOrderRecord so = mock(SellerOrderRecord.class);
        when(so.getId()).thenReturn(id);
        when(so.getSalesOrderId()).thenReturn(salesOrderId);
        when(so.getSellerProfileId()).thenReturn(sellerProfile.id());
        when(so.getStatus()).thenReturn(status);
        when(so.getSellerOrderNumber()).thenReturn("SO-" + id);
        when(so.getItemsSubtotal()).thenReturn(new BigDecimal("200.00"));
        when(so.getShippingFee()).thenReturn(new BigDecimal("20.00"));
        when(so.getDiscountAmount()).thenReturn(BigDecimal.ZERO);
        when(so.getGrandTotal()).thenReturn(new BigDecimal("220.00"));
        when(so.getCommissionAmount()).thenReturn(new BigDecimal("22.00"));
        when(so.getSellerNetAmount()).thenReturn(new BigDecimal("198.00"));
        return so;
    }

    @Nested
    @DisplayName("Parent Sales Order Status Rollup Calculation")
    class ParentStatusRollupTests {

        @Test
        @DisplayName("All children CANCELLED -> parent status CANCELLED")
        void rollup_AllCancelled_ResultsInCancelled() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "CANCELLED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "CANCELLED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("CANCELLED"), any(), any(), any());
        }

        @Test
        @DisplayName("All children COMPLETED -> parent status COMPLETED")
        void rollup_AllCompleted_ResultsInCompleted() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "COMPLETED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "COMPLETED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("COMPLETED"), any(), any(), any());
        }

        @Test
        @DisplayName("Mixed completion: 1 COMPLETED, 1 SHIPPED -> parent status PARTIALLY_COMPLETED")
        void rollup_MixedCompletedAndShipped_ResultsInPartiallyCompleted() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "COMPLETED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "SHIPPED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PARTIALLY_COMPLETED"), any(), any(), any());
        }

        @Test
        @DisplayName("Mixed completion: 1 COMPLETED, 1 CANCELLED -> parent status PARTIALLY_COMPLETED")
        void rollup_MixedCompletedAndCancelled_ResultsInPartiallyCompleted() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "COMPLETED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "CANCELLED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PARTIALLY_COMPLETED"), any(), any(), any());
        }

        @Test
        @DisplayName("All children REFUNDED -> parent status REFUNDED")
        void rollup_AllRefunded_ResultsInRefunded() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "REFUNDED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "REFUNDED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("REFUNDED"), any(), any(), any());
        }

        @Test
        @DisplayName("All children PENDING_PAYMENT -> parent status PENDING_PAYMENT")
        void rollup_AllPendingPayment_ResultsInPendingPayment() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PENDING_PAYMENT");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "PENDING_PAYMENT");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "PENDING_PAYMENT");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PENDING_PAYMENT"), any(), any(), any());
        }

        @ParameterizedTest(name = "Active child {0} with CANCELLED sibling -> parent status PAID")
        @ValueSource(strings = {"PAID", "PREPARING", "SHIPPED", "DELIVERED"})
        void rollup_AnyActiveChild_ResultsInPaid(String activeStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, activeStatus);
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "CANCELLED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PAID"), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Seller Order State Matrix — Ship Transitions")
    class ShipStateMatrixTests {

        @ParameterizedTest(name = "Valid ship from {0}")
        @ValueSource(strings = {"PAID", "PREPARING"})
        void ship_AllowedStatuses_Succeeds(String initialStatus) {
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);
            when(orderRepository.findSellerOrderByIdAndSellerProfileId(10L, sellerProfile.id())).thenReturn(Optional.of(so));
            when(orderRepository.updateSellerOrderStatus(eq(10L), eq(List.of("PAID", "PREPARING")), eq("SHIPPED"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(1);

            OrderItemRecord item = mock(OrderItemRecord.class);
            when(item.getId()).thenReturn(101L);
            when(orderRepository.findOrderItemsBySellerOrderId(10L)).thenReturn(List.of(item));
            when(orderRepository.findUnitIdsByOrderItemId(101L)).thenReturn(List.of(5001L));

            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so));
            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));

            ShipOrderRequest shipRequest = new ShipOrderRequest("Kerry", "TH12345", "KERRY", "img/proof.jpg", LocalDate.now().plusDays(3));
            SellerOrderDetailsResponse response = service.shipSellerOrder(sellerPrincipal, 10L, shipRequest);

            assertThat(response).isNotNull();
            verify(inventoryPort).commitSale(101L, List.of(5001L), sellerPrincipal.userId());
            verify(orderRepository).insertShipment(eq(10L), eq("KERRY"), eq("Kerry"), eq("TH12345"), eq("IN_TRANSIT"), any(), any(), eq("img/proof.jpg"), eq(sellerPrincipal.userId()));
        }

        @ParameterizedTest(name = "Invalid ship from {0} throws ConflictException")
        @ValueSource(strings = {"PENDING_PAYMENT", "SHIPPED", "DELIVERED", "COMPLETED", "CANCELLED", "REFUNDED"})
        void ship_DisallowedStatuses_ThrowsConflictException(String initialStatus) {
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);
            when(orderRepository.findSellerOrderByIdAndSellerProfileId(10L, sellerProfile.id())).thenReturn(Optional.of(so));

            ShipOrderRequest shipRequest = new ShipOrderRequest("Kerry", "TH12345", "KERRY", "img/proof.jpg", LocalDate.now().plusDays(3));

            assertThatThrownBy(() -> service.shipSellerOrder(sellerPrincipal, 10L, shipRequest))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION));

            verify(inventoryPort, never()).commitSale(anyLong(), anyCollection(), any());
            verify(orderRepository, never()).insertShipment(anyLong(), any(), any(), any(), any(), any(), any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("Seller Order State Matrix — Buyer Confirmation Transitions")
    class ConfirmReceiptStateMatrixTests {

        @ParameterizedTest(name = "Valid receipt confirmation from {0}")
        @ValueSource(strings = {"SHIPPED", "DELIVERED"})
        void confirm_AllowedStatuses_Succeeds(String initialStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);

            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));
            when(orderRepository.updateSellerOrderStatus(eq(10L), eq(List.of("SHIPPED", "DELIVERED")), eq("COMPLETED"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so));
            when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(parent));

            OrderDetailsResponse response = service.confirmBuyerOrderReceived(buyerPrincipal, 10L);

            assertThat(response).isNotNull();
            verify(ledgerPort).release(10L);
            verify(collectionPort).grant(buyerPrincipal.userId(), 10L);
        }

        @ParameterizedTest(name = "Invalid receipt confirmation from {0} throws ConflictException")
        @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "PREPARING", "COMPLETED", "CANCELLED", "REFUNDED"})
        void confirm_DisallowedStatuses_ThrowsConflictException(String initialStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);

            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.confirmBuyerOrderReceived(buyerPrincipal, 10L))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION));

            verify(ledgerPort, never()).release(anyLong());
            verify(collectionPort, never()).grant(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("Seller Order State Matrix — Buyer Cancellation Transitions")
    class CancelStateMatrixTests {

        @ParameterizedTest(name = "Valid cancellation from {0}")
        @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "PREPARING"})
        void cancelSingle_AllowedStatuses_Succeeds(String initialStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);

            when(orderRepository.findSalesOrderByIdAndBuyerId(10L, buyerPrincipal.userId())).thenReturn(Optional.empty());
            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));
            when(orderRepository.updateSellerOrderStatus(eq(10L), eq(List.of("PENDING_PAYMENT", "PAID", "PREPARING")), eq("CANCELLED"), any(), any(), any(), any(), any(), anyString()))
                    .thenReturn(1);
            when(orderRepository.findUnitIdsBySellerOrderId(10L)).thenReturn(List.of(701L));
            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so));
            when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(parent));

            OrderDetailsResponse response = service.cancelBuyerOrder(buyerPrincipal, 10L, new CancelOrderRequest("Reason"));

            assertThat(response).isNotNull();
            verify(inventoryPort).release(List.of(701L));
        }

        @ParameterizedTest(name = "Invalid cancellation from {0} throws ConflictException")
        @ValueSource(strings = {"SHIPPED", "DELIVERED", "COMPLETED", "CANCELLED", "REFUNDED"})
        void cancelSingle_DisallowedStatuses_ThrowsConflictException(String initialStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);

            when(orderRepository.findSalesOrderByIdAndBuyerId(10L, buyerPrincipal.userId())).thenReturn(Optional.empty());
            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.cancelBuyerOrder(buyerPrincipal, 10L, new CancelOrderRequest("Reason")))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION));

            verify(inventoryPort, never()).release(anyCollection());
        }

        @ParameterizedTest(name = "Sales order cancel blocked if any child is {0}")
        @ValueSource(strings = {"SHIPPED", "DELIVERED", "COMPLETED"})
        void cancelSalesOrder_BlockedIfAnyChildShippedOrBeyond(String childStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(10L, 1L, "PAID");
            SellerOrderRecord so2 = mockSellerOrder(11L, 1L, childStatus);

            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            assertThatThrownBy(() -> service.cancelBuyerOrder(buyerPrincipal, 1L, new CancelOrderRequest("Reason")))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION));

            verify(inventoryPort, never()).release(anyCollection());
        }
    }

    @Nested
    @DisplayName("Seller Order State Matrix — Payment Transitions")
    class PaymentStateMatrixTests {

        @Test
        @DisplayName("Valid payment transition from PENDING_PAYMENT to PAID")
        void pay_PendingPayment_Succeeds() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PENDING_PAYMENT");
            SellerOrderRecord so = mockSellerOrder(10L, 1L, "PENDING_PAYMENT");

            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so));
            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(parent));

            OrderDetailsResponse response = service.markOrderPaid(buyerPrincipal, 1L);

            assertThat(response).isNotNull();
            verify(orderRepository).updateSellerOrderStatus(eq(10L), eq(List.of("PENDING_PAYMENT")), eq("PAID"), any(), any(), any(), any(), any(), any());
        }

        @ParameterizedTest(name = "Invalid payment transition when parent is {0} throws ConflictException")
        @ValueSource(strings = {"PAID", "PREPARING", "SHIPPED", "DELIVERED", "COMPLETED", "CANCELLED", "REFUNDED"})
        void pay_DisallowedParentStatuses_ThrowsConflictException(String initialStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, initialStatus);
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.markOrderPaid(buyerPrincipal, 1L))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.ORDER_STATUS_TRANSITION));

            verify(orderRepository, never()).updateSellerOrderStatus(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Seller Order State Matrix — Auto-Release Transitions")
    class AutoReleaseStateMatrixTests {

        @ParameterizedTest(name = "Valid auto-release from {0}")
        @ValueSource(strings = {"SHIPPED", "DELIVERED"})
        void autoRelease_AllowedStatuses_Succeeds(String initialStatus) {
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");

            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));
            when(orderRepository.updateSellerOrderStatus(eq(10L), eq(List.of("SHIPPED", "DELIVERED")), eq("COMPLETED"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so));

            service.autoCompleteSellerOrder(10L);

            verify(ledgerPort).release(10L);
            verify(collectionPort).grant(parent.getBuyerId(), 10L);
        }

        @ParameterizedTest(name = "Disallowed auto-release from {0} ignored without release or update")
        @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "PREPARING", "COMPLETED", "CANCELLED", "REFUNDED"})
        void autoRelease_DisallowedStatuses_Ignored(String initialStatus) {
            SellerOrderRecord so = mockSellerOrder(10L, 1L, initialStatus);
            when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(so));

            service.autoCompleteSellerOrder(10L);

            verify(orderRepository, never()).updateSellerOrderStatus(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any());
            verify(ledgerPort, never()).release(anyLong());
            verify(collectionPort, never()).grant(anyLong(), anyLong());
        }
    }
}
