package com.pegasus.pegasustcgapi.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderAutoReleaseScheduler")
class OrderAutoReleaseSchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @Mock
    private JobLock jobLock;

    private OrderAutoReleaseScheduler scheduler(int batchSize) {
        return new OrderAutoReleaseScheduler(
                orderRepository, orderLifecycleService, jobLock, Clock.systemUTC(), batchSize);
    }

    /** Stands in for winning the lock: the task runs. */
    private void lockGranted() {
        given(jobLock.runExclusively(anyString(), any())).willAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Test
    @DisplayName("runAutoRelease queries overdue orders and calls autoCompleteSellerOrder for each")
    void runAutoRelease_ProcessesOverdueOrders() {
        SellerOrderRecord order1 = mock(SellerOrderRecord.class);
        when(order1.getId()).thenReturn(101L);

        SellerOrderRecord order2 = mock(SellerOrderRecord.class);
        when(order2.getId()).thenReturn(102L);

        lockGranted();
        when(orderRepository.findOverdueShippedOrders(any(), anyInt())).thenReturn(List.of(order1, order2));

        scheduler(10).runAutoRelease();

        verify(orderLifecycleService).autoCompleteSellerOrder(101L);
        verify(orderLifecycleService).autoCompleteSellerOrder(102L);
    }

    @Test
    @DisplayName("does nothing when another instance already holds the job lock")
    void skipsWhenLockHeldElsewhere() {
        given(jobLock.runExclusively(anyString(), any())).willReturn(false);

        scheduler(10).runAutoRelease();

        verify(orderRepository, never()).findOverdueShippedOrders(any(), anyInt());
        verify(orderLifecycleService, never()).autoCompleteSellerOrder(anyLong());
    }

    @Test
    @DisplayName("keeps pulling batches until a short one says the backlog is drained")
    void drainsBacklogInBatches() {
        SellerOrderRecord first = mock(SellerOrderRecord.class);
        when(first.getId()).thenReturn(201L);
        SellerOrderRecord second = mock(SellerOrderRecord.class);
        when(second.getId()).thenReturn(202L);

        lockGranted();
        when(orderRepository.findOverdueShippedOrders(any(), anyInt()))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of());

        scheduler(1).runAutoRelease();

        verify(orderLifecycleService).autoCompleteSellerOrder(201L);
        verify(orderLifecycleService).autoCompleteSellerOrder(202L);
    }
}
