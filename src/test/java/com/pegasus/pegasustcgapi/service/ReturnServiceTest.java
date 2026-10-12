package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.ReturnRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReturnServiceTest {
    @Mock ReturnRepository returns;
    @Mock OrderRepository orders;
    @Mock PaymentRepository payments;
    @Mock SellerPort sellers;
    @Mock OrderLifecycleService lifecycle;
    @Mock RefundService refunds;
    @Mock InventoryPort inventory;
    @Mock StorageService storage;
    @Mock PlatformSettingService settings;
    @Mock ObjectMapper json;
    ReturnService service;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void setUp() {
        service = new ReturnService(returns, orders, payments, sellers, lifecycle, refunds,
                inventory, storage, settings, json, clock);
    }

    @Test void requestAtReleaseDeadlineIsClosedEvenIfSchedulerHasNotRun() {
        SellerOrderRecord sellerOrder = new SellerOrderRecord();
        sellerOrder.setId(3L);
        sellerOrder.setSalesOrderId(2L);
        sellerOrder.setStatus("SHIPPED");
        sellerOrder.setAutoCompleteAt(OffsetDateTime.now(clock));
        SalesOrderRecord parent = new SalesOrderRecord();
        parent.setBuyerId(7L);
        when(orders.findSellerOrderById(3L)).thenReturn(Optional.of(sellerOrder));
        when(payments.findOrder(2L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.request(7L, 3L, "DAMAGED", "Card damaged",
                List.of(), List.of(new ReturnService.Item(4L, 1))))
                .isInstanceOf(ConflictException.class);
        verify(payments).lockOrder(2L);
        verifyNoInteractions(returns, refunds, lifecycle);
    }
}
