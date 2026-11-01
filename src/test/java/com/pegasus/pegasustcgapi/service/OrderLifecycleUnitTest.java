package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.dto.ReorderPreviewResponse;
import com.pegasus.pegasustcgapi.dto.ReorderPreviewResponse.ReorderAlternativeConditionResponse;
import com.pegasus.pegasustcgapi.dto.ReorderPreviewResponse.ReorderCurrentListingResponse;
import com.pegasus.pegasustcgapi.dto.ReorderPreviewResponse.ReorderItemResponse;
import com.pegasus.pegasustcgapi.dto.ReorderPreviewResponse.ReorderItemStatus;
import com.pegasus.pegasustcgapi.dto.ReorderPreviewResponse.ReorderSellerOrderResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
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
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    private SellerPort sellerPort;

    @Mock
    private PlatformSettingService platformSettingService;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private LedgerPort ledgerPort;

    @Mock
    private CollectionPort collectionPort;

    @Mock
    private ListingRepository listingRepository;

    @InjectMocks
    private OrderLifecycleService service;

    private final AuthPrincipal buyerPrincipal = new AuthPrincipal(100L, "buyer@example.com", "buyer", Set.of(RoleCode.BUYER));
    private final AuthPrincipal sellerPrincipal = new AuthPrincipal(200L, "seller@example.com", "seller", Set.of(RoleCode.SELLER));
    private final SellerProfile sellerProfile = new SellerProfile(10L, sellerPrincipal.userId(), SellerStatus.VERIFIED, OffsetDateTime.now(), null, (short) 1, false, true, OffsetDateTime.now());

    @BeforeEach
    void setUp() {
        when(platformSettingService.getHours(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS))
                .thenReturn(Duration.ofHours(24));
        when(platformSettingService.getDays(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS))
                .thenReturn(Duration.ofDays(7));
        when(sellerPort.requireProfile(sellerPrincipal.userId())).thenReturn(sellerProfile);
    }

    private SalesOrderRecord mockSalesOrder(long id, String status) {
        SalesOrderRecord so = mock(SalesOrderRecord.class);
        when(so.getId()).thenReturn(id);
        when(so.getBuyerId()).thenReturn(buyerPrincipal.userId());
        when(so.getStatus()).thenReturn(status);
        when(so.getOrderNumber()).thenReturn("PGS-20260920-%06d".formatted(id));
        // Unpaid orders have no cancellation window; paid ones are measured from paid_at.
        when(so.getPaidAt()).thenReturn(
                "PENDING_PAYMENT".equals(status) ? null : OffsetDateTime.now().minusHours(1));
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

        @ParameterizedTest(name = "1 COMPLETED and 1 {0}: nothing left running -> parent status COMPLETED")
        @ValueSource(strings = {"CANCELLED", "REFUNDED"})
        void rollup_CompletedAlongsideOtherTerminalChild_ResultsInCompleted(String siblingStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "COMPLETED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, siblingStatus);

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            // PARTIALLY_COMPLETED is for an order that is still going. Once every
            // sub-order has finished, one of them delivered, the order is done.
            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("COMPLETED"), any(), any(), any());
        }

        @Test
        @DisplayName("Everything terminal but nothing delivered: 1 CANCELLED, 1 REFUNDED -> parent status CANCELLED")
        void rollup_AllTerminalWithoutCompleted_ResultsInCancelled() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "CANCELLED");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "REFUNDED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("CANCELLED"), any(), any(), any());
        }

        @Test
        @DisplayName("Paid but nothing finished: 1 PAID, 1 PENDING_PAYMENT -> parent status PAID")
        void rollup_PartlyPaid_ResultsInPaid() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "PAID");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "PENDING_PAYMENT");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PAID"), any(), any(), any());
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

        @ParameterizedTest(name = "1 PENDING_PAYMENT and 1 {0} before payment -> parent stays PENDING_PAYMENT, no paid_at")
        @ValueSource(strings = {"CANCELLED", "REFUNDED"})
        void rollup_UnpaidWithDroppedSibling_StaysPendingPayment(String droppedStatus) {
            SalesOrderRecord parent = mockSalesOrder(1L, "PENDING_PAYMENT");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "PENDING_PAYMENT");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, droppedStatus);

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2));

            service.rollupSalesOrderStatus(1L);

            // TCG-582: this used to fall through to PAID with paid_at = now, which
            // blocked the buyer from paying and hid the order from the payment timeout.
            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PENDING_PAYMENT"), isNull(), any(), any());
        }

        @Test
        @DisplayName("PENDING_PAYMENT, CANCELLED and REFUNDED children -> parent status PENDING_PAYMENT")
        void rollup_UnpaidWithCancelledAndRefundedSiblings_StaysPendingPayment() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PENDING_PAYMENT");
            SellerOrderRecord so1 = mockSellerOrder(11L, 1L, "PENDING_PAYMENT");
            SellerOrderRecord so2 = mockSellerOrder(12L, 1L, "CANCELLED");
            SellerOrderRecord so3 = mockSellerOrder(13L, 1L, "REFUNDED");

            when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(parent));
            when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(so1, so2, so3));

            service.rollupSalesOrderStatus(1L);

            verify(orderRepository).updateSalesOrderStatus(eq(1L), eq("PENDING_PAYMENT"), isNull(), any(), any());
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
    @DisplayName("Buyer order view — payment review and cancel deadline")
    class BuyerOrderViewTests {

        @Test
        void getBuyerOrder_ExposesSlipUnderReviewAndCancelDeadline() {
            SalesOrderRecord parent = mockSalesOrder(1L, "PAID");
            OffsetDateTime paidAt = parent.getPaidAt();
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));
            when(orderRepository.findSalesOrderIdsWithPaymentUnderReview(List.of(1L))).thenReturn(Set.of(1L));

            OrderDetailsResponse response = service.getBuyerOrder(buyerPrincipal, 1L);

            assertThat(response.paymentUnderReview()).isTrue();
            assertThat(response.cancellableUntil()).isEqualTo(paidAt.plusHours(24));
        }

        @Test
        void getBuyerOrder_UnpaidOrderHasNoCancelDeadline() {
            SalesOrderRecord parent = mockSalesOrder(2L, "PENDING_PAYMENT");
            when(orderRepository.findSalesOrderByIdAndBuyerId(2L, buyerPrincipal.userId())).thenReturn(Optional.of(parent));

            OrderDetailsResponse response = service.getBuyerOrder(buyerPrincipal, 2L);

            assertThat(response.paymentUnderReview()).isFalse();
            assertThat(response.cancellableUntil()).isNull();
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

            when(orderRepository.findUnitIdsGroupedBySellerOrderId(10L)).thenReturn(Map.of(101L, List.of(5001L)));

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

    @Nested
    @DisplayName("Reorder Preview Tests")
    class ReorderPreviewTests {

        @Test
        @DisplayName("Unauthenticated principal throws UNAUTHENTICATED")
        void unauthenticated_ThrowsUnauthorizedException() {
            assertThatThrownBy(() -> service.getReorderPreview(null, 1L))
                    .isInstanceOf(UnauthorizedException.class)
                    .satisfies(e -> assertThat(((UnauthorizedException) e).errorCode())
                            .isEqualTo(ErrorCode.UNAUTHENTICATED));
        }

        @Test
        @DisplayName("Order not found throws ORDER_NOT_FOUND")
        void orderNotFound_ThrowsNotFoundException() {
            when(orderRepository.findSalesOrderByIdAndBuyerId(999L, buyerPrincipal.userId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReorderPreview(buyerPrincipal, 999L))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("Order belonging to another buyer throws ORDER_NOT_FOUND (prevents ID enumeration)")
        void otherBuyerOrder_ThrowsNotFoundException() {
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReorderPreview(buyerPrincipal, 1L))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("Non-completed order throws VALIDATION_FAILED")
        void nonCompletedOrder_ThrowsBadRequestException() {
            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(1L);
            when(order.getBuyerId()).thenReturn(buyerPrincipal.userId());
            when(order.getStatus()).thenReturn("PAID");
            when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.getReorderPreview(buyerPrincipal, 1L))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(e -> assertThat(((BadRequestException) e).errorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("Completed order with available matching listing and alternative conditions")
        void completedOrder_AvailableAndAlternativeConditions() {
            long orderId = 100L;
            long sellerProfileId = 12L;
            long sellerOrderId = 200L;
            long variantId = 55L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getBuyerId()).thenReturn(buyerPrincipal.userId());
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord sellerOrder = mock(SellerOrderRecord.class);
            when(sellerOrder.getId()).thenReturn(sellerOrderId);
            when(sellerOrder.getSalesOrderId()).thenReturn(orderId);
            when(sellerOrder.getSellerProfileId()).thenReturn(sellerProfileId);
            when(sellerOrder.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSellerOrdersBySalesOrderId(orderId)).thenReturn(List.of(sellerOrder));

            OrderItemRecord item = mock(OrderItemRecord.class);
            when(item.getId()).thenReturn(450L);
            when(item.getCatalogVariantId()).thenReturn(variantId);
            when(item.getProductNameSnapshot()).thenReturn("Bio-Broly (SR)");
            when(item.getVariantLabelSnapshot()).thenReturn("JP / FOIL");
            when(item.getConditionSnapshot()).thenReturn("DMG");
            when(item.getUnitPrice()).thenReturn(new BigDecimal("8000.00"));
            when(item.getQuantity()).thenReturn(1);
            when(item.getListingId()).thenReturn(2045L);
            when(orderRepository.findOrderItemsBySellerOrderIds(List.of(sellerOrderId)))
                    .thenReturn(Map.of(sellerOrderId, List.of(item)));

            when(listingRepository.findSellerStandings(List.of(sellerProfileId)))
                    .thenReturn(Map.of(sellerProfileId, new ListingRepository.SellerStanding(
                            sellerProfileId, "Card Kingdom TH", false, true)));

            Listing dmgListing = new Listing(2045L, sellerProfileId, variantId, CardCondition.DMG, null, null,
                    new BigDecimal("8200.00"), "THB", null, null, null, null, null, 5, 0, 3, ListingStatus.ACTIVE, null, null, 1, null, null, null);
            Listing nmListing = new Listing(2048L, sellerProfileId, variantId, CardCondition.NM, null, null,
                    new BigDecimal("9500.00"), "THB", null, null, null, null, null, 5, 0, 2, ListingStatus.ACTIVE, null, null, 1, null, null, null);

            when(listingRepository.findActiveListingsForReorder(List.of(sellerProfileId), List.of(variantId)))
                    .thenReturn(List.of(dmgListing, nmListing));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            assertThat(response.orderId()).isEqualTo(orderId);
            assertThat(response.sellerOrders()).hasSize(1);

            ReorderSellerOrderResponse soResp = response.sellerOrders().getFirst();
            assertThat(soResp.sellerProfileId()).isEqualTo(sellerProfileId);
            assertThat(soResp.sellerName()).isEqualTo("Card Kingdom TH");
            assertThat(soResp.sellerAvailable()).isTrue();
            assertThat(soResp.vacationMode()).isFalse();

            assertThat(soResp.items()).hasSize(1);
            ReorderItemResponse itemResp = soResp.items().getFirst();
            assertThat(itemResp.originalOrderItemId()).isEqualTo(450L);
            assertThat(itemResp.catalogVariantId()).isEqualTo(variantId);
            assertThat(itemResp.originalCondition()).isEqualTo("DMG");
            assertThat(itemResp.originalPrice()).isEqualByComparingTo("8000.00");
            assertThat(itemResp.status()).isEqualTo(ReorderItemStatus.AVAILABLE_PRICE_CHANGED);

            assertThat(itemResp.currentListing()).isNotNull();
            assertThat(itemResp.currentListing().listingId()).isEqualTo(2045L);
            assertThat(itemResp.currentListing().price()).isEqualByComparingTo("8200.00");
            assertThat(itemResp.currentListing().priceChanged()).isTrue();
            assertThat(itemResp.currentListing().isAvailable()).isTrue();
            assertThat(itemResp.currentListing().quantityAvailable()).isEqualTo(3);

            assertThat(itemResp.alternativeConditions()).hasSize(1);
            ReorderAlternativeConditionResponse alt = itemResp.alternativeConditions().getFirst();
            assertThat(alt.listingId()).isEqualTo(2048L);
            assertThat(alt.condition()).isEqualTo("NM");
            assertThat(alt.price()).isEqualByComparingTo("9500.00");
            assertThat(alt.quantityAvailable()).isEqualTo(2);
        }

        @Test
        @DisplayName("Original condition sold out returns ALTERNATIVE_AVAILABLE")
        void originalConditionSoldOut_ReturnsAlternativeAvailable() {
            long orderId = 101L;
            long sellerProfileId = 12L;
            long sellerOrderId = 201L;
            long variantId = 55L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getBuyerId()).thenReturn(buyerPrincipal.userId());
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord sellerOrder = mock(SellerOrderRecord.class);
            when(sellerOrder.getId()).thenReturn(sellerOrderId);
            when(sellerOrder.getSalesOrderId()).thenReturn(orderId);
            when(sellerOrder.getSellerProfileId()).thenReturn(sellerProfileId);
            when(sellerOrder.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSellerOrdersBySalesOrderId(orderId)).thenReturn(List.of(sellerOrder));

            OrderItemRecord item = mock(OrderItemRecord.class);
            when(item.getId()).thenReturn(451L);
            when(item.getCatalogVariantId()).thenReturn(variantId);
            when(item.getConditionSnapshot()).thenReturn("DMG");
            when(item.getUnitPrice()).thenReturn(new BigDecimal("8000.00"));
            when(item.getQuantity()).thenReturn(1);
            when(orderRepository.findOrderItemsBySellerOrderIds(List.of(sellerOrderId)))
                    .thenReturn(Map.of(sellerOrderId, List.of(item)));

            when(listingRepository.findSellerStandings(List.of(sellerProfileId)))
                    .thenReturn(Map.of(sellerProfileId, new ListingRepository.SellerStanding(
                            sellerProfileId, "Card Kingdom TH", false, true)));

            Listing nmListing = new Listing(2048L, sellerProfileId, variantId, CardCondition.NM, null, null,
                    new BigDecimal("9500.00"), "THB", null, null, null, null, null, 5, 0, 2, ListingStatus.ACTIVE, null, null, 1, null, null, null);

            when(listingRepository.findActiveListingsForReorder(List.of(sellerProfileId), List.of(variantId)))
                    .thenReturn(List.of(nmListing));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            ReorderItemResponse itemResp = response.sellerOrders().getFirst().items().getFirst();
            assertThat(itemResp.currentListing()).isNull();
            assertThat(itemResp.alternativeConditions()).hasSize(1);
            assertThat(itemResp.status()).isEqualTo(ReorderItemStatus.ALTERNATIVE_AVAILABLE);
        }

        @Test
        @DisplayName("Seller on vacation returns sellerAvailable=false and OUT_OF_STOCK")
        void sellerOnVacation_ReturnsUnavailableAndOutOfStock() {
            long orderId = 102L;
            long sellerProfileId = 12L;
            long sellerOrderId = 202L;
            long variantId = 55L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getBuyerId()).thenReturn(buyerPrincipal.userId());
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord sellerOrder = mock(SellerOrderRecord.class);
            when(sellerOrder.getId()).thenReturn(sellerOrderId);
            when(sellerOrder.getSalesOrderId()).thenReturn(orderId);
            when(sellerOrder.getSellerProfileId()).thenReturn(sellerProfileId);
            when(sellerOrder.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSellerOrdersBySalesOrderId(orderId)).thenReturn(List.of(sellerOrder));

            OrderItemRecord item = mock(OrderItemRecord.class);
            when(item.getId()).thenReturn(452L);
            when(item.getCatalogVariantId()).thenReturn(variantId);
            when(item.getConditionSnapshot()).thenReturn("NM");
            when(item.getUnitPrice()).thenReturn(new BigDecimal("9500.00"));
            when(item.getQuantity()).thenReturn(1);
            when(orderRepository.findOrderItemsBySellerOrderIds(List.of(sellerOrderId)))
                    .thenReturn(Map.of(sellerOrderId, List.of(item)));

            when(listingRepository.findSellerStandings(List.of(sellerProfileId)))
                    .thenReturn(Map.of(sellerProfileId, new ListingRepository.SellerStanding(
                            sellerProfileId, "Card Kingdom TH", true, false)));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            ReorderSellerOrderResponse soResp = response.sellerOrders().getFirst();
            assertThat(soResp.sellerAvailable()).isFalse();
            assertThat(soResp.vacationMode()).isTrue();

            ReorderItemResponse itemResp = soResp.items().getFirst();
            assertThat(itemResp.currentListing()).isNull();
            assertThat(itemResp.alternativeConditions()).isEmpty();
            assertThat(itemResp.status()).isEqualTo(ReorderItemStatus.OUT_OF_STOCK);
        }

        @Test
        @DisplayName("Cancelled sub-orders are filtered out from reorder preview")
        void cancelledSubOrder_IsFilteredOut() {
            long orderId = 103L;
            long activeSellerId = 12L;
            long cancelledSellerId = 13L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord activeSubOrder = mock(SellerOrderRecord.class);
            when(activeSubOrder.getId()).thenReturn(203L);
            when(activeSubOrder.getSellerProfileId()).thenReturn(activeSellerId);
            when(activeSubOrder.getStatus()).thenReturn("COMPLETED");

            SellerOrderRecord cancelledSubOrder = mock(SellerOrderRecord.class);
            when(cancelledSubOrder.getId()).thenReturn(204L);
            when(cancelledSubOrder.getSellerProfileId()).thenReturn(cancelledSellerId);
            when(cancelledSubOrder.getStatus()).thenReturn("CANCELLED");

            when(orderRepository.findSellerOrdersBySalesOrderId(orderId))
                    .thenReturn(List.of(activeSubOrder, cancelledSubOrder));

            when(listingRepository.findSellerStandings(List.of(activeSellerId)))
                    .thenReturn(Map.of(activeSellerId, new ListingRepository.SellerStanding(
                            activeSellerId, "Active Seller", false, true)));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            assertThat(response.sellerOrders()).hasSize(1);
            assertThat(response.sellerOrders().getFirst().sellerProfileId()).isEqualTo(activeSellerId);
        }

        @Test
        @DisplayName("Graded vs RAW isolation: Raw item does NOT match PSA graded listing")
        void rawItem_DoesNotMatchGradedListing() {
            long orderId = 104L;
            long sellerProfileId = 12L;
            long sellerOrderId = 205L;
            long variantId = 55L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord sellerOrder = mock(SellerOrderRecord.class);
            when(sellerOrder.getId()).thenReturn(sellerOrderId);
            when(sellerOrder.getSellerProfileId()).thenReturn(sellerProfileId);
            when(sellerOrder.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSellerOrdersBySalesOrderId(orderId)).thenReturn(List.of(sellerOrder));

            OrderItemRecord rawItem = mock(OrderItemRecord.class);
            when(rawItem.getId()).thenReturn(455L);
            when(rawItem.getListingId()).thenReturn(2045L);
            when(rawItem.getCatalogVariantId()).thenReturn(variantId);
            when(rawItem.getConditionSnapshot()).thenReturn("NM");
            when(rawItem.getUnitPrice()).thenReturn(new BigDecimal("100.00"));
            when(rawItem.getQuantity()).thenReturn(1);
            when(orderRepository.findOrderItemsBySellerOrderIds(List.of(sellerOrderId)))
                    .thenReturn(Map.of(sellerOrderId, List.of(rawItem)));

            when(listingRepository.findGradingByListingIds(List.of(2045L)))
                    .thenReturn(Map.of(2045L, new ListingRepository.ListingGrading(null, null)));

            when(listingRepository.findSellerStandings(List.of(sellerProfileId)))
                    .thenReturn(Map.of(sellerProfileId, new ListingRepository.SellerStanding(
                            sellerProfileId, "Card Kingdom TH", false, true)));

            Listing psaListing = new Listing(3001L, sellerProfileId, variantId, CardCondition.NM, "PSA", new BigDecimal("10.0"),
                    new BigDecimal("5000.00"), "THB", null, null, null, null, null, 1, 0, 1, ListingStatus.ACTIVE, null, null, 1, null, null, null);

            when(listingRepository.findActiveListingsForReorder(List.of(sellerProfileId), List.of(variantId)))
                    .thenReturn(List.of(psaListing));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            ReorderItemResponse itemResp = response.sellerOrders().getFirst().items().getFirst();
            assertThat(itemResp.currentListing()).isNull();
            assertThat(itemResp.alternativeConditions()).isEmpty();
            assertThat(itemResp.status()).isEqualTo(ReorderItemStatus.OUT_OF_STOCK);
        }

        @Test
        @DisplayName("Selection Priority: Original listing ID is prioritized over cheaper listing")
        void selectionPriority_OriginalListingIdPrioritized() {
            long orderId = 105L;
            long sellerProfileId = 12L;
            long sellerOrderId = 206L;
            long variantId = 55L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord sellerOrder = mock(SellerOrderRecord.class);
            when(sellerOrder.getId()).thenReturn(sellerOrderId);
            when(sellerOrder.getSellerProfileId()).thenReturn(sellerProfileId);
            when(sellerOrder.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSellerOrdersBySalesOrderId(orderId)).thenReturn(List.of(sellerOrder));

            OrderItemRecord item = mock(OrderItemRecord.class);
            when(item.getId()).thenReturn(456L);
            when(item.getListingId()).thenReturn(2045L);
            when(item.getCatalogVariantId()).thenReturn(variantId);
            when(item.getConditionSnapshot()).thenReturn("NM");
            when(item.getUnitPrice()).thenReturn(new BigDecimal("100.00"));
            when(item.getQuantity()).thenReturn(1);
            when(orderRepository.findOrderItemsBySellerOrderIds(List.of(sellerOrderId)))
                    .thenReturn(Map.of(sellerOrderId, List.of(item)));

            when(listingRepository.findGradingByListingIds(List.of(2045L)))
                    .thenReturn(Map.of(2045L, new ListingRepository.ListingGrading(null, null)));

            when(listingRepository.findSellerStandings(List.of(sellerProfileId)))
                    .thenReturn(Map.of(sellerProfileId, new ListingRepository.SellerStanding(
                            sellerProfileId, "Card Kingdom TH", false, true)));

            Listing origListing = new Listing(2045L, sellerProfileId, variantId, CardCondition.NM, null, null,
                    new BigDecimal("120.00"), "THB", null, null, null, null, null, 2, 0, 2, ListingStatus.ACTIVE, null, null, 1, null, null, null);
            Listing cheaperListing = new Listing(2046L, sellerProfileId, variantId, CardCondition.NM, null, null,
                    new BigDecimal("90.00"), "THB", null, null, null, null, null, 1, 0, 1, ListingStatus.ACTIVE, null, null, 1, null, null, null);

            when(listingRepository.findActiveListingsForReorder(List.of(sellerProfileId), List.of(variantId)))
                    .thenReturn(List.of(origListing, cheaperListing));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            ReorderItemResponse itemResp = response.sellerOrders().getFirst().items().getFirst();
            assertThat(itemResp.currentListing()).isNotNull();
            assertThat(itemResp.currentListing().listingId()).isEqualTo(2045L);
            assertThat(itemResp.currentListing().price()).isEqualByComparingTo("120.00");

            assertThat(itemResp.alternativeConditions()).hasSize(1);
            assertThat(itemResp.alternativeConditions().getFirst().listingId()).isEqualTo(2046L);
        }

        @Test
        @DisplayName("Selection Priority: Lowest price chosen when original listing is no longer active")
        void selectionPriority_LowestPriceChosenWhenOriginalNotActive() {
            long orderId = 106L;
            long sellerProfileId = 12L;
            long sellerOrderId = 207L;
            long variantId = 55L;

            SalesOrderRecord order = mock(SalesOrderRecord.class);
            when(order.getId()).thenReturn(orderId);
            when(order.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSalesOrderByIdAndBuyerId(orderId, buyerPrincipal.userId())).thenReturn(Optional.of(order));

            SellerOrderRecord sellerOrder = mock(SellerOrderRecord.class);
            when(sellerOrder.getId()).thenReturn(sellerOrderId);
            when(sellerOrder.getSellerProfileId()).thenReturn(sellerProfileId);
            when(sellerOrder.getStatus()).thenReturn("COMPLETED");
            when(orderRepository.findSellerOrdersBySalesOrderId(orderId)).thenReturn(List.of(sellerOrder));

            OrderItemRecord item = mock(OrderItemRecord.class);
            when(item.getId()).thenReturn(457L);
            when(item.getListingId()).thenReturn(2040L);
            when(item.getCatalogVariantId()).thenReturn(variantId);
            when(item.getConditionSnapshot()).thenReturn("NM");
            when(item.getUnitPrice()).thenReturn(new BigDecimal("100.00"));
            when(item.getQuantity()).thenReturn(1);
            when(orderRepository.findOrderItemsBySellerOrderIds(List.of(sellerOrderId)))
                    .thenReturn(Map.of(sellerOrderId, List.of(item)));

            when(listingRepository.findGradingByListingIds(List.of(2040L)))
                    .thenReturn(Map.of());

            when(listingRepository.findSellerStandings(List.of(sellerProfileId)))
                    .thenReturn(Map.of(sellerProfileId, new ListingRepository.SellerStanding(
                            sellerProfileId, "Card Kingdom TH", false, true)));

            Listing cheaperListing = new Listing(2046L, sellerProfileId, variantId, CardCondition.NM, null, null,
                    new BigDecimal("90.00"), "THB", null, null, null, null, null, 1, 0, 1, ListingStatus.ACTIVE, null, null, 1, null, null, null);
            Listing moreExpensiveListing = new Listing(2047L, sellerProfileId, variantId, CardCondition.NM, null, null,
                    new BigDecimal("110.00"), "THB", null, null, null, null, null, 2, 0, 2, ListingStatus.ACTIVE, null, null, 1, null, null, null);

            when(listingRepository.findActiveListingsForReorder(List.of(sellerProfileId), List.of(variantId)))
                    .thenReturn(List.of(moreExpensiveListing, cheaperListing));

            ReorderPreviewResponse response = service.getReorderPreview(buyerPrincipal, orderId);

            ReorderItemResponse itemResp = response.sellerOrders().getFirst().items().getFirst();
            assertThat(itemResp.currentListing()).isNotNull();
            assertThat(itemResp.currentListing().listingId()).isEqualTo(2046L);
            assertThat(itemResp.currentListing().price()).isEqualByComparingTo("90.00");

            assertThat(itemResp.alternativeConditions()).hasSize(1);
            assertThat(itemResp.alternativeConditions().getFirst().listingId()).isEqualTo(2047L);
        }
    }
}
