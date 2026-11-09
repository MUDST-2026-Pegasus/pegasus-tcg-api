package com.pegasus.pegasustcgapi.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.job.auction.AuctionCloseScheduler;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingUnit;
import com.pegasus.pegasustcgapi.model.listing.ListingUnitStatus;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionCloseService;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuctionCloseScheduler & AuctionCloseService")
class AuctionCloseSchedulerTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-11-02T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(BASE_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED_CLOCK);

    private static final long SELLER_PROFILE_ID = 101L;
    private static final long UNIT_ID = 201L;
    private static final long VARIANT_ID = 301L;
    private static final long WINNER_ID = 88L;
    private static final long LEADING_BID_ID = 999L;

    @Mock
    private AuctionRepository auctions;

    @Mock
    private ListingUnitRepository units;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private JobLock jobLock;

    @Mock
    private AuctionCloseService mockAuctionCloseService;

    @Mock
    private PlatformSettingService settings;

    private AuctionCloseService realCloseService;

    @BeforeEach
    void setUp() {
        realCloseService = new AuctionCloseService(auctions, units, inventoryService, settings);
        org.mockito.Mockito.lenient().when(settings.getAuctionPaymentWindow())
                .thenReturn(java.time.Duration.ofHours(24));
    }

    private Auction sampleAuction(
            long auctionId,
            AuctionStatus status,
            int bidCount,
            BigDecimal currentPrice,
            OffsetDateTime endsAt,
            Long leadingBidId) {
        return new Auction(
                auctionId,
                "AUC-20261102-0001",
                SELLER_PROFILE_ID,
                UNIT_ID,
                VARIANT_ID,
                CardCondition.NM,
                new BigDecimal("1000.00"),
                new BigDecimal("50.00"),
                new BigDecimal("3000.00"),
                currentPrice,
                leadingBidId,
                bidCount,
                bidCount > 0 ? 1 : 0,
                NOW.minusHours(2),
                endsAt,
                endsAt,
                0,
                bidCount > 0 ? NOW.minusHours(1) : null,
                "Note",
                null,
                null,
                null,
                null,
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                1,
                NOW.minusHours(2),
                NOW.minusHours(2));
    }

    private ListingUnit sampleUnit(long unitId) {
        return new ListingUnit(
                unitId,
                UUID.randomUUID(),
                SELLER_PROFILE_ID,
                VARIANT_ID,
                CardCondition.NM,
                null,
                ListingUnitStatus.ON_AUCTION,
                null,
                new BigDecimal("500.00"),
                NOW,
                null,
                null,
                NOW,
                NOW);
    }

    private void lockGranted() {
        given(jobLock.runExclusively(anyString(), any())).willAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Nested
    @DisplayName("Scheduler Lock & Batch Processing")
    class SchedulerLockAndBatchTests {

        @Test
        @DisplayName("skips execution when another instance already holds the job lock")
        void skipsWhenLockHeldElsewhere() {
            given(jobLock.runExclusively(anyString(), any())).willReturn(false);

            AuctionCloseScheduler scheduler = new AuctionCloseScheduler(
                    auctions, mockAuctionCloseService, jobLock, FIXED_CLOCK, 100);
            scheduler.runAuctionClose();

            verify(auctions, never()).findExpiredLiveAuctionIds(any(), anyInt());
            verify(mockAuctionCloseService, never()).closeAuction(anyLong(), any());
        }

        @Test
        @DisplayName("one auction failure does not stop subsequent auctions in the batch")
        void keepsGoingAfterOneFailure() {
            lockGranted();
            given(auctions.findExpiredLiveAuctionIds(NOW, 10)).willReturn(List.of(101L, 102L));
            doThrow(new RuntimeException("DB glitch")).when(mockAuctionCloseService).closeAuction(101L, NOW);

            AuctionCloseScheduler scheduler = new AuctionCloseScheduler(
                    auctions, mockAuctionCloseService, jobLock, FIXED_CLOCK, 10);
            scheduler.runAuctionClose();

            verify(mockAuctionCloseService).closeAuction(101L, NOW);
            verify(mockAuctionCloseService).closeAuction(102L, NOW);
        }

        @Test
        @DisplayName("full batch with zero progress terminates early to prevent infinite busy spinning")
        void fullBatch_ZeroProgress_TerminatesEarly() {
            lockGranted();
            given(auctions.findExpiredLiveAuctionIds(NOW, 3))
                    .willReturn(List.of(101L, 102L, 103L));
            given(mockAuctionCloseService.closeAuction(anyLong(), eq(NOW)))
                    .willReturn(false);

            AuctionCloseScheduler scheduler = new AuctionCloseScheduler(
                    auctions, mockAuctionCloseService, jobLock, FIXED_CLOCK, 3);
            scheduler.runAuctionClose();

            // Verified that it called findExpiredLiveAuctionIds only once
            verify(auctions).findExpiredLiveAuctionIds(NOW, 3);
            verify(mockAuctionCloseService).closeAuction(101L, NOW);
            verify(mockAuctionCloseService).closeAuction(102L, NOW);
            verify(mockAuctionCloseService).closeAuction(103L, NOW);
        }
    }

    @Nested
    @DisplayName("AuctionCloseService Business Rules")
    class AuctionCloseServiceRuleTests {

        @Test
        void winnerDeadlineUsesCurrentPaymentSetting() {
            realCloseService = new AuctionCloseService(auctions, units, inventoryService, settings);
            long auctionId = 506L;
            Auction auction = sampleAuction(auctionId, AuctionStatus.LIVE, 1,
                    new BigDecimal("1100.00"), NOW.minusSeconds(1), LEADING_BID_ID);
            given(auctions.lock(auctionId)).willReturn(Optional.of(auction));
            given(auctions.findBidById(LEADING_BID_ID)).willReturn(Optional.of(
                    new AuctionBid(LEADING_BID_ID, auctionId, WINNER_ID,
                            new BigDecimal("1100.00"), BidStatus.ACTIVE, null, NOW.minusMinutes(1))));
            given(settings.getAuctionPaymentWindow()).willReturn(java.time.Duration.ofSeconds(36));

            realCloseService.closeAuction(auctionId, NOW);

            verify(auctions).recordAuctionWon(auctionId, WINNER_ID,
                    new BigDecimal("1100.00"), NOW, NOW.plusSeconds(36));
        }

        @Test
        @DisplayName("Case 1: Auction with bids transitions to AWAITING_PAYMENT with leading bidder and payment_due_at")
        void closeAuction_WithBids_TransitionsToAwaitingPayment() {
            long auctionId = 501L;
            Auction auction = sampleAuction(auctionId, AuctionStatus.LIVE, 3, new BigDecimal("1800.00"), NOW.minusSeconds(10), LEADING_BID_ID);
            given(auctions.lock(auctionId)).willReturn(Optional.of(auction));

            AuctionBid leadingBid = new AuctionBid(LEADING_BID_ID, auctionId, WINNER_ID, new BigDecimal("1800.00"), BidStatus.ACTIVE, "key-1", NOW.minusMinutes(30));
            given(auctions.findBidById(LEADING_BID_ID)).willReturn(Optional.of(leadingBid));

            OffsetDateTime expectedPaymentDueAt = NOW.plusHours(24);
            given(auctions.recordAuctionWon(auctionId, WINNER_ID, new BigDecimal("1800.00"), NOW, expectedPaymentDueAt)).willReturn(true);

            realCloseService.closeAuction(auctionId, NOW);

            verify(auctions).recordAuctionWon(auctionId, WINNER_ID, new BigDecimal("1800.00"), NOW, expectedPaymentDueAt);
            verify(units, never()).revertFromAuction(anyLong());
            verify(inventoryService, never()).recordAuctionUnsold(any(), anyLong(), any());
        }

        @Test
        @DisplayName("Case 2: Auction with 0 bids transitions to UNSOLD, reverts card to stock, records inventory movement")
        void closeAuction_WithoutBids_TransitionsToUnsoldAndRestocks() {
            long auctionId = 502L;
            Auction auction = sampleAuction(auctionId, AuctionStatus.LIVE, 0, new BigDecimal("1000.00"), NOW.minusSeconds(10), null);
            given(auctions.lock(auctionId)).willReturn(Optional.of(auction));
            given(auctions.recordAuctionUnsold(auctionId, NOW)).willReturn(true);
            given(units.revertFromAuction(UNIT_ID)).willReturn(true);

            ListingUnit unit = sampleUnit(UNIT_ID);
            given(units.findOfSeller(UNIT_ID, SELLER_PROFILE_ID)).willReturn(Optional.of(unit));

            realCloseService.closeAuction(auctionId, NOW);

            verify(auctions).recordAuctionUnsold(auctionId, NOW);
            verify(units).revertFromAuction(UNIT_ID);
            verify(inventoryService).recordAuctionUnsold(unit, auctionId, null);
        }

        @Test
        @DisplayName("Anti-sniping protection: auction whose ends_at was extended past now is skipped")
        void closeAuction_ExtendedAuction_IsSkipped() {
            long auctionId = 503L;
            // endsAt is 30 seconds into the future (was extended by a sniper bid while in queue)
            Auction extendedAuction = sampleAuction(auctionId, AuctionStatus.LIVE, 2, new BigDecimal("1500.00"), NOW.plusSeconds(30), LEADING_BID_ID);
            given(auctions.lock(auctionId)).willReturn(Optional.of(extendedAuction));

            realCloseService.closeAuction(auctionId, NOW);

            verify(auctions, never()).recordAuctionWon(anyLong(), anyLong(), any(), any(), any());
            verify(auctions, never()).recordAuctionUnsold(anyLong(), any());
            verify(units, never()).revertFromAuction(anyLong());
            verify(inventoryService, never()).recordAuctionUnsold(any(), anyLong(), any());
        }

        @Test
        @DisplayName("Auction not LIVE or already closed is skipped")
        void closeAuction_NotLive_IsSkipped() {
            long auctionId = 504L;
            Auction cancelledAuction = sampleAuction(auctionId, AuctionStatus.CANCELLED, 0, new BigDecimal("1000.00"), NOW.minusMinutes(10), null);
            given(auctions.lock(auctionId)).willReturn(Optional.of(cancelledAuction));

            realCloseService.closeAuction(auctionId, NOW);

            verify(auctions, never()).recordAuctionWon(anyLong(), anyLong(), any(), any(), any());
            verify(auctions, never()).recordAuctionUnsold(anyLong(), any());
        }

        @Test
        @DisplayName("Case 3: Stuck auction with bids but missing leading bid transitions to SUSPENDED")
        void closeAuction_WithBids_MissingLeadingBid_TransitionsToSuspended() {
            long auctionId = 505L;
            Auction auction = sampleAuction(auctionId, AuctionStatus.LIVE, 2, new BigDecimal("1500.00"), NOW.minusSeconds(10), LEADING_BID_ID);
            given(auctions.lock(auctionId)).willReturn(Optional.of(auction));
            given(auctions.findBidById(LEADING_BID_ID)).willReturn(Optional.empty());
            given(auctions.recordAuctionSuspended(eq(auctionId), anyString(), eq(NOW))).willReturn(true);

            boolean progress = realCloseService.closeAuction(auctionId, NOW);

            assertThat(progress).isTrue();
            verify(auctions).recordAuctionSuspended(eq(auctionId), anyString(), eq(NOW));
            verify(auctions, never()).recordAuctionWon(anyLong(), anyLong(), any(), any(), any());
            verify(auctions, never()).recordAuctionUnsold(anyLong(), any());
        }
    }
}
