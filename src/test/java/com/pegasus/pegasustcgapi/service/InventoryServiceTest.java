package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.port.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.InventoryMovementRepository;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.SellerVariantCostRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final long SELLER = 11L;
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED);

    @Mock
    private SellerPort sellers;

    @Mock
    private ListingUnitRepository units;

    @Mock
    private InventoryMovementRepository movements;

    @Mock
    private SellerVariantCostRepository costs;

    @Mock
    private InventoryLedger ledger;

    @Mock
    private ListingCardRenderer cards;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryService(sellers, units, movements, costs, ledger, cards, FIXED);
    }

    private static ListingUnit card(long id, long listingId, ListingUnitStatus status) {
        return new ListingUnit(id, UUID.randomUUID(), SELLER, 902L, CardCondition.NM, listingId, status, null,
                new BigDecimal("900.00"), NOW, null, null, NOW, NOW);
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((ApiException) e).errorCode();
    }

    @Test
    @DisplayName("when a listing cannot supply the quantity, no card is marked reserved")
    void shortListingReservesNothing() {
        given(units.lockForReservation(7001L, 2)).willReturn(List.of(card(1L, 7001L, ListingUnitStatus.LISTED)));

        assertThatThrownBy(() -> service.reserve(Map.of(7001L, 2)))
                .extracting(InventoryServiceTest::codeOf)
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        verify(units, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("listings are reserved in ascending id order, whatever order checkout names them in")
    void reservesInListingIdOrder() {
        Map<Long, Integer> basket = new LinkedHashMap<>();
        basket.put(7004L, 1);
        basket.put(7001L, 1);
        given(units.lockForReservation(7001L, 1)).willReturn(List.of(card(1L, 7001L, ListingUnitStatus.LISTED)));
        given(units.lockForReservation(7004L, 1)).willReturn(List.of(card(2L, 7004L, ListingUnitStatus.LISTED)));

        Map<Long, List<ReservedUnit>> held = service.reserve(basket);

        InOrder order = inOrder(units);
        order.verify(units).lockForReservation(7001L, 1);
        order.verify(units).changeStatus(List.of(1L), ListingUnitStatus.LISTED, ListingUnitStatus.RESERVED);
        order.verify(units).lockForReservation(7004L, 1);
        assertThat(held).containsOnlyKeys(7001L, 7004L);
    }

    @Test
    @DisplayName("a card that is not reserved cannot be sold, and nothing is booked")
    void commitSaleNeedsReservedCards() {
        given(units.lockByIds(List.of(1L))).willReturn(List.of(card(1L, 7001L, ListingUnitStatus.LISTED)));

        assertThatThrownBy(() -> service.commitSale(8001L, List.of(1L), null))
                .extracting(InventoryServiceTest::codeOf)
                .isEqualTo(ErrorCode.LISTING_UNIT_STATE);

        verify(units, never()).markSold(any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("a returned card goes back into the books at the cost it left at, not what it was bought for")
    void returnUsesTheSaleCost() {
        ListingUnit sold = card(5L, 7001L, ListingUnitStatus.SOLD);
        given(units.lockByIds(List.of(5L))).willReturn(List.of(sold));
        given(movements.lastSaleCost(5L)).willReturn(Optional.of(new BigDecimal("950.00")));

        service.restockReturn(9801L, 5L, null);

        verify(units).markReturned(5L);
        verify(ledger).returned(sold, new BigDecimal("950.00"), 9801L, null);
    }

    @Test
    @DisplayName("releasing skips cards that are no longer held, so a second release is harmless")
    void releaseSkipsCardsNoLongerHeld() {
        given(units.lockByIds(List.of(1L, 2L))).willReturn(List.of(
                card(1L, 7001L, ListingUnitStatus.RESERVED), card(2L, 7001L, ListingUnitStatus.LISTED)));

        service.release(List.of(1L, 2L));

        verify(units).release(List.of(1L));
    }

    @Test
    @DisplayName("with nothing on the books the average cost is zero, not an error")
    void noCostOnTheBooks() {
        given(costs.find(SELLER, new MarketKey(902L, CardCondition.NM))).willReturn(Optional.empty());

        assertThat(service.averageUnitCost(SELLER, 902L, CardCondition.NM)).isEqualByComparingTo("0");
    }
}
