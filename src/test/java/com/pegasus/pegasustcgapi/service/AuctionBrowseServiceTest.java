package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionPage;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Bid;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.BidHistory;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Filter;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Sort;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Viewer;
import com.pegasus.pegasustcgapi.dto.auction.BidEligibility;
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionViewerState;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository.PublicAuctionQuery;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository.SellerInfo;
import com.pegasus.pegasustcgapi.repository.catalog.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.catalog.GameRepository;
import com.pegasus.pegasustcgapi.repository.user.AddressRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionBrowseService;
import com.pegasus.pegasustcgapi.service.listing.ListingCardRenderer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuctionBrowseServiceTest {

    private static final long USER_ID = 42L;
    private static final long SELLER_USER_ID = 99L;
    private static final long SELLER_PROFILE_ID = 101L;
    private static final long AUCTION_ID = 501L;
    private static final long VARIANT_ID = 301L;
    private static final short GAME_ID = 1;

    private static final Instant BASE_INSTANT = Instant.parse("2026-11-02T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(BASE_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED_CLOCK);

    @Mock
    private AuctionRepository auctions;

    @Mock
    private AddressRepository addresses;

    @Mock
    private SellerPort sellers;

    @Mock
    private UserVerificationPort userVerification;

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

    private AuctionBrowseService service;

    @BeforeEach
    void setUp() {
        service = new AuctionBrowseService(
                auctions,
                addresses,
                sellers,
                userVerification,
                variants,
                products,
                games,
                cardSets,
                cards,
                FIXED_CLOCK);
    }

    private Auction sampleAuction(AuctionStatus status, BigDecimal currentPrice, int bidCount, BigDecimal buyNowPrice) {
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
                currentPrice,
                null,
                bidCount,
                bidCount > 0 ? 1 : 0,
                NOW.minusHours(1),
                NOW.plusDays(3),
                NOW.plusDays(3),
                0,
                bidCount > 0 ? NOW.minusMinutes(30) : null,
                "Seller note",
                NOW.minusHours(1),
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

    private void stubCardRenderer() {
        ListingCard card = new ListingCard(
                VARIANT_ID,
                "PKM-1",
                "NM",
                true,
                VARIANT_ID,
                "Charizard",
                "charizard",
                (short) 1,
                null);
        given(cards.card(VARIANT_ID)).willReturn(card);
        given(variants.findById(VARIANT_ID)).willReturn(Optional.empty());
    }

    /** List pages load every card in one batch. */
    private void stubCardRendererBatch() {
        ListingCard card = new ListingCard(
                VARIANT_ID,
                "PKM-1",
                "NM",
                true,
                VARIANT_ID,
                "Charizard",
                "charizard",
                (short) 1,
                null);
        given(cards.cards(Set.of(VARIANT_ID))).willReturn(Map.of(VARIANT_ID, card));
    }

    private static Address sampleAddress(long userId) {
        return new Address(1L, userId, "Home", "User", "0812345678", "123 St", null, "Sub", "Dist", "BKK", "10110", "TH", true, false, NOW);
    }

    @Nested
    @DisplayName("Catalog Listing & Filters")
    class CatalogListingTests {

        @Test
        @DisplayName("list() with filters and sort calls repository with correct query and returns summaries")
        void list_WithFiltersAndSort_ReturnsSummaries() {
            stubCardRendererBatch();
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), 0, new BigDecimal("3000.00"));
            given(auctions.findPublicCatalog(any(PublicAuctionQuery.class))).willReturn(List.of(auction));
            given(auctions.countPublicCatalog(any(PublicAuctionQuery.class))).willReturn(1L);
            given(auctions.findShopNames(Set.of(SELLER_PROFILE_ID))).willReturn(Map.of(SELLER_PROFILE_ID, "Pegasus Shop"));

            Map<Filter, Long> filterCounts = new EnumMap<>(Filter.class);
            filterCounts.put(Filter.ENDING_SOON, 5L);
            filterCounts.put(Filter.BUY_NOW, 2L);
            filterCounts.put(Filter.NO_BIDS, 3L);
            filterCounts.put(Filter.NEW, 1L);
            given(auctions.publicFilterCounts(any(OffsetDateTime.class))).willReturn(filterCounts);
            given(auctions.countLiveAuctions(any(OffsetDateTime.class))).willReturn(10L);

            AuctionPage page = service.list(Filter.BUY_NOW, GAME_ID, SELLER_PROFILE_ID, VARIANT_ID, "Charizard", Sort.ENDING_SOON, 0, 20);

            assertThat(page.page().items()).hasSize(1);
            assertThat(page.page().items().getFirst().auctionNumber()).isEqualTo("AUC-20261102-0001");
            assertThat(page.page().items().getFirst().shopName()).isEqualTo("Pegasus Shop");
            assertThat(page.page().items().getFirst().buyNowPrice()).isEqualByComparingTo("3000.00");
            assertThat(page.totalLive()).isEqualTo(10L);
            assertThat(page.filterCounts()).containsEntry(Filter.BUY_NOW, 2L);
        }

        @Test
        @DisplayName("list() when auction has bids disables buy-now price in summary")
        void list_WhenHasBids_BuyNowPriceIsNull() {
            stubCardRendererBatch();
            Auction auctionWithBids = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1050.00"), 1, new BigDecimal("2500.00"));
            given(auctions.findPublicCatalog(any(PublicAuctionQuery.class))).willReturn(List.of(auctionWithBids));
            given(auctions.countPublicCatalog(any(PublicAuctionQuery.class))).willReturn(1L);
            given(auctions.publicFilterCounts(any())).willReturn(Map.of());
            given(auctions.countLiveAuctions(any())).willReturn(1L);

            AuctionPage page = service.list(null, null, null, null, null, Sort.ENDING_SOON, 0, 20);

            assertThat(page.page().items().getFirst().buyNowPrice()).isNull();
            assertThat(page.page().items().getFirst().shopName()).isEqualTo("Shop");
        }
    }

    @Nested
    @DisplayName("Auction Detail")
    class AuctionDetailTests {

        @Test
        @DisplayName("get() throws NotFoundException when auction does not exist")
        void get_NotFound_ThrowsException() {
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(AUCTION_ID, USER_ID))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(ex -> assertThat(((NotFoundException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND));
        }

        @Test
        @DisplayName("get() returns detail for live auction with seller info and recent bids")
        void get_LiveAuction_ReturnsDetail() {
            stubCardRenderer();
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), 0, new BigDecimal("2000.00"));
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.of(auction));
            given(auctions.findSellerInfo(SELLER_PROFILE_ID)).willReturn(Optional.of(new SellerInfo("Card Castle", "castle_owner", new BigDecimal("4.9"))));
            given(auctions.getSellerCompletedSalesCount(SELLER_PROFILE_ID)).willReturn(45L);
            given(auctions.findBids(AUCTION_ID, 5, 0)).willReturn(List.of());

            AuctionDetail detail = service.get(AUCTION_ID, null);

            assertThat(detail.id()).isEqualTo(AUCTION_ID);
            assertThat(detail.status()).isEqualTo(AuctionStatus.LIVE);
            assertThat(detail.seller().shopName()).isEqualTo("Card Castle");
            assertThat(detail.seller().soldCount()).isEqualTo(45L);
            assertThat(detail.viewer()).isNull();
            assertThat(detail.buyNowAvailable()).isTrue();
        }

        @Test
        @DisplayName("get() allows viewing SUSPENDED auction")
        void get_SuspendedAuction_CanBeViewed() {
            stubCardRenderer();
            Auction suspended = sampleAuction(AuctionStatus.SUSPENDED, new BigDecimal("1000.00"), 0, null);
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.of(suspended));
            given(auctions.findSellerInfo(SELLER_PROFILE_ID)).willReturn(Optional.empty());
            given(auctions.getSellerCompletedSalesCount(SELLER_PROFILE_ID)).willReturn(0L);
            given(auctions.findBids(AUCTION_ID, 5, 0)).willReturn(List.of());

            AuctionDetail detail = service.get(AUCTION_ID, null);

            assertThat(detail.status()).isEqualTo(AuctionStatus.SUSPENDED);
        }
    }

    @Nested
    @DisplayName("Masked Bid History & Masking Logic")
    class BidHistoryTests {

        @Test
        @DisplayName("bids() throws NotFoundException when auction does not exist")
        void bids_NotFound_ThrowsException() {
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.bids(AUCTION_ID, 0, 20, USER_ID))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(ex -> assertThat(((NotFoundException) ex).errorCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND));
        }

        @Test
        @DisplayName("bids() returns masked aliases for other users and unmasked alias for caller")
        void bids_ReturnsMaskedAndUnmaskedBids() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1500.00"), 2, null);
            given(auctions.findById(AUCTION_ID)).willReturn(Optional.of(auction));

            AuctionBid bid1 = new AuctionBid(1L, AUCTION_ID, USER_ID, new BigDecimal("1500.00"), BidStatus.ACTIVE, "key1", NOW.minusMinutes(5));
            AuctionBid bid2 = new AuctionBid(2L, AUCTION_ID, 888L, new BigDecimal("1200.00"), BidStatus.ACTIVE, "key2", NOW.minusMinutes(10));
            given(auctions.findBids(AUCTION_ID, 20, 0)).willReturn(List.of(bid1, bid2));
            given(auctions.countBids(AUCTION_ID)).willReturn(2);
            given(auctions.findUsernames(Set.of(USER_ID, 888L))).willReturn(Map.of(
                    USER_ID, "natthawut",
                    888L, "somchai"));

            BidHistory history = service.bids(AUCTION_ID, 0, 20, USER_ID);

            assertThat(history.page().items()).hasSize(2);

            Bid myBid = history.page().items().get(0);
            assertThat(myBid.mine()).isTrue();
            assertThat(myBid.bidderAlias()).isEqualTo("natthawut");
            assertThat(myBid.bidderInitials()).isEqualTo("NT");

            Bid otherBid = history.page().items().get(1);
            assertThat(otherBid.mine()).isFalse();
            assertThat(otherBid.bidderAlias()).isEqualTo("s***i");
            assertThat(otherBid.bidderInitials()).isEqualTo("SI");
        }

        @Test
        @DisplayName("maskUsername tests edge cases")
        void maskUsername_EdgeCases() {
            assertThat(AuctionBrowseService.maskUsername("natthawut")).isEqualTo("n***t");
            assertThat(AuctionBrowseService.maskUsername("ab")).isEqualTo("ab***");
            assertThat(AuctionBrowseService.maskUsername("a")).isEqualTo("a***");
            assertThat(AuctionBrowseService.maskUsername(null)).isEqualTo("Anonymous");
            assertThat(AuctionBrowseService.maskUsername("   ")).isEqualTo("Anonymous");
        }

        @Test
        @DisplayName("bidderInitials tests edge cases")
        void bidderInitials_EdgeCases() {
            assertThat(AuctionBrowseService.bidderInitials("natthawut")).isEqualTo("NT");
            assertThat(AuctionBrowseService.bidderInitials("a")).isEqualTo("AA");
            assertThat(AuctionBrowseService.bidderInitials("")).isEqualTo("AN");
            assertThat(AuctionBrowseService.bidderInitials(null)).isEqualTo("AN");
        }
    }

    @Nested
    @DisplayName("Bid Eligibility Evaluation (5 Cases + Eligible)")
    class BidEligibilityTests {

        private Auction liveAuction;

        @BeforeEach
        void initLiveAuction() {
            liveAuction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), 0, null);
        }

        @Test
        @DisplayName("Case 1: UNAUTHENTICATED when userId is null")
        void eligibility_Unauthenticated() {
            BidEligibility result = service.evaluateBidEligibility(liveAuction, null, NOW);
            assertThat(result).isEqualTo(BidEligibility.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("Case 2: NO_SHIPPING_ADDRESS when user has no address")
        void eligibility_NoShippingAddress() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of());

            BidEligibility result = service.evaluateBidEligibility(liveAuction, USER_ID, NOW);
            assertThat(result).isEqualTo(BidEligibility.NO_SHIPPING_ADDRESS);
        }

        @Test
        @DisplayName("Case 3: EMAIL_NOT_VERIFIED when user email is unverified")
        void eligibility_EmailNotVerified() {
            Address address = sampleAddress(USER_ID);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(false);

            BidEligibility result = service.evaluateBidEligibility(liveAuction, USER_ID, NOW);
            assertThat(result).isEqualTo(BidEligibility.EMAIL_NOT_VERIFIED);
        }

        @Test
        @DisplayName("Case 4a: BIDDING_PENALIZED when user has active ban")
        void eligibility_BiddingBanned() {
            Address address = sampleAddress(USER_ID);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.of(NOW.plusDays(7)));

            BidEligibility result = service.evaluateBidEligibility(liveAuction, USER_ID, NOW);
            assertThat(result).isEqualTo(BidEligibility.BIDDING_PENALIZED);
        }

        @Test
        @DisplayName("Case 4b: BIDDING_PENALIZED when user has 3 or more active strikes")
        void eligibility_BiddingPenalizedStrikes() {
            Address address = sampleAddress(USER_ID);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(USER_ID)).willReturn(3);

            BidEligibility result = service.evaluateBidEligibility(liveAuction, USER_ID, NOW);
            assertThat(result).isEqualTo(BidEligibility.BIDDING_PENALIZED);
        }

        @Test
        @DisplayName("Case 5: OWN_AUCTION when user is the seller")
        void eligibility_OwnAuction() {
            Address address = sampleAddress(SELLER_USER_ID);
            given(addresses.findByUserId(SELLER_USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(SELLER_USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(SELLER_USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(SELLER_USER_ID)).willReturn(0);

            SellerProfile sellerProfile = new SellerProfile(SELLER_PROFILE_ID, SELLER_USER_ID, SellerStatus.VERIFIED, NOW, null, (short) 1, false, true, NOW);
            given(sellers.requireProfile(SELLER_USER_ID)).willReturn(sellerProfile);

            BidEligibility result = service.evaluateBidEligibility(liveAuction, SELLER_USER_ID, NOW);
            assertThat(result).isEqualTo(BidEligibility.OWN_AUCTION);
        }

        @Test
        @DisplayName("Case 6: ELIGIBLE when all conditions pass")
        void eligibility_Eligible() {
            Address address = sampleAddress(USER_ID);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(USER_ID)).willReturn(0);
            given(sellers.requireProfile(USER_ID)).willThrow(new NotFoundException(ErrorCode.SELLER_NOT_FOUND, "Not a seller"));

            BidEligibility result = service.evaluateBidEligibility(liveAuction, USER_ID, NOW);
            assertThat(result).isEqualTo(BidEligibility.ELIGIBLE);
        }
    }

    @Nested
    @DisplayName("Viewer State & Blockers Resolution")
    class ViewerResolutionTests {

        @Test
        @DisplayName("resolveViewer returns null when user is guest (null userId)")
        void resolveViewer_Guest_ReturnsNull() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1000.00"), 0, null);
            Viewer viewer = service.resolveViewer(auction, null, NOW);
            assertThat(viewer).isNull();
        }

        @Test
        @DisplayName("resolveViewer identifies LEADING state and collects blockers")
        void resolveViewer_LeadingBidder_WithBlockers() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1500.00"), 2, null);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of());
            given(userVerification.isEmailVerified(USER_ID)).willReturn(false);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.of(NOW.plusDays(3)));
            given(auctions.findMaxBidByUser(AUCTION_ID, USER_ID)).willReturn(Optional.of(new BigDecimal("1500.00")));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(true);
            given(auctions.findLeadingBidderId(AUCTION_ID)).willReturn(Optional.of(USER_ID));

            Viewer viewer = service.resolveViewer(auction, USER_ID, NOW);

            assertThat(viewer).isNotNull();
            assertThat(viewer.state()).isEqualTo(AuctionViewerState.LEADING);
            assertThat(viewer.myHighestBid()).isEqualByComparingTo("1500.00");
            assertThat(viewer.blockers()).containsExactlyInAnyOrder(
                    ErrorCode.NO_SHIPPING_ADDRESS,
                    ErrorCode.EMAIL_NOT_VERIFIED,
                    ErrorCode.BIDDING_BANNED);
        }

        @Test
        @DisplayName("resolveViewer identifies OUTBID state")
        void resolveViewer_Outbid() {
            Auction auction = sampleAuction(AuctionStatus.LIVE, new BigDecimal("1800.00"), 3, null);
            Address address = sampleAddress(USER_ID);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(USER_ID)).willReturn(0);
            given(auctions.findMaxBidByUser(AUCTION_ID, USER_ID)).willReturn(Optional.of(new BigDecimal("1500.00")));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(true);
            given(auctions.findLeadingBidderId(AUCTION_ID)).willReturn(Optional.of(999L));

            Viewer viewer = service.resolveViewer(auction, USER_ID, NOW);

            assertThat(viewer).isNotNull();
            assertThat(viewer.state()).isEqualTo(AuctionViewerState.OUTBID);
            assertThat(viewer.blockers()).isEmpty();
        }

        @Test
        @DisplayName("resolveViewer identifies WON_AWAITING_PAYMENT state and paymentDueAt")
        void resolveViewer_WonAwaitingPayment() {
            Auction auction = new Auction(
                    AUCTION_ID,
                    "AUC-20261102-0001",
                    SELLER_PROFILE_ID,
                    201L,
                    VARIANT_ID,
                    CardCondition.NM,
                    new BigDecimal("1000.00"),
                    new BigDecimal("50.00"),
                    null,
                    new BigDecimal("2500.00"),
                    null,
                    5,
                    3,
                    NOW.minusDays(2),
                    NOW.minusHours(1),
                    NOW.minusHours(1),
                    0,
                    NOW.minusDays(2),
                    null,
                    null,
                    USER_ID,
                    new BigDecimal("2500.00"),
                    NOW.minusHours(1),
                    NOW.plusHours(23),
                    null,
                    AuctionStatus.AWAITING_PAYMENT,
                    null,
                    null,
                    null,
                    null,
                    null,
                    2,
                    NOW.minusDays(2),
                    NOW.minusHours(1));

            Address address = sampleAddress(USER_ID);
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address));
            given(userVerification.isEmailVerified(USER_ID)).willReturn(true);
            given(auctions.findActiveBanUntil(USER_ID, NOW)).willReturn(Optional.empty());
            given(auctions.countActiveStrikes(USER_ID)).willReturn(0);
            given(auctions.findMaxBidByUser(AUCTION_ID, USER_ID)).willReturn(Optional.of(new BigDecimal("2500.00")));
            given(auctions.hasBid(AUCTION_ID, USER_ID)).willReturn(true);

            Viewer viewer = service.resolveViewer(auction, USER_ID, NOW);

            assertThat(viewer).isNotNull();
            assertThat(viewer.state()).isEqualTo(AuctionViewerState.WON_AWAITING_PAYMENT);
            assertThat(viewer.paymentDueAt()).isEqualTo(NOW.plusHours(23));
        }
    }
}
