package com.pegasus.pegasustcgapi.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderAutoReleaseScheduler")
class OrderAutoReleaseSchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @InjectMocks
    private OrderAutoReleaseScheduler scheduler;

    @Test
    @DisplayName("runAutoRelease queries overdue orders and calls autoCompleteSellerOrder for each")
    void runAutoRelease_ProcessesOverdueOrders() {
        SellerOrderRecord order1 = mock(SellerOrderRecord.class);
        when(order1.getId()).thenReturn(101L);

        SellerOrderRecord order2 = mock(SellerOrderRecord.class);
        when(order2.getId()).thenReturn(102L);

        when(orderRepository.findOverdueShippedOrders(any())).thenReturn(List.of(order1, order2));

        scheduler.runAutoRelease();

        verify(orderLifecycleService).autoCompleteSellerOrder(101L);
        verify(orderLifecycleService).autoCompleteSellerOrder(102L);
    }
}
