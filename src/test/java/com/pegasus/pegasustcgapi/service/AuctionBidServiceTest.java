package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.BidPlaced;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.user.AddressRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionBidService;
import com.pegasus.pegasustcgapi.service.auction.AuctionEligibilityValidator;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class AuctionBidServiceTest {

    private static final long USER_ID = 42L;
    private static final long SELLER_USER_ID = 99L;
    private static final long SELLER_PROFILE_ID = 101L;
    private static final long AUCTION_ID = 501L;
    private static final long VARIANT_ID = 301L;

    private static final Instant BASE_INSTANT = Instant.parse("2026-11-02T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(BASE_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED_CLOCK);

    @Mock
    private AuctionRepository auctions;

    @Mock
    private AddressRepository addresses;

    @Mock
    private UserVerificationPort userVerification;

    @Mock
    private SellerPort sellers;

    @Mock
    private PlatformSettingService settings;

    private AuctionEligibilityValidator validator;
    private AuctionBidService service;

    @BeforeEach
    void setUp() {
        validator = new AuctionEligibilityValidator(addresses, userVerification, auctions, sellers, settings);
        service = new AuctionBidService(auctions, validator, FIXED_CLOCK, settings);
        org.mockito.Mockito.lenient().when(settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT)).thenReturn(3);
        org.mockito.Mockito.lenient().when(settings.getSeconds(PlatformSettingService.AUCTION_EXTENSION_SECONDS))
                .thenReturn(java.time.Duration.ofSeconds(120));
    }

    private Auction sampleAuction(
            AuctionStatus status,
            BigDecimal startingPrice,
            BigDecimal bidIncrement,
            BigDecimal currentPrice,
            int bidCount,
            OffsetDateTime endsAt) {
        return new Auction(
                AUCTION_ID,
                "AUC-20261102-0001",
                SELLER_PROFILE_ID,
                201L,
                VARIANT_ID,
                CardCondition.NM,
                startingPrice,
                bidIncrement,
                new BigDecimal("5000.00"),
                currentPrice,
                bidCount > 0 ? 100L : null,
                bidCount,
                bidCount > 0 ? 1 : 0,
                NOW.minusHours(1),
                endsAt,
                endsAt,
                0,
                bidCount > 0 ? NOW.minusMinutes(30) : null,
                "Seller note",
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
                NOW.minusHours(1),
                NOW.minusHours(1));
    }

    private static Address sampleAddress(long userId) {
        return new Address(1L, userId, "Home", "User", "0812345678", "123 St", null, "Sub", "Dist", "BKK", "10110", "TH", true, false, NOW);
    }

    private void stubEligibleUser(long userId) {
        given(addresses.findByUserId(userId)).willReturn(List.of(sampleAddress(userId)));
        given(userVerification.isEmailVerified(userId)).willReturn(true);
        given(auctions.findActiveBanUntil(userId, NOW)).willReturn(Optional.empty());
        given(auctions.countActiveStrikes(userId)).willReturn(0);
        given(sellers.requireProfile(userId)).willThrow(new NotFoundException(ErrorCode.SELLER_NOT_FOUND, "Not a seller"));
    }

    @Nested
    @DisplayName("Pessimistic Locking & Auction Status")
    class LockingAndStatusTests {

        @Test
        @DisplayName("placeBid locks auction row with pessimistic lock")
        void placeBid_TakesPessimisticLock() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1000.00")), any())).willReturn(1L);

            service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null);

            verify(auctions).lock(AUCTION_ID);
        }

        @Test
        @DisplayName("placeBid throws NotFoundException when auction does not exist")
        void placeBid_AuctionNotFound_ThrowsException() {
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(ex -> assertThat(((NotFoundException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("placeBid throws ConflictException(AUCTION_CLOSED) when auction is not LIVE")
        void placeBid_NotLive_ThrowsAuctionClosed() {
            Auction auction = sampleAuction(AuctionStatus.CANCELLED, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_CLOSED));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("placeBid throws ConflictException(AUCTION_CLOSED) when auction has expired (now >= endsAt)")
        void placeBid_Expired_ThrowsAuctionClosed() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.minusSeconds(1));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_CLOSED));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("Bidder Eligibility Validation (5 Cases)")
    class EligibilityValidationTests {

        private Auction liveAuction;

        @BeforeEach
        void initLiveAuction() {
            liveAuction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(liveAuction));
        }

        @Test
        @DisplayName("Eligibility Case 1: Throws BadRequestException(NO_SHIPPING_ADDRESS) when buyer has no address")
        void eligibility_NoAddress_ThrowsBadRequest() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of());

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(ex -> assertThat(((BadRequestException) ex).errorCode()).isEqualTo(ErrorCode.NO_SHIPPING_ADDRESS));
        }

        @Test
        @DisplayName("Eligibility Case 2: Throws ForbiddenException(EMAIL_NOT_VERIFIED) when email is not verified")
        void eligibility_EmailNotVerified_ThrowsForbidden() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(sampleAddress(USER_ID)));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
        }

        @Test
        @DisplayName("Eligibility Case 3a: Throws ForbiddenException(BIDDING_BANNED) with bannedUntil when active ban exists")
        void eligibility_ActiveBan_ThrowsForbidden() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(sampleAddress(USER_ID)));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.of(NOW.plusDays(7)));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> {
                        assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.BIDDING_BANNED);
                        assertThat(((ForbiddenException) ex).details()).containsKey("bannedUntil");
                    });
        }

        @Test
        @DisplayName("Eligibility Case 3b: Throws ForbiddenException(BIDDING_BANNED) when user has >= 3 strikes")
        void eligibility_StrikesBan_ThrowsForbidden() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(sampleAddress(USER_ID)));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(USER_ID)).willReturn(3);

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.BIDDING_BANNED));
        }

        @Test
        @DisplayName("Eligibility Case 4: Throws ForbiddenException(OWN_AUCTION) when caller is the auction seller")
        void eligibility_OwnAuction_ThrowsForbidden() {
            given(addresses.findByUserId(SELLER_USER_ID)).willReturn(List.of(sampleAddress(SELLER_USER_ID)));
            given(userVerification.isEmailVerified(SELLER_USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(SELLER_USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(SELLER_USER_ID)).willReturn(0);

            SellerProfile sellerProfile = new SellerProfile(SELLER_PROFILE_ID, SELLER_USER_ID, SellerStatus.VERIFIED, NOW, null, (short) 1, false, true, NOW);
            given(sellers.requireProfile(SELLER_USER_ID)).willReturn(sellerProfile);

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, SELLER_USER_ID, new BigDecimal("1000.00"), null))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.OWN_AUCTION));
        }
    }

    @Nested
    @DisplayName("Bid Price Rules")
    class PriceRuleTests {

        @Test
        @DisplayName("First bid < startingPrice throws ConflictException(BID_TOO_LOW)")
        void firstBid_BelowStartingPrice_ThrowsBidTooLow() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("999.00"), null))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> {
                        assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.BID_TOO_LOW);
                        assertThat(((ConflictException) ex).details()).containsEntry("minNextBid", new BigDecimal("1000.00"));
                        assertThat(((ConflictException) ex).details()).containsEntry("currentPrice", new BigDecimal("1000.00"));
                    });
        }

        @Test
        @DisplayName("First bid == startingPrice succeeds and records first bid")
        void firstBid_AtStartingPrice_Succeeds() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(false);
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1000.00")), any())).willReturn(101L);

            BidPlaced result = service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), null);

            assertThat(result.amount()).isEqualByComparingTo("1000.00");
            assertThat(result.currentPrice()).isEqualByComparingTo("1000.00");
            assertThat(result.minNextBid()).isEqualByComparingTo("1050.00");
            assertThat(result.bidCount()).isEqualTo(1);
            assertThat(result.leading()).isTrue();
            assertThat(result.extended()).isFalse();

            verify(auctions).recordBid(eq(AUCTION_ID), eq(101L), eq(new BigDecimal("1000.00")), eq(true), eq(NOW), eq(auction.endsAt()), eq(false), eq(NOW));
        }

        @Test
        @DisplayName("Subsequent bid < currentPrice + increment throws ConflictException(BID_TOO_LOW)")
        void subsequentBid_BelowCurrentPlusIncrement_ThrowsBidTooLow() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1200.00"), 3, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            // current = 1200, increment = 50 -> minNextBid = 1250. Trying 1240 throws BID_TOO_LOW
            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1240.00"), null))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> {
                        assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.BID_TOO_LOW);
                        assertThat(((ConflictException) ex).details()).containsEntry("minNextBid", new BigDecimal("1250.00"));
                        assertThat(((ConflictException) ex).details()).containsEntry("currentPrice", new BigDecimal("1200.00"));
                    });
        }

        @Test
        @DisplayName("Subsequent bid >= currentPrice + increment succeeds")
        void subsequentBid_AboveCurrentPlusIncrement_Succeeds() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1200.00"), 3, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(true);
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1300.00")), any())).willReturn(102L);

            BidPlaced result = service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1300.00"), null);

            assertThat(result.amount()).isEqualByComparingTo("1300.00");
            assertThat(result.currentPrice()).isEqualByComparingTo("1300.00");
            assertThat(result.minNextBid()).isEqualByComparingTo("1350.00");
            assertThat(result.bidCount()).isEqualTo(4);

            // isNewBidder is false because hasBid was true, firstBidAt is null
            verify(auctions).recordBid(eq(AUCTION_ID), eq(102L), eq(new BigDecimal("1300.00")), eq(false), eq(null), eq(auction.endsAt()), eq(false), eq(NOW));
        }
    }

    @Nested
    @DisplayName("Anti-Sniping Clock Extension (< 120s vs >= 120s)")
    class AntiSnipingTests {

        @Test
        void extensionReadsCurrentSetting() {
            service = new AuctionBidService(auctions, validator, FIXED_CLOCK, settings);
            given(settings.getSeconds(PlatformSettingService.AUCTION_EXTENSION_SECONDS))
                    .willReturn(java.time.Duration.ofSeconds(30));
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"),
                    new BigDecimal("50.00"), new BigDecimal("1000.00"), 1, NOW.plusSeconds(20));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1050.00")), any()))
                    .willReturn(106L);

            assertThat(service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1050.00"), null).endsAt())
                    .isEqualTo(NOW.plusSeconds(30));
        }

        @Test
        @DisplayName("Anti-sniping: remaining time < 120s (119s) triggers 120s extension")
        void antiSniping_Under120Seconds_ExtendsAuction() {
            stubEligibleUser(USER_ID);
            OffsetDateTime endsAt119s = NOW.plusSeconds(119);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 1, endsAt119s);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(false);
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1050.00")), any())).willReturn(103L);

            BidPlaced result = service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1050.00"), null);

            assertThat(result.extended()).isTrue();
            OffsetDateTime expectedEndsAt = NOW.plusSeconds(120);
            assertThat(result.endsAt()).isEqualTo(expectedEndsAt);

            verify(auctions).recordBid(eq(AUCTION_ID), eq(103L), eq(new BigDecimal("1050.00")), eq(true), eq(null), eq(expectedEndsAt), eq(true), eq(NOW));
        }

        @Test
        @DisplayName("Anti-sniping: remaining time >= 120s (exactly 120s) does NOT trigger extension")
        void antiSniping_Exactly120Seconds_DoesNotExtend() {
            stubEligibleUser(USER_ID);
            OffsetDateTime endsAt120s = NOW.plusSeconds(120);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 1, endsAt120s);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(false);
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1050.00")), any())).willReturn(104L);

            BidPlaced result = service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1050.00"), null);

            assertThat(result.extended()).isFalse();
            assertThat(result.endsAt()).isEqualTo(endsAt120s);

            verify(auctions).recordBid(eq(AUCTION_ID), eq(104L), eq(new BigDecimal("1050.00")), eq(true), eq(null), eq(endsAt120s), eq(false), eq(NOW));
        }

        @Test
        @DisplayName("Anti-sniping: remaining time > 120s (121s) does NOT trigger extension")
        void antiSniping_Over120Seconds_DoesNotExtend() {
            stubEligibleUser(USER_ID);
            OffsetDateTime endsAt121s = NOW.plusSeconds(121);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 1, endsAt121s);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(false);
            given(auctions.insertBid(eq(AUCTION_ID), eq(USER_ID), eq(new BigDecimal("1050.00")), any())).willReturn(105L);

            BidPlaced result = service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1050.00"), null);

            assertThat(result.extended()).isFalse();
            assertThat(result.endsAt()).isEqualTo(endsAt121s);

            verify(auctions).recordBid(eq(AUCTION_ID), eq(105L), eq(new BigDecimal("1050.00")), eq(true), eq(null), eq(endsAt121s), eq(false), eq(NOW));
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        private static final String IDEM_KEY = "test-auction-idem-key-001"; // gitleaks:allow

        @Test
        @DisplayName("Replaying with same idempotency key and amount returns existing bid without inserting new one")
        void idempotency_SameKeyAndAmount_ReturnsExistingBid() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1200.00"), 2, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            AuctionBid existing = new AuctionBid(100L, AUCTION_ID, USER_ID, new BigDecimal("1200.00"), BidStatus.ACTIVE, IDEM_KEY, NOW.minusMinutes(1));
            given(auctions.findBidByIdempotencyKey(AUCTION_ID, IDEM_KEY)).willReturn(Optional.of(existing));

            BidPlaced result = service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1200.00"), IDEM_KEY);

            assertThat(result.amount()).isEqualByComparingTo("1200.00");
            assertThat(result.leading()).isTrue(); // auction.leadingBidId() is 100L == existing.id()
            assertThat(result.extended()).isFalse();

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
            verify(auctions, never()).recordBid(anyLong(), anyLong(), any(), anyBoolean(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("Replaying with same idempotency key but different amount throws ConflictException(IDEMPOTENCY_KEY_CONFLICT)")
        void idempotency_SameKeyDifferentAmount_ThrowsConflict() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1200.00"), 2, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            AuctionBid existing = new AuctionBid(100L, AUCTION_ID, USER_ID, new BigDecimal("1200.00"), BidStatus.ACTIVE, IDEM_KEY, NOW.minusMinutes(1));
            given(auctions.findBidByIdempotencyKey(AUCTION_ID, IDEM_KEY)).willReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1300.00"), IDEM_KEY))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("Replaying with same idempotency key by different user throws ConflictException(IDEMPOTENCY_KEY_CONFLICT)")
        void idempotency_SameKeyDifferentUser_ThrowsConflict() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1200.00"), 2, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            AuctionBid existing = new AuctionBid(100L, AUCTION_ID, 999L, new BigDecimal("1200.00"), BidStatus.ACTIVE, IDEM_KEY, NOW.minusMinutes(1));
            given(auctions.findBidByIdempotencyKey(AUCTION_ID, IDEM_KEY)).willReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1200.00"), IDEM_KEY))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("Concurrent race condition causing DuplicateKeyException throws ConflictException(IDEMPOTENCY_KEY_CONFLICT)")
        void idempotency_ConcurrentDuplicateKeyException_ReturnsExistingBid() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 0, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            // First lookup finds nothing
            given(auctions.findBidByIdempotencyKey(AUCTION_ID, IDEM_KEY))
                    .willReturn(Optional.empty());

            given(auctions.insertBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), IDEM_KEY))
                    .willThrow(new DuplicateKeyException("duplicate key value violates unique constraint"));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1000.00"), IDEM_KEY))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        }

        @Test
        @DisplayName("placeBid throws ConflictException(ALREADY_LEADING) when caller is already the highest bidder")
        void placeBid_WhenAlreadyLeading_ThrowsConflict() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1050.00"), 1, NOW.plusHours(2));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            stubEligibleUser(USER_ID);

            AuctionBid leadingBid = new AuctionBid(100L, AUCTION_ID, USER_ID, new BigDecimal("1050.00"), BidStatus.ACTIVE, "k1", NOW.minusMinutes(10));
            given(auctions.findBidById(100L)).willReturn(Optional.of(leadingBid));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1100.00"), null))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.ALREADY_LEADING));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("placeBid reports AUCTION_CLOSED, not ALREADY_LEADING, when the leader bids after the end")
        void placeBid_WhenLeaderBidsAfterEnd_ThrowsClosed() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1050.00"), 1, NOW.minusSeconds(1));
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.placeBid(AUCTION_ID, USER_ID, new BigDecimal("1100.00"), null))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_CLOSED));

            verify(auctions, never()).insertBid(anyLong(), anyLong(), any(), any());
        }
    }
}
