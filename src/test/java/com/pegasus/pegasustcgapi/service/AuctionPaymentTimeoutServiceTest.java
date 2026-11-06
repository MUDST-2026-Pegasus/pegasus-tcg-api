package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.listing.ListingUnit;
import com.pegasus.pegasustcgapi.model.listing.ListingUnitStatus;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionPaymentTimeoutService;
import com.pegasus.pegasustcgapi.service.auction.BidderStrikeService;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuctionPaymentTimeoutServiceTest {
    private final AuctionRepository auctions = mock(AuctionRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
    private final BidderStrikeService strikes = mock(BidderStrikeService.class);
    private final ListingUnitRepository units = mock(ListingUnitRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final AuctionPaymentTimeoutService service = new AuctionPaymentTimeoutService(
            auctions, orders, lifecycle, strikes, units, inventory);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-05T12:00:00Z");

    private Auction auction(AuctionStatus status, Long orderId, OffsetDateTime dueAt) {
        Auction a = mock(Auction.class);
        when(a.status()).thenReturn(status);
        when(a.salesOrderId()).thenReturn(orderId);
        when(a.paymentDueAt()).thenReturn(dueAt);
        when(a.winnerId()).thenReturn(7L);
        when(a.listingUnitId()).thenReturn(8L);
        when(a.sellerProfileId()).thenReturn(9L);
        return a;
    }

    @Test
    void paidAuctionIsSkipped() {
        Auction paid = auction(AuctionStatus.SOLD, 10L, now.minusHours(1));
        when(auctions.findById(1)).thenReturn(Optional.of(paid));
        assertThat(service.expire(1, now)).isFalse();
        verifyNoInteractions(lifecycle, strikes, units, inventory);
    }

    @Test
    void deadlineNotReachedIsSkipped() {
        Auction a = auction(AuctionStatus.AWAITING_PAYMENT, null, now.plusMinutes(1));
        when(auctions.findById(1)).thenReturn(Optional.of(a));
        when(auctions.lock(1)).thenReturn(Optional.of(a));
        assertThat(service.expire(1, now)).isFalse();
        verifyNoInteractions(strikes, units, inventory);
    }

    @Test
    void unpaidWinWithoutOrderReturnsCardAndRecordsStrike() {
        Auction a = auction(AuctionStatus.AWAITING_PAYMENT, null, now.minusMinutes(1));
        ListingUnit card = mock(ListingUnit.class);
        when(card.status()).thenReturn(ListingUnitStatus.IN_STOCK);
        when(auctions.findById(1)).thenReturn(Optional.of(a));
        when(auctions.lock(1)).thenReturn(Optional.of(a));
        when(auctions.recordUnpaidAuction(1, now)).thenReturn(true);
        when(units.revertFromAuction(8)).thenReturn(true);
        when(units.findOfSeller(8, 9)).thenReturn(Optional.of(card));
        assertThat(service.expire(1, now)).isTrue();
        verify(strikes).recordUnpaidWin(7, 1, null, now);
        verify(inventory).recordAuctionUnsold(card, 1, null);
    }

    @Test
    void unpaidWinWithOrderCancelsItAndReturnsCard() {
        Auction a = auction(AuctionStatus.AWAITING_PAYMENT, 10L, now.minusMinutes(1));
        ListingUnit card = mock(ListingUnit.class);
        when(card.status()).thenReturn(ListingUnitStatus.IN_STOCK);
        when(auctions.findById(1)).thenReturn(Optional.of(a));
        when(auctions.lock(1)).thenReturn(Optional.of(a));
        when(lifecycle.expireUnpaidAuctionOrder(10L, 1)).thenReturn(true);
        when(auctions.recordUnpaidAuction(1, now)).thenReturn(true);
        when(units.findOfSeller(8, 9)).thenReturn(Optional.of(card));
        assertThat(service.expire(1, now)).isTrue();
        var order = inOrder(orders, auctions, lifecycle, strikes, inventory);
        order.verify(orders).findSalesOrderByIdForUpdate(10L);
        order.verify(auctions).lock(1);
        order.verify(lifecycle).expireUnpaidAuctionOrder(10L, 1);
        order.verify(strikes).recordUnpaidWin(7, 1, 10L, now);
        order.verify(inventory).recordAuctionUnsold(card, 1, null);
        verify(units, never()).revertFromAuction(anyLong());
    }

    @Test
    void paymentUnderReviewLeavesAuctionUnchanged() {
        Auction a = auction(AuctionStatus.AWAITING_PAYMENT, 10L, now.minusMinutes(1));
        when(auctions.findById(1)).thenReturn(Optional.of(a));
        when(auctions.lock(1)).thenReturn(Optional.of(a));
        assertThat(service.expire(1, now)).isFalse();
        verifyNoInteractions(strikes, units, inventory);
    }

    @Test
    void previouslyReleasedCardDoesNotBlockAuctionExpiry() {
        Auction a = auction(AuctionStatus.AWAITING_PAYMENT, 10L, now.minusMinutes(1));
        ListingUnit card = mock(ListingUnit.class);
        when(card.status()).thenReturn(ListingUnitStatus.LISTED);
        when(auctions.findById(1)).thenReturn(Optional.of(a));
        when(auctions.lock(1)).thenReturn(Optional.of(a));
        when(lifecycle.expireUnpaidAuctionOrder(10L, 1)).thenReturn(true);
        when(auctions.recordUnpaidAuction(1, now)).thenReturn(true);
        when(units.findOfSeller(8, 9)).thenReturn(Optional.of(card));

        assertThat(service.expire(1, now)).isTrue();
        verify(strikes).recordUnpaidWin(7, 1, 10L, now);
        verifyNoInteractions(inventory);
    }
}
