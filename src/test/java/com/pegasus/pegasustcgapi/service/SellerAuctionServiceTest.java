package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.CreateAuction;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.UpdateAuction;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.SellerAuctionDetail;
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingUnit;
import com.pegasus.pegasustcgapi.model.listing.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository.NewAuction;
import com.pegasus.pegasustcgapi.repository.catalog.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.catalog.GameRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.service.auction.SellerAuctionService;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import com.pegasus.pegasustcgapi.service.listing.ListingCardRenderer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerAuctionServiceTest {

    private static final long USER_ID = 42L;
    private static final long SELLER_ID = 101L;
    private static final long AUCTION_ID = 501L;
    private static final long UNIT_ID = 201L;
    private static final UUID UNIT_UID = UUID.fromString("0f9e1c2a-6b1d-4e8a-9a51-2c7d5b3e1042");
    private static final long VARIANT_ID = 301L;

    private static final Instant BASE_INSTANT = Instant.parse("2026-11-02T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(BASE_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED_CLOCK);

    @Mock
    private AuctionRepository auctions;

    @Mock
    private ListingUnitRepository units;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private SellerPort sellers;

    @Mock
    private LedgerPort ledgerPort;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private CatalogProductRepository products;

    @Mock
    private GameRepository games;

    @Mock
    private CardSetRepository cardSets;

    @Mock
    private ListingCardRenderer cards;

    @Mock
    private DSLContext dsl;

    private SellerAuctionService service;

    private SellerProfile verifiedSeller;

    @BeforeEach
    void setUp() {
        service = new SellerAuctionService(
                auctions,
                units,
                inventoryService,
                sellers,
                ledgerPort,
                variants,
                products,
                games,
                cardSets,
                cards,
                dsl,
                FIXED_CLOCK);

        verifiedSeller = new SellerProfile(
                SELLER_ID,
                USER_ID,
                SellerStatus.VERIFIED,
                NOW,
                null,
                (short) 1,
                false,
                true,
                NOW);
    }

    private static ListingUnit sampleUnit(ListingUnitStatus status, Long listingId) {
        return new ListingUnit(
                UNIT_ID,
                UNIT_UID,
                SELLER_ID,
                VARIANT_ID,
                CardCondition.NM,
                listingId,
                status,
                null,
                new BigDecimal("500.00"),
                NOW,
                null,
                null,
                NOW,
                NOW);
    }

    private static Auction sampleAuction(AuctionStatus status, int bidCount, BigDecimal startPrice, BigDecimal buyNow) {
        return new Auction(
                AUCTION_ID,
                "AUC-2026-0001",
                SELLER_ID,
                UNIT_ID,
                VARIANT_ID,
                CardCondition.NM,
                startPrice,
                new BigDecimal("50.00"),
                buyNow,
                startPrice,
                null,
                bidCount,
                0,
                NOW,
                NOW.plusDays(3),
                NOW.plusDays(3),
                0,
                null,
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
                0,
                NOW,
                NOW);
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((ApiException) e).errorCode();
    }

    // =========================================================================
    // Rule 1: Seller Verification
    // =========================================================================
    @Nested
    @DisplayName("Rule 1: Seller Verification")
    class SellerVerificationTests {

        @Test
        @DisplayName("rejects request with SELLER_NOT_VERIFIED when profile does not exist")
        void rejectsWhenProfileNotFound() {
            given(sellers.requireProfile(USER_ID)).willThrow(new NotFoundException(ErrorCode.SELLER_NOT_FOUND));

            assertThatThrownBy(() -> service.get(USER_ID, AUCTION_ID))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.SELLER_NOT_VERIFIED);
        }

        @Test
        @DisplayName("rejects request with SELLER_NOT_VERIFIED when seller profile status is not VERIFIED")
        void rejectsWhenProfileNotVerified() {
            SellerProfile pendingSeller = new SellerProfile(
                    SELLER_ID,
                    USER_ID,
                    SellerStatus.PENDING,
                    null,
                    null,
                    (short) 1,
                    false,
                    true,
                    NOW);
            given(sellers.requireProfile(USER_ID)).willReturn(pendingSeller);

            assertThatThrownBy(() -> service.get(USER_ID, AUCTION_ID))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.SELLER_NOT_VERIFIED);
        }
    }

    // =========================================================================
    // Rule 3: Create Auction Validations & Execution
    // =========================================================================
    @Nested
    @DisplayName("Rule 3: Create Auction Validations & Execution")
    class CreateAuctionTests {

        @BeforeEach
        void initSeller() {
            given(sellers.requireProfile(USER_ID)).willReturn(verifiedSeller);
        }

        @Test
        @DisplayName("rejects auction when endsAt is less than 1 hour away (AUCTION_END_TOO_SOON)")
        void rejectsEndsAtTooSoon() {
            OffsetDateTime tooSoon = NOW.plusMinutes(59);
            CreateAuction request = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                    tooSoon, new BigDecimal("1500.00"), "Note");

            assertThatThrownBy(() -> service.create(USER_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_END_TOO_SOON);

            verify(auctions, never()).insert(any());
        }

        @Test
        @DisplayName("rejects auction when endsAt is more than 30 days away (AUCTION_END_TOO_FAR)")
        void rejectsEndsAtTooFar() {
            OffsetDateTime tooFar = NOW.plusDays(30).plusMinutes(1);
            CreateAuction request = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                    tooFar, new BigDecimal("1500.00"), "Note");

            assertThatThrownBy(() -> service.create(USER_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_END_TOO_FAR);

            verify(auctions, never()).insert(any());
        }

        @Test
        @DisplayName("rejects auction when buyNowPrice <= startingPrice (BUY_NOW_BELOW_START)")
        void rejectsBuyNowBelowOrEqualStart() {
            OffsetDateTime validEndsAt = NOW.plusDays(3);

            // Equal price
            CreateAuction requestEqual = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                    validEndsAt, new BigDecimal("1000.00"), "Note");

            assertThatThrownBy(() -> service.create(USER_ID, requestEqual))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.BUY_NOW_BELOW_START);

            // Lower price
            CreateAuction requestLower = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                    validEndsAt, new BigDecimal("900.00"), "Note");

            assertThatThrownBy(() -> service.create(USER_ID, requestLower))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.BUY_NOW_BELOW_START);

            verify(auctions, never()).insert(any());
        }

        @Test
        @DisplayName("rejects auction when bid increment is <= 0")
        void rejectsInvalidBidIncrement() {
            OffsetDateTime validEndsAt = NOW.plusDays(3);
            CreateAuction request = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), BigDecimal.ZERO,
                    validEndsAt, new BigDecimal("1500.00"), "Note");

            assertThatThrownBy(() -> service.create(USER_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);

            verify(auctions, never()).insert(any());
        }

        @Test
        @DisplayName("rejects auction when target card unit is ON_AUCTION or RESERVED (UNIT_NOT_AVAILABLE)")
        void rejectsUnitNotAvailable() {
            OffsetDateTime validEndsAt = NOW.plusDays(3);
            CreateAuction request = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                    validEndsAt, new BigDecimal("1500.00"), "Note");

            // Case 1: Already ON_AUCTION
            given(units.lockOfSeller(UNIT_UID, SELLER_ID)).willReturn(Optional.of(sampleUnit(ListingUnitStatus.ON_AUCTION, null)));
            assertThatThrownBy(() -> service.create(USER_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.UNIT_NOT_AVAILABLE);

            // Case 2: RESERVED by order
            given(units.lockOfSeller(UNIT_UID, SELLER_ID)).willReturn(Optional.of(sampleUnit(ListingUnitStatus.RESERVED, 12L)));
            assertThatThrownBy(() -> service.create(USER_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.UNIT_NOT_AVAILABLE);

            verify(auctions, never()).insert(any());
        }

        @Test
        @DisplayName("successfully delists LISTED card, updates status to ON_AUCTION, records inventory movement, and inserts LIVE auction")
        void createsAuctionFromListedCard() {
            OffsetDateTime validEndsAt = NOW.plusDays(3);
            CreateAuction request = new CreateAuction(
                    UNIT_UID, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                    validEndsAt, new BigDecimal("1800.00"), "Note");

            ListingUnit listedUnit = sampleUnit(ListingUnitStatus.LISTED, 888L);
            given(units.lockOfSeller(UNIT_UID, SELLER_ID)).willReturn(Optional.of(listedUnit));
            given(auctions.nextAuctionNumber()).willReturn("AUC-2026-0001");
            given(auctions.insert(any(NewAuction.class))).willReturn(AUCTION_ID);

            Auction createdAuction = sampleAuction(AuctionStatus.LIVE, 0, new BigDecimal("1000.00"), new BigDecimal("1800.00"));
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.of(createdAuction));
            given(cards.card(VARIANT_ID)).willReturn(new ListingCard(VARIANT_ID, "PKM-1", "NM", true, VARIANT_ID, "Card Name", "card-slug", (short) 1, null));
            given(ledgerPort.quoteCommission(SELLER_ID, new BigDecimal("1000.00")))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), new BigDecimal("50.00")));

            SellerAuctionDetail result = service.create(USER_ID, request);

            verify(units).moveToAuction(UNIT_ID);
            verify(inventoryService).recordAuctionTransfer(listedUnit, AUCTION_ID, USER_ID);
            verify(auctions).insert(any(NewAuction.class));
            assertThat(result.id()).isEqualTo(AUCTION_ID);
            assertThat(result.status()).isEqualTo(AuctionStatus.LIVE);
            assertThat(result.estimatedPayout()).isEqualByComparingTo("950.00");
        }
    }

    // =========================================================================
    // Rule 4: Edit Auction Settings
    // =========================================================================
    @Nested
    @DisplayName("Rule 4: Edit Auction Settings")
    class EditAuctionTests {

        @BeforeEach
        void initSeller() {
            given(sellers.requireProfile(USER_ID)).willReturn(verifiedSeller);
        }

        @Test
        @DisplayName("rejects update when auction already has bids (AUCTION_HAS_BIDS)")
        void rejectsUpdateWithBids() {
            Auction auctionWithBids = sampleAuction(AuctionStatus.LIVE, 1, new BigDecimal("1000.00"), new BigDecimal("2000.00"));
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.of(auctionWithBids));

            UpdateAuction request = new UpdateAuction(new BigDecimal("2200.00"), "New note");

            assertThatThrownBy(() -> service.update(USER_ID, AUCTION_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_HAS_BIDS);

            verify(auctions, never()).updateSettings(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("rejects update when auction is not LIVE (AUCTION_CLOSED)")
        void rejectsUpdateWhenNotLive() {
            Auction closedAuction = sampleAuction(AuctionStatus.SOLD, 0, new BigDecimal("1000.00"), new BigDecimal("2000.00"));
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.of(closedAuction));

            UpdateAuction request = new UpdateAuction(new BigDecimal("2200.00"), "New note");

            assertThatThrownBy(() -> service.update(USER_ID, AUCTION_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_CLOSED);

            verify(auctions, never()).updateSettings(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("rejects update when buyNowPrice <= startingPrice (BUY_NOW_BELOW_START)")
        void rejectsUpdateBuyNowBelowStart() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, 0, new BigDecimal("1000.00"), new BigDecimal("2000.00"));
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.of(auction));

            UpdateAuction request = new UpdateAuction(new BigDecimal("999.00"), "New note");

            assertThatThrownBy(() -> service.update(USER_ID, AUCTION_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.BUY_NOW_BELOW_START);

            verify(auctions, never()).updateSettings(anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("updates buyNowPrice and sellerNote successfully when bidCount == 0")
        void updatesAuctionSuccessfully() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, 0, new BigDecimal("1000.00"), new BigDecimal("2000.00"));
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.of(auction));

            Auction updatedAuction = sampleAuction(AuctionStatus.LIVE, 0, new BigDecimal("1000.00"), new BigDecimal("2500.00"));
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.of(updatedAuction));
            given(cards.card(VARIANT_ID)).willReturn(new ListingCard(VARIANT_ID, "PKM-1", "NM", true, VARIANT_ID, "Card Name", "card-slug", (short) 1, null));
            given(ledgerPort.quoteCommission(SELLER_ID, new BigDecimal("1000.00")))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), new BigDecimal("50.00")));

            given(auctions.updateSettings(eq(AUCTION_ID), eq(SELLER_ID), any(), any(), any())).willReturn(true);

            UpdateAuction request = new UpdateAuction(new BigDecimal("2500.00"), "New note");
            SellerAuctionDetail result = service.update(USER_ID, AUCTION_ID, request);

            verify(auctions).updateSettings(eq(AUCTION_ID), eq(SELLER_ID), eq(new BigDecimal("2500.00")), eq("New note"), any());
            assertThat(result.buyNowPrice()).isEqualByComparingTo("2500.00");
        }
    }

    // =========================================================================
    // Rule 5: Cancel Auction
    // =========================================================================
    @Nested
    @DisplayName("Rule 5: Cancel Auction")
    class CancelAuctionTests {

        @BeforeEach
        void initSeller() {
            given(sellers.requireProfile(USER_ID)).willReturn(verifiedSeller);
        }

        @Test
        @DisplayName("rejects cancel when auction already has bids (AUCTION_HAS_BIDS)")
        void rejectsCancelWithBids() {
            Auction auctionWithBids = sampleAuction(AuctionStatus.LIVE, 3, new BigDecimal("1000.00"), null);
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.of(auctionWithBids));

            assertThatThrownBy(() -> service.cancel(USER_ID, AUCTION_ID))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_HAS_BIDS);

            verify(auctions, never()).cancel(anyLong(), anyLong(), any());
            verify(units, never()).revertFromAuction(anyLong());
        }

        @Test
        @DisplayName("cancels auction when bidCount == 0, returns card to IN_STOCK, and records inventory movement")
        void cancelsAuctionSuccessfully() {
            Auction auctionWithoutBids = sampleAuction(AuctionStatus.LIVE, 0, new BigDecimal("1000.00"), null);
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.of(auctionWithoutBids));

            ListingUnit unitOnAuction = sampleUnit(ListingUnitStatus.ON_AUCTION, null);
            given(units.findOfSeller(UNIT_ID, SELLER_ID)).willReturn(Optional.of(unitOnAuction));

            Auction cancelledAuction = sampleAuction(AuctionStatus.CANCELLED, 0, new BigDecimal("1000.00"), null);
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.of(cancelledAuction));
            given(cards.card(VARIANT_ID)).willReturn(new ListingCard(VARIANT_ID, "PKM-1", "NM", true, VARIANT_ID, "Card Name", "card-slug", (short) 1, null));
            given(ledgerPort.quoteCommission(SELLER_ID, new BigDecimal("1000.00")))
                    .willReturn(new CommissionQuote(new BigDecimal("5.0"), new BigDecimal("50.00")));

            given(auctions.cancel(eq(AUCTION_ID), eq(SELLER_ID), any())).willReturn(true);

            SellerAuctionDetail result = service.cancel(USER_ID, AUCTION_ID);

            verify(auctions).cancel(eq(AUCTION_ID), eq(SELLER_ID), any());
            verify(units).revertFromAuction(UNIT_ID);
            verify(inventoryService).recordAuctionCancellation(unitOnAuction, AUCTION_ID, USER_ID);
            assertThat(result.status()).isEqualTo(AuctionStatus.CANCELLED);
        }
    }

    // =========================================================================
    // Multi-tenant Security Check
    // =========================================================================
    @Nested
    @DisplayName("Multi-tenant Security Check")
    class MultiTenantSecurityTests {

        @BeforeEach
        void initSeller() {
            given(sellers.requireProfile(USER_ID)).willReturn(verifiedSeller);
        }

        @Test
        @DisplayName("accessing another seller's auction returns 404 AUCTION_NOT_FOUND on get")
        void getOtherSellerAuctionReturns404() {
            given(auctions.findByIdAndSeller(AUCTION_ID, SELLER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(USER_ID, AUCTION_ID))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }

        @Test
        @DisplayName("updating another seller's auction returns 404 AUCTION_NOT_FOUND")
        void updateOtherSellerAuctionReturns404() {
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.empty());

            UpdateAuction request = new UpdateAuction(new BigDecimal("2500.00"), "Note");

            assertThatThrownBy(() -> service.update(USER_ID, AUCTION_ID, request))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }

        @Test
        @DisplayName("cancelling another seller's auction returns 404 AUCTION_NOT_FOUND")
        void cancelOtherSellerAuctionReturns404() {
            given(auctions.lockById(AUCTION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(USER_ID, AUCTION_ID))
                    .extracting(SellerAuctionServiceTest::codeOf)
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }
    }
}
