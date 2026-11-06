package com.pegasus.pegasustcgapi.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.port.collection.CollectionPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuctionOrderTimeoutLifecycleTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final InventoryPort inventory = mock(InventoryPort.class);
    private final LedgerPort ledger = mock(LedgerPort.class);
    private final OrderLifecycleService service = new OrderLifecycleService(orders, mock(SellerPort.class),
            mock(PlatformSettingService.class), inventory, ledger, mock(CollectionPort.class),
            Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC), null);

    @Test
    void createdUnpaidOrderIsCancelledAndCardReleased() {
        SalesOrderRecord sale = mock(SalesOrderRecord.class);
        SellerOrderRecord child = mock(SellerOrderRecord.class);
        when(sale.getSource()).thenReturn("AUCTION");
        when(sale.getAuctionId()).thenReturn(1L);
        when(sale.getStatus()).thenReturn("PENDING_PAYMENT");
        when(child.getId()).thenReturn(11L);
        when(child.getStatus()).thenReturn("PENDING_PAYMENT", "CANCELLED");
        when(orders.findSalesOrderByIdForUpdate(10L)).thenReturn(Optional.of(sale));
        when(orders.findSellerOrdersBySalesOrderId(10L)).thenReturn(List.of(child));
        when(orders.updateSellerOrderStatus(eq(11L), eq(List.of("PENDING_PAYMENT")), eq("CANCELLED"),
                isNull(), isNull(), isNull(), isNull(), any(), anyString())).thenReturn(1);
        when(orders.findUnitIdsBySellerOrderId(11L)).thenReturn(List.of(8L));

        assertThat(service.expireUnpaidAuctionOrder(10L, 1L)).isTrue();
        verify(inventory).release(List.of(8L));
        verify(orders).updateSalesOrderStatus(eq(10L), eq("CANCELLED"), isNull(), isNull(), any());
        verify(ledger).refundOnCancel(eq(11L), anyString(), isNull());
    }

    @Test
    void orderUnderReviewIsNotCancelled() {
        SalesOrderRecord sale = mock(SalesOrderRecord.class);
        when(sale.getSource()).thenReturn("AUCTION");
        when(sale.getAuctionId()).thenReturn(1L);
        when(orders.findSalesOrderByIdForUpdate(10L)).thenReturn(Optional.of(sale));
        when(orders.hasPaymentUnderReview(10L)).thenReturn(true);
        assertThat(service.expireUnpaidAuctionOrder(10L, 1L)).isFalse();
        verifyNoInteractions(inventory, ledger);
    }
}
