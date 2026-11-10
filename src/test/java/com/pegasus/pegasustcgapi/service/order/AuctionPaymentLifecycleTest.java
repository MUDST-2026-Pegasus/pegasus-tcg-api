package com.pegasus.pegasustcgapi.service.order;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderTransitionRepository;
import com.pegasus.pegasustcgapi.port.collection.CollectionPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuctionPaymentLifecycleTest {
    @Test void successfulPaymentMarksItsAuctionSold() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderTransitionRepository orderTransitions = mock(OrderTransitionRepository.class);
        OrderQueryRepository orderQueries = mock(OrderQueryRepository.class);
        OrderReferenceQueryRepository orderReferences = mock(OrderReferenceQueryRepository.class);
        AuctionRepository auctions = mock(AuctionRepository.class);
        OrderLifecycleService lifecycle = new OrderLifecycleService(orders, orderTransitions, orderQueries,
                orderReferences, mock(SellerPort.class), mock(PlatformSettingService.class),
                mock(InventoryPort.class), mock(LedgerPort.class), mock(CollectionPort.class), Clock.systemUTC(),
                null, auctions);
        SalesOrderRecord sale = new SalesOrderRecord();
        sale.setId(100L);
        sale.setAuctionId(5L);
        sale.setStatus("PENDING_PAYMENT");
        when(orders.findSalesOrderById(100L)).thenReturn(Optional.of(sale));
        when(orderQueries.findSellerOrdersBySalesOrderId(100L)).thenReturn(List.of());
        // The rollup is not under test: with no locked parent row it stops at once.
        when(orders.findSalesOrderByIdForUpdate(100L)).thenReturn(Optional.empty());

        lifecycle.markOrderPaidBySystem(100L, 42L);

        verify(auctions).markSoldForOrder(5L, 100L);
    }
}
