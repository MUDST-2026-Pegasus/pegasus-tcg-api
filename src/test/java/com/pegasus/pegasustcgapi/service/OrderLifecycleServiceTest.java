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
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pegasus.pegasustcgapi.model.RoleCode;
import java.util.Set;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderLifecycleService")
class OrderLifecycleServiceTest {

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

    private final AuthPrincipal buyerPrincipal = new AuthPrincipal(42L, "buyer@example.com", "buyer", Set.of(RoleCode.BUYER));
    private final AuthPrincipal sellerPrincipal = new AuthPrincipal(99L, "seller@example.com", "seller", Set.of(RoleCode.SELLER));

    @BeforeEach
    void setUp() {
    }

    private SalesOrderRecord mockSalesOrder(long id, long buyerId, String status, OffsetDateTime placedAt) {
        SalesOrderRecord so = mock(SalesOrderRecord.class);
        when(so.getId()).thenReturn(id);
        when(so.getBuyerId()).thenReturn(buyerId);
        when(so.getStatus()).thenReturn(status);
        when(so.getOrderNumber()).thenReturn("ORD-" + id);
        when(so.getCurrency()).thenReturn("THB");
        when(so.getItemsSubtotal()).thenReturn(new BigDecimal("100.00"));
        when(so.getShippingTotal()).thenReturn(new BigDecimal("10.00"));
        when(so.getDiscountTotal()).thenReturn(BigDecimal.ZERO);
        when(so.getGrandTotal()).thenReturn(new BigDecimal("110.00"));
        when(so.getPlacedAt()).thenReturn(placedAt);
        return so;
    }

    private SellerOrderRecord mockSellerOrder(long id, long salesOrderId, long sellerProfileId, String status) {
        SellerOrderRecord so = mock(SellerOrderRecord.class);
        when(so.getId()).thenReturn(id);
        when(so.getSalesOrderId()).thenReturn(salesOrderId);
        when(so.getSellerProfileId()).thenReturn(sellerProfileId);
        when(so.getStatus()).thenReturn(status);
        when(so.getSellerOrderNumber()).thenReturn("ORD-" + salesOrderId + "-S" + sellerProfileId);
        when(so.getItemsSubtotal()).thenReturn(new BigDecimal("100.00"));
        when(so.getShippingFee()).thenReturn(new BigDecimal("10.00"));
        when(so.getGrandTotal()).thenReturn(new BigDecimal("110.00"));
        when(so.getCommissionAmount()).thenReturn(new BigDecimal("11.00"));
        when(so.getSellerNetAmount()).thenReturn(new BigDecimal("99.00"));
        return so;
    }

    @Test
    @DisplayName("getBuyerOrder throws NotFoundException if order does not belong to buyer")
    void getBuyerOrder_NotFoundForOtherBuyer() {
        when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBuyerOrder(buyerPrincipal, 1L))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> assertThat(((NotFoundException) ex).errorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("cancelBuyerOrder cancels open sales order and releases held inventory")
    void cancelBuyerOrder_Success() {
        SalesOrderRecord salesOrder = mockSalesOrder(1L, buyerPrincipal.userId(), "PENDING_PAYMENT", OffsetDateTime.now().minusMinutes(10));
        SellerOrderRecord sellerOrder = mockSellerOrder(10L, 1L, 77L, "PENDING_PAYMENT");

        when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(salesOrder));
        when(platformSettingService.getInt(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS)).thenReturn(24);
        when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(sellerOrder));
        when(orderRepository.updateSellerOrderStatus(eq(10L), anyCollection(), eq("CANCELLED"), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(orderRepository.findUnitIdsBySellerOrderId(10L)).thenReturn(List.of(501L, 502L));
        when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(salesOrder));
        when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(salesOrder));

        OrderDetailsResponse response = service.cancelBuyerOrder(buyerPrincipal, 1L, new CancelOrderRequest("Changed my mind"));

        assertThat(response).isNotNull();
        verify(inventoryPort).release(List.of(501L, 502L));
        verify(orderRepository).insertStatusHistory(eq(10L), eq("PENDING_PAYMENT"), eq("CANCELLED"), eq(buyerPrincipal.userId()), eq("Changed my mind"));
    }

    @Test
    @DisplayName("cancelBuyerOrder throws ConflictException if cancellation window closed")
    void cancelBuyerOrder_WindowClosed() {
        SalesOrderRecord salesOrder = mockSalesOrder(1L, buyerPrincipal.userId(), "PENDING_PAYMENT", OffsetDateTime.now().minusHours(30));
        when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(salesOrder));
        when(platformSettingService.getInt(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS)).thenReturn(24);

        assertThatThrownBy(() -> service.cancelBuyerOrder(buyerPrincipal, 1L, new CancelOrderRequest("Late cancel")))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.CANCEL_WINDOW_CLOSED));

        verify(inventoryPort, never()).release(anyCollection());
    }

    @Test
    @DisplayName("confirmBuyerOrderReceived transitions SHIPPED order to COMPLETED, releases escrow, and grants collection cards")
    void confirmBuyerOrderReceived_Success() {
        SalesOrderRecord salesOrder = mockSalesOrder(1L, buyerPrincipal.userId(), "PAID", OffsetDateTime.now().minusDays(2));
        SellerOrderRecord sellerOrder = mockSellerOrder(10L, 1L, 77L, "SHIPPED");

        when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(sellerOrder));
        when(orderRepository.findSalesOrderByIdAndBuyerId(1L, buyerPrincipal.userId())).thenReturn(Optional.of(salesOrder));
        when(orderRepository.updateSellerOrderStatus(eq(10L), anyCollection(), eq("COMPLETED"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(salesOrder));
        when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(sellerOrder));
        when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(salesOrder));

        OrderDetailsResponse response = service.confirmBuyerOrderReceived(buyerPrincipal, 10L);

        assertThat(response).isNotNull();
        verify(ledgerPort).release(10L);
        verify(collectionPort).grant(buyerPrincipal.userId(), 10L);
        verify(orderRepository).insertStatusHistory(eq(10L), eq("SHIPPED"), eq("COMPLETED"), eq(buyerPrincipal.userId()), anyString());
    }

    @Test
    @DisplayName("shipSellerOrder transitions PAID seller order to SHIPPED and commits inventory sale")
    void shipSellerOrder_Success() {
        SellerProfile profile = new SellerProfile(77L, sellerPrincipal.userId(), SellerStatus.VERIFIED, OffsetDateTime.now(), null, (short) 1, false, true, OffsetDateTime.now());
        when(sellerProfileRepository.findByUserId(sellerPrincipal.userId())).thenReturn(Optional.of(profile));

        SellerOrderRecord sellerOrder = mockSellerOrder(10L, 1L, 77L, "PAID");
        when(orderRepository.findSellerOrderByIdAndSellerProfileId(10L, 77L)).thenReturn(Optional.of(sellerOrder));
        when(platformSettingService.getInt(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS)).thenReturn(7);
        when(orderRepository.updateSellerOrderStatus(eq(10L), anyCollection(), eq("SHIPPED"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        OrderItemRecord item = mock(OrderItemRecord.class);
        when(item.getId()).thenReturn(100L);
        when(orderRepository.findOrderItemsBySellerOrderId(10L)).thenReturn(List.of(item));
        when(orderRepository.findUnitIdsByOrderItemId(100L)).thenReturn(List.of(888L));

        SalesOrderRecord salesOrder = mockSalesOrder(1L, buyerPrincipal.userId(), "PAID", OffsetDateTime.now());
        when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(salesOrder));
        when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(sellerOrder));
        when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(sellerOrder));

        ShipOrderRequest shipRequest = new ShipOrderRequest("Flash Express", "FL9876543210", "FLASH", "proof/ship_10.jpg", LocalDate.now().plusDays(2));
        SellerOrderDetailsResponse response = service.shipSellerOrder(sellerPrincipal, 10L, shipRequest);

        assertThat(response).isNotNull();
        verify(inventoryPort).commitSale(100L, List.of(888L), sellerPrincipal.userId());
        verify(orderRepository).insertShipment(eq(10L), eq("FLASH"), eq("Flash Express"), eq("FL9876543210"), eq("IN_TRANSIT"), any(), any(), eq("proof/ship_10.jpg"), eq(sellerPrincipal.userId()));
        verify(orderRepository).insertStatusHistory(eq(10L), eq("PAID"), eq("SHIPPED"), eq(sellerPrincipal.userId()), anyString());
    }

    @Test
    @DisplayName("autoCompleteSellerOrder completes overdue order and releases escrow automatically")
    void autoCompleteSellerOrder_Success() {
        SellerOrderRecord sellerOrder = mockSellerOrder(10L, 1L, 77L, "SHIPPED");
        when(orderRepository.findSellerOrderById(10L)).thenReturn(Optional.of(sellerOrder));
        when(orderRepository.updateSellerOrderStatus(eq(10L), anyCollection(), eq("COMPLETED"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        SalesOrderRecord salesOrder = mockSalesOrder(1L, buyerPrincipal.userId(), "PAID", OffsetDateTime.now().minusDays(8));
        when(orderRepository.findSalesOrderById(1L)).thenReturn(Optional.of(salesOrder));
        when(orderRepository.findSalesOrderByIdForUpdate(1L)).thenReturn(Optional.of(salesOrder));
        when(orderRepository.findSellerOrdersBySalesOrderId(1L)).thenReturn(List.of(sellerOrder));

        service.autoCompleteSellerOrder(10L);

        verify(ledgerPort).release(10L);
        verify(collectionPort).grant(buyerPrincipal.userId(), 10L);
        verify(orderRepository).insertStatusHistory(eq(10L), eq("SHIPPED"), eq("COMPLETED"), eq(null), anyString());
    }
}
