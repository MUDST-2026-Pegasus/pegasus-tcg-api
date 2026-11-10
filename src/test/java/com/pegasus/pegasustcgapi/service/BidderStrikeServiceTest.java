package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pegasus.pegasustcgapi.repository.auction.BidderStrikeRepository;
import com.pegasus.pegasustcgapi.service.auction.BidderStrikeService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BidderStrikeServiceTest {
    private final BidderStrikeRepository bidderStrikes = mock(BidderStrikeRepository.class);
    private final PlatformSettingService settings = mock(PlatformSettingService.class);
    private final BidderStrikeService service = new BidderStrikeService(bidderStrikes, settings);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-05T12:00:00Z");

    @Test
    void firstStrikeDoesNotBan() {
        when(bidderStrikes.insertStrike(7, 10, null, now)).thenReturn(true);
        when(settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT)).thenReturn(3);
        when(bidderStrikes.countActiveStrikes(7)).thenReturn(1);
        service.recordUnpaidWin(7, 10, null, now);
        verify(bidderStrikes).lockBidder(7);
        verify(bidderStrikes, never()).insertBan(anyLong(), any(), any());
    }

    @Test
    void thirdStrikeBansForConfiguredDays() {
        when(bidderStrikes.insertStrike(7, 12, 100L, now)).thenReturn(true);
        when(settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT)).thenReturn(3);
        when(settings.getDays(PlatformSettingService.AUCTION_BAN_DAYS)).thenReturn(java.time.Duration.ofDays(30));
        when(bidderStrikes.countActiveStrikes(7)).thenReturn(3);
        service.recordUnpaidWin(7, 12, 100L, now);
        verify(bidderStrikes).insertBan(7, now, now.plusDays(30));
    }

    @Test
    void loweredLimitCreatesBanOnceAfterThresholdWasPassed() {
        when(bidderStrikes.insertStrike(7, 13, null, now)).thenReturn(true);
        when(settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT)).thenReturn(2);
        when(settings.getDays(PlatformSettingService.AUCTION_BAN_DAYS)).thenReturn(java.time.Duration.ofDays(30));
        when(bidderStrikes.countActiveStrikes(7)).thenReturn(4);
        service.recordUnpaidWin(7, 13, null, now);
        verify(bidderStrikes).insertBan(7, now, now.plusDays(30));
    }

    @Test
    void existingActiveBanIsNotDuplicated() {
        when(bidderStrikes.insertStrike(7, 13, null, now)).thenReturn(true);
        when(settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT)).thenReturn(2);
        when(bidderStrikes.countActiveStrikes(7)).thenReturn(4);
        when(bidderStrikes.findActiveBanUntil(7, now)).thenReturn(Optional.of(now.plusDays(5)));
        service.recordUnpaidWin(7, 13, null, now);
        verify(bidderStrikes, never()).insertBan(anyLong(), any(), any());
    }

    @Test
    void duplicateAuctionCannotCreateSecondStrikeOrBan() {
        service.recordUnpaidWin(7, 12, null, now);
        verify(bidderStrikes, never()).insertBan(anyLong(), any(), any());
        verify(bidderStrikes, never()).countActiveStrikes(anyLong());
    }

    @Test
    void buyerStatusIncludesCountAndBanEnd() {
        when(bidderStrikes.countActiveStrikes(7)).thenReturn(2);
        when(settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT)).thenReturn(3);
        when(bidderStrikes.findActiveBanUntil(7, now)).thenReturn(Optional.of(now.plusDays(30)));
        var status = service.status(7, now);
        assertThat(status.strikeCount()).isEqualTo(2);
        assertThat(status.bannedUntil()).isEqualTo(now.plusDays(30));
    }
}
