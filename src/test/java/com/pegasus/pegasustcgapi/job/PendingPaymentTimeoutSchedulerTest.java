package com.pegasus.pegasustcgapi.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.PlatformSettingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingPaymentTimeoutScheduler")
class PendingPaymentTimeoutSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @Mock
    private PlatformSettingService platformSettingService;

    @Mock
    private JobLock jobLock;

    private PendingPaymentTimeoutScheduler scheduler(int batchSize) {
        return new PendingPaymentTimeoutScheduler(
                orderRepository, orderLifecycleService, platformSettingService, jobLock,
                Clock.fixed(NOW, ZoneOffset.UTC), batchSize);
    }

    private void lockGranted() {
        given(jobLock.runExclusively(anyString(), any())).willAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Test
    @DisplayName("cancels orders whose payment deadline has passed, reading the deadline from settings")
    void cancelsOrdersPastTheDeadline() {
        SellerOrderRecord order = mock(SellerOrderRecord.class);
        given(order.getId()).willReturn(501L);

        lockGranted();
        given(platformSettingService.getMinutes(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES))
                .willReturn(Duration.ofMinutes(60));
        given(orderRepository.findExpiredPendingPaymentOrders(any(), anyInt())).willReturn(List.of(order));

        scheduler(10).runPaymentTimeout();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(orderRepository).findExpiredPendingPaymentOrders(cutoff.capture(), eq(10));
        assertThat(cutoff.getValue().toInstant()).isEqualTo(NOW.minus(Duration.ofMinutes(60)));
        verify(orderLifecycleService).expireUnpaidSellerOrder(501L);
    }

    @Test
    @DisplayName("does nothing when another instance already holds the job lock")
    void skipsWhenLockHeldElsewhere() {
        given(jobLock.runExclusively(anyString(), any())).willReturn(false);

        scheduler(10).runPaymentTimeout();

        verify(platformSettingService, never()).getMinutes(anyString());
        verify(orderLifecycleService, never()).expireUnpaidSellerOrder(anyLong());
    }

    @Test
    @DisplayName("one order that will not close does not stop the rest of the batch")
    void keepsGoingAfterOneFailure() {
        SellerOrderRecord first = mock(SellerOrderRecord.class);
        given(first.getId()).willReturn(601L);
        SellerOrderRecord second = mock(SellerOrderRecord.class);
        given(second.getId()).willReturn(602L);

        lockGranted();
        given(platformSettingService.getMinutes(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES))
                .willReturn(Duration.ofMinutes(30));
        given(orderRepository.findExpiredPendingPaymentOrders(any(), anyInt()))
                .willReturn(List.of(first, second));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("boom"))
                .given(orderLifecycleService).expireUnpaidSellerOrder(601L);

        scheduler(10).runPaymentTimeout();

        verify(orderLifecycleService).expireUnpaidSellerOrder(602L);
    }
}
