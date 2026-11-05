package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionWin;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.user.AddressRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionBuyNowService;
import com.pegasus.pegasustcgapi.service.auction.AuctionEligibilityValidator;
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

@ExtendWith(MockitoExtension.class)
class AuctionBuyNowServiceTest {

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

    private AuctionEligibilityValidator validator;
    private AuctionBuyNowService service;

    @BeforeEach
    void setUp() {
        validator = new AuctionEligibilityValidator(addresses, userVerification, auctions, sellers);
        service = new AuctionBuyNowService(auctions, validator, FIXED_CLOCK);
    }

    private Auction sampleAuction(
            AuctionStatus status,
            BigDecimal buyNowPrice,
            int bidCount,
            OffsetDateTime endsAt,
            Long winnerId,
            BigDecimal winningAmount,
            OffsetDateTime paymentDueAt) {
        return new Auction(
                AUCTION_ID,
                "AUC-20261102-0001",
                SELLER_PROFILE_ID,
                201L,
                VARIANT_ID,
                CardCondition.NM,
                new BigDecimal("1000.00"),
                new BigDecimal("50.00"),
                buyNowPrice,
                new BigDecimal("1000.00"),
                null,
                bidCount,
                bidCount > 0 ? 1 : 0,
                NOW.minusHours(1),
                endsAt,
                endsAt,
                0,
                bidCount > 0 ? NOW.minusMinutes(30) : null,
                "Seller note",
                null,
                winnerId,
                winningAmount,
                status == AuctionStatus.AWAITING_PAYMENT ? NOW.minusMinutes(5) : null,
                paymentDueAt,
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
    @DisplayName("Happy Path")
    class HappyPathTests {

        @Test
        @DisplayName("buyNow successfully transitions auction to AWAITING_PAYMENT and returns AuctionWin")
        void buyNow_Success() {
            stubEligibleUser(USER_ID);
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("3500.00"), 0, NOW.plusHours(24), null, null, null);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));
            OffsetDateTime expectedPaymentDueAt = NOW.plusHours(24);
            given(auctions.recordBuyNowWin(AUCTION_ID, USER_ID, new BigDecimal("3500.00"), NOW, expectedPaymentDueAt)).willReturn(true);

            AuctionWin win = service.buyNow(AUCTION_ID, USER_ID);

            assertThat(win.auctionId()).isEqualTo(AUCTION_ID);
            assertThat(win.auctionNumber()).isEqualTo("AUC-20261102-0001");
            assertThat(win.price()).isEqualByComparingTo("3500.00");
            assertThat(win.shippingFee()).isEqualByComparingTo("50.00");
            assertThat(win.total()).isEqualByComparingTo("3550.00");
            assertThat(win.paymentDueAt()).isEqualTo(expectedPaymentDueAt);
            assertThat(win.serverTime()).isEqualTo(NOW);

            verify(auctions).recordBuyNowWin(AUCTION_ID, USER_ID, new BigDecimal("3500.00"), NOW, expectedPaymentDueAt);
        }
    }

    @Nested
    @DisplayName("Precondition Rules & Errors")
    class PreconditionErrorTests {

        @Test
        @DisplayName("Auction not found throws NotFoundException(AUCTION_NOT_FOUND)")
        void buyNow_AuctionNotFound() {
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(ex -> assertThat(((NotFoundException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND));

            verify(auctions, never()).recordBuyNowWin(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Expired auction (now >= endsAt) throws ConflictException(AUCTION_CLOSED)")
        void buyNow_Expired_ThrowsAuctionClosed() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("3500.00"), 0, NOW.minusSeconds(1), null, null, null);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_CLOSED));
        }

        @Test
        @DisplayName("Auction not LIVE throws ConflictException(BUY_NOW_UNAVAILABLE)")
        void buyNow_NotLive_ThrowsBuyNowUnavailable() {
            Auction auction = sampleAuction(AuctionStatus.CANCELLED, new BigDecimal("3500.00"), 0, NOW.plusHours(5), null, null, null);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.BUY_NOW_UNAVAILABLE));
        }

        @Test
        @DisplayName("Auction with no buy-now price configured throws ConflictException(BUY_NOW_UNAVAILABLE)")
        void buyNow_NoBuyNowPrice_ThrowsBuyNowUnavailable() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, null, 0, NOW.plusHours(5), null, null, null);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.BUY_NOW_UNAVAILABLE));
        }

        @Test
        @DisplayName("Auction with bids (bid_count > 0) throws ConflictException(BUY_NOW_UNAVAILABLE)")
        void buyNow_HasBids_ThrowsBuyNowUnavailable() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("3500.00"), 1, NOW.plusHours(5), null, null, null);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.BUY_NOW_UNAVAILABLE));
        }
    }

    @Nested
    @DisplayName("Eligibility Checks")
    class EligibilityTests {

        private Auction liveAuction;

        @BeforeEach
        void initAuction() {
            liveAuction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("3500.00"), 0, NOW.plusHours(5), null, null, null);
            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(liveAuction));
        }

        @Test
        @DisplayName("No shipping address throws BadRequestException(NO_SHIPPING_ADDRESS)")
        void eligibility_NoAddress() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of());

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(ex -> assertThat(((BadRequestException) ex).errorCode()).isEqualTo(ErrorCode.NO_SHIPPING_ADDRESS));
        }

        @Test
        @DisplayName("Unverified email throws ForbiddenException(EMAIL_NOT_VERIFIED)")
        void eligibility_EmailNotVerified() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(sampleAddress(USER_ID)));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
        }

        @Test
        @DisplayName("Active ban throws ForbiddenException(BIDDING_BANNED)")
        void eligibility_ActiveBan() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(sampleAddress(USER_ID)));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.of(NOW.plusDays(3)));

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.BIDDING_BANNED));
        }

        @Test
        @DisplayName("Caller is the auction seller throws ForbiddenException(OWN_AUCTION)")
        void eligibility_OwnAuction() {
            given(addresses.findByUserId(SELLER_USER_ID)).willReturn(List.of(sampleAddress(SELLER_USER_ID)));
            given(userVerification.isEmailVerified(SELLER_USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(SELLER_USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(SELLER_USER_ID)).willReturn(0);

            SellerProfile profile = new SellerProfile(SELLER_PROFILE_ID, SELLER_USER_ID, SellerStatus.VERIFIED, NOW, null, (short) 1, false, true, NOW);
            given(sellers.requireProfile(SELLER_USER_ID)).willReturn(profile);

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, SELLER_USER_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(ex -> assertThat(((ForbiddenException) ex).errorCode()).isEqualTo(ErrorCode.OWN_AUCTION));
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("Repeated buyNow call by same winner returns existing win without calling update")
        void idempotency_AlreadyWonByCaller_ReturnsWin() {
            OffsetDateTime paymentDueAt = NOW.plusHours(20);
            Auction wonAuction = sampleAuction(
                    AuctionStatus.AWAITING_PAYMENT,
                    new BigDecimal("3500.00"),
                    0,
                    NOW.plusHours(5),
                    USER_ID,
                    new BigDecimal("3500.00"),
                    paymentDueAt);

            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(wonAuction));

            AuctionWin win = service.buyNow(AUCTION_ID, USER_ID);

            assertThat(win.auctionId()).isEqualTo(AUCTION_ID);
            assertThat(win.price()).isEqualByComparingTo("3500.00");
            assertThat(win.total()).isEqualByComparingTo("3550.00");
            assertThat(win.paymentDueAt()).isEqualTo(paymentDueAt);

            verify(auctions, never()).recordBuyNowWin(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("buyNow by different user when already won throws ConflictException(BUY_NOW_UNAVAILABLE)")
        void idempotency_AlreadyWonByDifferentUser_ThrowsUnavailable() {
            OffsetDateTime paymentDueAt = NOW.plusHours(20);
            Auction wonAuction = sampleAuction(
                    AuctionStatus.AWAITING_PAYMENT,
                    new BigDecimal("3500.00"),
                    0,
                    NOW.plusHours(5),
                    888L,
                    new BigDecimal("3500.00"),
                    paymentDueAt);

            given(auctions.lock(AUCTION_ID)).willReturn(Optional.of(wonAuction));

            assertThatThrownBy(() -> service.buyNow(AUCTION_ID, USER_ID))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(ErrorCode.BUY_NOW_UNAVAILABLE));
        }
    }
}
