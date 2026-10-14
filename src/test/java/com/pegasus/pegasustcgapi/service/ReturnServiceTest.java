package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.ReturnRequestRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
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
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
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

    @Test void evidenceLinksAreSignedOnlyForTheOwningSeller() {
        ReturnRequestRecord request = new ReturnRequestRecord();
        request.setId(5L);
        request.setSellerOrderId(3L);
        request.setEvidenceKeys(JSONB.valueOf("[\"returns/photo.png\"]"));
        SellerOrderRecord order = new SellerOrderRecord();
        order.setSellerProfileId(10L);
        when(returns.find(5L)).thenReturn(Optional.of(request));
        when(orders.findSellerOrderById(3L)).thenReturn(Optional.of(order));
        when(sellers.requireProfile(30L)).thenReturn(new SellerProfile(10L, 30L,
                SellerStatus.VERIFIED, null, null, (short) 1, false, false, null));
        when(json.readValue(org.mockito.ArgumentMatchers.eq("[\"returns/photo.png\"]"),
                org.mockito.ArgumentMatchers.<TypeReference<List<String>>>any()))
                .thenReturn(List.of("returns/photo.png"));
        when(storage.presignDownload("returns/photo.png")).thenReturn("https://example.test/photo");

        assertThat(service.sellerEvidence(30L, 5L)).containsExactly(
                new ReturnService.EvidenceLink(1, "https://example.test/photo"));
        verify(storage).presignDownload("returns/photo.png");
    }

    @Test void anotherSellerCannotGetEvidenceLinks() {
        ReturnRequestRecord request = new ReturnRequestRecord();
        request.setSellerOrderId(3L);
        SellerOrderRecord order = new SellerOrderRecord();
        order.setSellerProfileId(10L);
        when(returns.find(5L)).thenReturn(Optional.of(request));
        when(orders.findSellerOrderById(3L)).thenReturn(Optional.of(order));
        when(sellers.requireProfile(31L)).thenReturn(new SellerProfile(11L, 31L,
                SellerStatus.VERIFIED, null, null, (short) 1, false, false, null));

        assertThatThrownBy(() -> service.sellerEvidence(31L, 5L))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(storage);
    }
}
