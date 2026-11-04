package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionPage;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionSummary;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Bid;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.BidHistory;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Filter;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Seller;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Sort;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Viewer;
import com.pegasus.pegasustcgapi.dto.auction.BidEligibility;
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionOffer;
import com.pegasus.pegasustcgapi.model.auction.AuctionOfferStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionViewerState;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.CardSet;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import com.pegasus.pegasustcgapi.model.catalog.Game;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository.PublicAuctionQuery;
import com.pegasus.pegasustcgapi.repository.catalog.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.catalog.GameRepository;
import com.pegasus.pegasustcgapi.repository.user.AddressRepository;
import com.pegasus.pegasustcgapi.service.listing.ListingCardRenderer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public and buyer-facing auction browsing service [TCG-613, TCG-616].
 * Handles live auction catalog browsing, auction detail with bidder eligibility evaluation,
 * and masked bid history.
 */
@Service
public class AuctionBrowseService {

    private static final BigDecimal DEFAULT_SHIPPING = new BigDecimal("50.00");

    private final AuctionRepository auctions;
    private final AddressRepository addresses;
    private final SellerPort sellers;
    private final UserVerificationPort userVerification;
    private final CatalogVariantRepository variants;
    private final CatalogProductRepository products;
    private final GameRepository games;
    private final CardSetRepository cardSets;
    private final ListingCardRenderer cards;
    private final Clock clock;

    public AuctionBrowseService(
            AuctionRepository auctions,
            AddressRepository addresses,
            SellerPort sellers,
            UserVerificationPort userVerification,
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            GameRepository games,
            CardSetRepository cardSets,
            ListingCardRenderer cards,
            Clock clock) {
        this.auctions = auctions;
        this.addresses = addresses;
        this.sellers = sellers;
        this.userVerification = userVerification;
        this.variants = variants;
        this.products = products;
        this.games = games;
        this.cardSets = cardSets;
        this.cards = cards;
        this.clock = clock;
    }

    // =========================================================================
    // Endpoint 1: Auction Catalog Listing
    // =========================================================================

    @Transactional(readOnly = true)
    public AuctionPage list(
            Filter filter,
            Short gameId,
            Long sellerId,
            Long variantId,
            String q,
            Sort sort,
            int page,
            int size) {

        OffsetDateTime now = OffsetDateTime.now(clock);
        Paging paging = Paging.of(page, size);

        PublicAuctionQuery query = new PublicAuctionQuery(
                filter,
                q,
                gameId,
                sellerId,
                variantId,
                sort,
                paging.size(),
                paging.offset(),
                now);

        List<Auction> auctionRows = auctions.findPublicCatalog(query);
        long total = auctions.countPublicCatalog(query);

        List<AuctionSummary> summaries = auctionRows.stream().map(a -> {
            AuctionItem item = buildAuctionItem(a.catalogVariantId(), a.condition());
            String shopName = resolveShopName(a.sellerProfileId());
            BigDecimal buyNowPrice = a.buyNowAvailable() ? a.buyNowPrice() : null;
            return new AuctionSummary(
                    a.id(),
                    a.auctionNumber(),
                    item,
                    shopName,
                    a.startingPrice(),
                    a.currentPrice(),
                    a.bidCount(),
                    buyNowPrice,
                    a.endsAt());
        }).toList();

        Map<Filter, Long> filterCounts = auctions.publicFilterCounts(now);
        long totalLive = auctions.countLiveAuctions(now);

        return new AuctionPage(PageResponse.of(summaries, paging.page(), paging.size(), total), filterCounts, totalLive, now);
    }

    // =========================================================================
    // Endpoint 2: Auction Detail
    // =========================================================================

    @Transactional(readOnly = true)
    public AuctionDetail get(long auctionId, Long userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        Auction a = auctions.findById(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found: " + auctionId));

        AuctionItem item = buildAuctionItem(a.catalogVariantId(), a.condition());
        Seller seller = resolveSeller(a.sellerProfileId());
        List<Bid> recentBids = resolveRecentBids(a.id(), 5, userId);
        Viewer viewer = resolveViewer(a, userId, now);

        BigDecimal minNextBid = a.minNextBid();
        boolean isBuyNowAvailable = a.buyNowAvailable();

        return new AuctionDetail(
                a.id(),
                a.auctionNumber(),
                a.status(),
                item,
                List.of(),
                seller,
                a.sellerNote(),
                a.startingPrice(),
                a.bidIncrement(),
                a.currentPrice(),
                minNextBid,
                a.bidCount(),
                a.bidderCount(),
                a.buyNowPrice(),
                isBuyNowAvailable,
                a.startedAt(),
                a.originalEndsAt(),
                a.endsAt(),
                a.extensionCount(),
                DEFAULT_SHIPPING,
                recentBids,
                viewer,
                now);
    }

    // =========================================================================
    // Endpoint 3: Masked Bid History
    // =========================================================================

    @Transactional(readOnly = true)
    public BidHistory bids(long auctionId, int page, int size, Long currentUserId) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        auctions.findById(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found: " + auctionId));

        Paging paging = Paging.of(page, size);
        List<AuctionBid> bidRows = auctions.findBids(auctionId, paging.size(), paging.offset());
        long total = auctions.countBids(auctionId);

        List<Bid> bids = new ArrayList<>();
        if (!bidRows.isEmpty()) {
            Set<Long> bidderIds = Set.copyOf(bidRows.stream().map(AuctionBid::bidderId).toList());
            Map<Long, String> usernames = auctions.findUsernames(bidderIds);

            for (AuctionBid b : bidRows) {
                String uname = usernames.get(b.bidderId());
                boolean isMine = currentUserId != null && Objects.equals(b.bidderId(), currentUserId);
                String alias = isMine ? uname : maskUsername(uname);
                String initials = bidderInitials(uname);

                bids.add(new Bid(alias, initials, isMine, b.amount(), b.createdAt()));
            }
        }

        return new BidHistory(PageResponse.of(bids, paging.page(), paging.size(), total), now);
    }

    // =========================================================================
    // Bid Eligibility Rules Evaluation
    // =========================================================================

    public BidEligibility evaluateBidEligibility(Auction auction, Long userId, OffsetDateTime now) {
        if (userId == null) {
            return BidEligibility.UNAUTHENTICATED;
        }

        if (addresses.findByUserId(userId).isEmpty()) {
            return BidEligibility.NO_SHIPPING_ADDRESS;
        }

        if (!userVerification.isEmailVerified(userId)) {
            return BidEligibility.EMAIL_NOT_VERIFIED;
        }

        if (auctions.findActiveBanUntil(userId, now).isPresent() || auctions.countActiveStrikes(userId) >= 3) {
            return BidEligibility.BIDDING_PENALIZED;
        }

        if (isOwnAuction(auction.sellerProfileId(), userId)) {
            return BidEligibility.OWN_AUCTION;
        }

        return BidEligibility.ELIGIBLE;
    }

    public Viewer resolveViewer(Auction a, Long userId, OffsetDateTime now) {
        if (userId == null) {
            return null;
        }

        boolean ownAuction = isOwnAuction(a.sellerProfileId(), userId);
        List<ErrorCode> blockers = new ArrayList<>();
        if (ownAuction) {
            blockers.add(ErrorCode.OWN_AUCTION);
        }
        if (addresses.findByUserId(userId).isEmpty()) {
            blockers.add(ErrorCode.NO_SHIPPING_ADDRESS);
        }
        if (!userVerification.isEmailVerified(userId)) {
            blockers.add(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        Optional<OffsetDateTime> banUntil = auctions.findActiveBanUntil(userId, now);
        if (banUntil.isPresent() || auctions.countActiveStrikes(userId) >= 3) {
            blockers.add(ErrorCode.BIDDING_BANNED);
        }

        BigDecimal myHighestBid = auctions.findMaxBidByUser(a.id(), userId).orElse(null);
        AuctionViewerState state = resolveViewerState(a, userId);
        OffsetDateTime paymentDueAt = (state == AuctionViewerState.WON_AWAITING_PAYMENT) ? a.paymentDueAt() : null;

        return new Viewer(
                state,
                myHighestBid,
                ownAuction,
                blockers,
                banUntil.orElse(null),
                paymentDueAt,
                null);
    }

    private AuctionViewerState resolveViewerState(Auction a, Long userId) {
        if (userId == null) {
            return null;
        }
        boolean hasBid = auctions.hasBid(a.id(), userId);
        if (!hasBid && !Objects.equals(a.winnerId(), userId)) {
            return null;
        }

        if (a.status() == AuctionStatus.LIVE) {
            Optional<Long> leadingBidder = auctions.findLeadingBidderId(a.id());
            return leadingBidder.map(id -> id.equals(userId) ? AuctionViewerState.LEADING : AuctionViewerState.OUTBID)
                    .orElse(AuctionViewerState.OUTBID);
        }
        if (a.status() == AuctionStatus.AWAITING_PAYMENT) {
            return Objects.equals(a.winnerId(), userId) ? AuctionViewerState.WON_AWAITING_PAYMENT : AuctionViewerState.LOST;
        }
        if (a.status() == AuctionStatus.SOLD) {
            return Objects.equals(a.winnerId(), userId) ? AuctionViewerState.WON : AuctionViewerState.LOST;
        }
        if (a.status() == AuctionStatus.OFFERED_TO_SECOND) {
            Optional<AuctionOffer> offer = auctions.findLatestOffer(a.id());
            if (offer.isPresent() && offer.get().recipientId() == userId && offer.get().status() == AuctionOfferStatus.PENDING) {
                return AuctionViewerState.SECOND_CHANCE;
            }
            return AuctionViewerState.LOST;
        }
        return AuctionViewerState.LOST;
    }

    private boolean isOwnAuction(long sellerProfileId, long userId) {
        try {
            SellerProfile profile = sellers.requireProfile(userId);
            return profile != null && profile.id() == sellerProfileId;
        } catch (Exception e) {
            return false;
        }
    }

    private AuctionItem buildAuctionItem(long catalogVariantId, CardCondition condition) {
        ListingCard card = cards.card(catalogVariantId);
        CatalogVariant variant = variants.findById(catalogVariantId).orElse(null);
        if (variant == null) {
            return new AuctionItem(card, condition, null, null, null, null);
        }
        CatalogProduct product = products.findById(variant.catalogProductId()).orElse(null);
        if (product == null) {
            return new AuctionItem(card, condition, null, null, null, null);
        }
        String gameName = games.findById(product.gameId()).map(Game::name).orElse(null);
        String setName = product.cardSetId() != null
                ? cardSets.findById(product.cardSetId()).map(CardSet::name).orElse(null)
                : null;
        return new AuctionItem(card, condition, gameName, setName, product.cardNumber(), product.rarityCode());
    }

    private String resolveShopName(long sellerProfileId) {
        return auctions.findSellerInfo(sellerProfileId)
                .map(AuctionRepository.SellerInfo::shopName)
                .orElse("Shop");
    }

    private Seller resolveSeller(long sellerProfileId) {
        var info = auctions.findSellerInfo(sellerProfileId);
        String shopName = info.map(AuctionRepository.SellerInfo::shopName).orElse("Shop");
        String username = info.map(AuctionRepository.SellerInfo::username).orElse("seller");
        BigDecimal rating = info.map(AuctionRepository.SellerInfo::rating).orElse(null);
        long soldCount = auctions.getSellerCompletedSalesCount(sellerProfileId);
        return new Seller(sellerProfileId, shopName, username, rating, soldCount);
    }

    private List<Bid> resolveRecentBids(long auctionId, int limit, Long currentUserId) {
        List<AuctionBid> bidRows = auctions.findBids(auctionId, limit, 0);
        if (bidRows.isEmpty()) {
            return List.of();
        }
        Set<Long> bidderIds = Set.copyOf(bidRows.stream().map(AuctionBid::bidderId).toList());
        Map<Long, String> usernames = auctions.findUsernames(bidderIds);

        List<Bid> bids = new ArrayList<>();
        for (AuctionBid b : bidRows) {
            String uname = usernames.get(b.bidderId());
            boolean isMine = currentUserId != null && Objects.equals(b.bidderId(), currentUserId);
            String alias = isMine ? uname : maskUsername(uname);
            String initials = bidderInitials(uname);
            bids.add(new Bid(alias, initials, isMine, b.amount(), b.createdAt()));
        }
        return bids;
    }

    public static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "Anonymous";
        }
        String s = username.strip();
        if (s.length() <= 2) {
            return s.toLowerCase() + "***";
        }
        return (s.charAt(0) + "***" + s.charAt(s.length() - 1)).toLowerCase();
    }

    public static String bidderInitials(String username) {
        if (username == null || username.isBlank()) {
            return "AN";
        }
        String s = username.strip().toUpperCase();
        if (s.length() <= 1) {
            return (s + s).substring(0, 2);
        }
        return "" + s.charAt(0) + s.charAt(s.length() - 1);
    }
}
