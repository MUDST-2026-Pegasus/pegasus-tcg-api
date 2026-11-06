package com.pegasus.pegasustcgapi.job;

import static org.mockito.Mockito.*;

import com.pegasus.pegasustcgapi.job.auction.AuctionPaymentTimeoutScheduler;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionPaymentTimeoutService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionPaymentTimeoutSchedulerTest {
    private final AuctionRepository auctions = mock(AuctionRepository.class);
    private final AuctionPaymentTimeoutService timeout = mock(AuctionPaymentTimeoutService.class);
    private final JobLock lock = mock(JobLock.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
    private final OffsetDateTime now = OffsetDateTime.now(clock);

    private void lockGranted() {
        when(lock.runExclusively(eq("auctionPaymentTimeout"), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return true;
        });
    }

    @Test
    void runsOneLockedSweepAndExpiresDueAuctions() {
        lockGranted();
        when(auctions.findExpiredPaymentAuctionIds(now, 0L, 10)).thenReturn(List.of(12L, 13L));
        new AuctionPaymentTimeoutScheduler(auctions, timeout, lock, clock, 10).processExpiredAuctions();
        verify(timeout).expire(12L, now);
        verify(timeout).expire(13L, now);
    }

    @Test
    void failingAuctionsDoNotBlockLaterOnes() {
        lockGranted();
        // A full first page where every auction fails, then a page behind it.
        when(auctions.findExpiredPaymentAuctionIds(now, 0L, 2)).thenReturn(List.of(1L, 2L));
        when(auctions.findExpiredPaymentAuctionIds(now, 2L, 2)).thenReturn(List.of(3L));
        when(timeout.expire(1L, now)).thenThrow(new IllegalStateException("stuck"));
        when(timeout.expire(2L, now)).thenReturn(false);
        when(timeout.expire(3L, now)).thenReturn(true);

        new AuctionPaymentTimeoutScheduler(auctions, timeout, lock, clock, 2).processExpiredAuctions();

        verify(timeout).expire(3L, now);
        verify(timeout, times(1)).expire(1L, now);
    }

    @Test
    void skippedLockDoesNotProcessAuctions() {
        new AuctionPaymentTimeoutScheduler(auctions, timeout, lock, clock, 10).processExpiredAuctions();
        verifyNoInteractions(auctions, timeout);
    }
}
