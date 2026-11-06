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

    @Test
    void runsOneLockedSweepAndExpiresDueAuctions() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        when(auctions.findExpiredPaymentAuctionIds(now, 10)).thenReturn(List.of(12L, 13L));
        when(lock.runExclusively(eq("auctionPaymentTimeout"), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return true;
        });
        new AuctionPaymentTimeoutScheduler(auctions, timeout, lock, clock, 10).processExpiredAuctions();
        verify(timeout).expire(12L, now);
        verify(timeout).expire(13L, now);
    }

    @Test
    void skippedLockDoesNotProcessAuctions() {
        new AuctionPaymentTimeoutScheduler(auctions, timeout, lock, clock, 10).processExpiredAuctions();
        verifyNoInteractions(auctions, timeout);
    }
}
