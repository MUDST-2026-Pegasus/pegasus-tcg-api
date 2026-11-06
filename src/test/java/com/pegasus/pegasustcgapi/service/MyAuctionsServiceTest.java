package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.MyTab;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionViewerState;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionItemMapper;
import com.pegasus.pegasustcgapi.service.auction.BidderStrikeService;
import com.pegasus.pegasustcgapi.service.auction.MyAuctionsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyAuctionsServiceTest {
    private static final long BUYER = 42L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T12:00:00Z");
    private static final BigDecimal PRICE = new BigDecimal("1000.00");

    @Mock AuctionRepository auctions;
    @Mock AuctionItemMapper itemMapper;
    @Mock BidderStrikeService strikes;
    private MyAuctionsService service;

    @BeforeEach
    void setUp() {
        service = new MyAuctionsService(auctions, itemMapper, strikes,
                Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void calculatesAllFiveStatesAndUnpaidWinnerBecomesLost() {
        var rows = List.of(
                auction(1, AuctionStatus.LIVE, null, 0),
                auction(2, AuctionStatus.LIVE, null, 0),
                auction(3, AuctionStatus.AWAITING_PAYMENT, BUYER, 0),
                auction(4, AuctionStatus.SOLD, BUYER, 0),
                auction(5, AuctionStatus.UNSOLD, BUYER, 0));
        when(auctions.findMyAuctions(BUYER, MyTab.ALL, 20, 0)).thenReturn(rows);
        when(auctions.findMyHighestBids(BUYER, Set.of(1L, 2L, 3L, 4L, 5L)))
                .thenReturn(Map.of(1L, PRICE, 2L, PRICE, 3L, PRICE, 4L, PRICE, 5L, PRICE));
        when(auctions.findMyLeadingAuctionIds(BUYER, Set.of(1L, 2L, 3L, 4L, 5L)))
                .thenReturn(Set.of(1L));
        stubItems();
        when(strikes.status(BUYER, NOW)).thenReturn(new BidderStrikeService.PenaltyStatus(2, 3, NOW.plusDays(7)));
        when(auctions.findLastStrikeCardName(BUYER)).thenReturn(Optional.of("Charizard"));

        var result = service.list(BUYER, MyTab.ALL, 0, 20);

        assertThat(result.page().items()).extracting(a -> a.state()).containsExactly(
                AuctionViewerState.LEADING, AuctionViewerState.OUTBID,
                AuctionViewerState.WON_AWAITING_PAYMENT, AuctionViewerState.WON, AuctionViewerState.LOST);
        assertThat(result.page().items().get(2).totalDue()).isEqualByComparingTo("1050.00");
        assertThat(result.page().items().get(2).deadline()).isEqualTo(NOW.plusHours(24));
        assertThat(result.page().items().get(0).minNextBid()).isNotNull();
        assertThat(result.page().items().get(4).minNextBid()).isNull();
        assertThat(result.unpaidStrikes()).isEqualTo(2);
        assertThat(result.strikeLimit()).isEqualTo(3);
        assertThat(result.bannedUntil()).isEqualTo(NOW.plusDays(7));
        assertThat(result.lastStrikeCardName()).isEqualTo("Charizard");
    }

    @Test
    void passesBuyerAndTabToRepositoryAndReturnsAllTabCounts() {
        when(auctions.findMyAuctions(BUYER, MyTab.TO_PAY, 10, 10)).thenReturn(List.of());
        when(auctions.countMyAuctions(BUYER, MyTab.ALL)).thenReturn(12L);
        when(auctions.countMyAuctions(BUYER, MyTab.BIDDING)).thenReturn(3L);
        when(auctions.countMyAuctions(BUYER, MyTab.TO_PAY)).thenReturn(2L);
        when(auctions.countMyAuctions(BUYER, MyTab.WON)).thenReturn(4L);
        when(auctions.countMyAuctions(BUYER, MyTab.LOST)).thenReturn(3L);
        when(strikes.status(BUYER, NOW)).thenReturn(new BidderStrikeService.PenaltyStatus(0, 3, null));

        var result = service.list(BUYER, MyTab.TO_PAY, 1, 10);

        verify(auctions).findMyAuctions(BUYER, MyTab.TO_PAY, 10, 10);
        assertThat(result.page().totalItems()).isEqualTo(2);
        assertThat(result.tabCounts()).containsEntry(MyTab.ALL, 12L).containsEntry(MyTab.BIDDING, 3L)
                .containsEntry(MyTab.TO_PAY, 2L).containsEntry(MyTab.WON, 4L).containsEntry(MyTab.LOST, 3L);
        assertThat(result.page().items()).isEmpty();
    }

    @Test
    void buyNowWinnerUsesWinningAmountEvenWithoutBid() {
        var row = auction(7, AuctionStatus.AWAITING_PAYMENT, BUYER, 0);
        when(auctions.findMyAuctions(BUYER, MyTab.TO_PAY, 20, 0)).thenReturn(List.of(row));
        stubItems();
        when(strikes.status(BUYER, NOW)).thenReturn(new BidderStrikeService.PenaltyStatus(0, 3, null));

        var result = service.list(BUYER, MyTab.TO_PAY, 0, 20);

        assertThat(result.page().items().getFirst().currentPrice()).isEqualByComparingTo("1000.00");
        assertThat(result.page().items().getFirst().myHighestBid()).isEqualByComparingTo("1000.00");
    }

    private void stubItems() {
        when(itemMapper.buildAuctionItems(anySet(), anySet())).thenReturn(
                new AuctionItemMapper.AuctionItemBundle(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    private Auction auction(long id, AuctionStatus status, Long winner, int extensions) {
        return new Auction(id, "AUC-2026-" + id, 10L, 20L, 30L, CardCondition.NM,
                PRICE, new BigDecimal("50.00"), null, PRICE, null, 1, 1,
                NOW.minusDays(1), NOW.plusHours(1), NOW.plusHours(1), extensions,
                NOW.minusHours(1), null, null, winner, PRICE, NOW, NOW.plusHours(24),
                null, status, null, null, null, null, null, 1, NOW.minusDays(1), NOW);
    }
}
