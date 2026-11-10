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
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Rules;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Seller;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Sort;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Viewer;
import com.pegasus.pegasustcgapi.dto.auction.BidEligibility;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionViewerState;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionBidRepository;
import com.pegasus.pegasustcgapi.repository.auction.BidderStrikeRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionQueryRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionQueryRepository.ProductAuctionStats;
import com.pegasus.pegasustcgapi.repository.auction.AuctionQueryRepository.PublicAuctionQuery;
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
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public and buyer-facing auction browsing service [TCG-613, TCG-616, ISSUE-001, ISSUE-010].
 * Handles live auction catalog browsing, auction detail with bidder eligibility evaluation,
 * and masked bid history.
 */
@Service
public class AuctionBrowseService {

    private final AuctionRepository auctions;
    private final AuctionBidRepository auctionBids;
    private final BidderStrikeRepository bidderStrikes;
    private final AuctionQueryRepository auctionQueries;
    private final AuctionReferenceQueryRepository auctionReferences;
    private final AuctionEligibilityValidator validator;
    private final AuctionItemMapper itemMapper;
    private final Clock clock;
    private final AuctionShippingFee shippingFee;
    private final PlatformSettingService settings;

    @Transactional(readOnly = true)
    public ProductAuctionStats productStats(long productId) {
        return auctionQueries.productStats(productId, OffsetDateTime.now(clock));
    }

    @Autowired
    public AuctionBrowseService(
            AuctionRepository auctions,
            AuctionBidRepository auctionBids,
            BidderStrikeRepository bidderStrikes,
            AuctionQueryRepository auctionQueries,
            AuctionReferenceQueryRepository auctionReferences,
            AuctionEligibilityValidator validator,
            AuctionItemMapper itemMapper,
            Clock clock,
            AuctionShippingFee shippingFee,
            PlatformSettingService settings) {
        this.auctions = auctions;
        this.auctionBids = auctionBids;
        this.bidderStrikes = bidderStrikes;
        this.auctionQueries = auctionQueries;
        this.auctionReferences = auctionReferences;
        this.validator = validator;
        this.itemMapper = itemMapper;
        this.clock = clock;
        this.shippingFee = shippingFee;
        this.settings = settings;
    }

    public AuctionBrowseService(
            AuctionRepository auctions,
            AuctionBidRepository auctionBids,
            BidderStrikeRepository bidderStrikes,
            AuctionQueryRepository auctionQueries,
            AuctionReferenceQueryRepository auctionReferences,
            AuctionEligibilityValidator validator,
            AuctionItemMapper itemMapper,
            Clock clock) {
        this(auctions, auctionBids, bidderStrikes, auctionQueries, auctionReferences, validator, itemMapper, clock, AuctionShippingFee.fixed(), null);
    }

    /** The rules shown on the page; the defaults when built without settings (tests). */
    private Rules currentRules() {
        if (settings == null) return Rules.DEFAULTS;
        return new Rules(
                settings.getDecimal(PlatformSettingService.AUCTION_PAYMENT_HOURS),
                settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT),
                settings.getInt(PlatformSettingService.AUCTION_BAN_DAYS));
    }

    public AuctionBrowseService(
            AuctionRepository auctions,
            AuctionBidRepository auctionBids,
            BidderStrikeRepository bidderStrikes,
            AuctionQueryRepository auctionQueries,
            AuctionReferenceQueryRepository auctionReferences,
            AddressRepository addresses,
            SellerPort sellers,
            UserVerificationPort userVerification,
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            GameRepository games,
            CardSetRepository cardSets,
            ListingCardRenderer cards,
            Clock clock,
            com.pegasus.pegasustcgapi.service.setting.PlatformSettingService settings) {
        this(auctions, auctionBids, bidderStrikes, auctionQueries, auctionReferences,
             new AuctionEligibilityValidator(addresses, userVerification, bidderStrikes, sellers, settings),
             new AuctionItemMapper(cards, variants, products, games, cardSets, auctionReferences),
             clock);
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
        return list(filter, gameId, sellerId, null, variantId, q, sort, page, size);
    }

    @Transactional(readOnly = true)
    public AuctionPage list(
            Filter filter,
            Short gameId,
            Long sellerId,
            String sellerUsername,
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
                sellerUsername,
                variantId,
                sort,
                paging.size(),
                paging.offset(),
                now);

        List<Auction> auctionRows = auctionQueries.findPublicCatalog(query);
        long total = auctionQueries.countPublicCatalog(query);

        Set<Long> variantIds = auctionRows.stream().map(Auction::catalogVariantId).collect(Collectors.toSet());
        Set<Long> sellerIds = auctionRows.stream().map(Auction::sellerProfileId).collect(Collectors.toSet());
        var bundle = itemMapper.buildAuctionItems(variantIds, sellerIds);

        List<AuctionSummary> summaries = auctionRows.stream().map(a -> {
            AuctionItem item = bundle.buildAuctionItem(a.catalogVariantId(), a.condition());
            String shopName = bundle.resolveShopName(a.sellerProfileId());
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

        Map<Filter, Long> filterCounts = auctionQueries.publicFilterCounts(now);
        long totalLive = auctionQueries.countLiveAuctions(now);

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

        AuctionItem item = itemMapper.buildAuctionItem(a.catalogVariantId(), a.condition());
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
                a.closingOrCurrentPrice(),
                minNextBid,
                a.bidCount(),
                a.bidderCount(),
                a.buyNowPrice(),
                isBuyNowAvailable,
                a.startedAt(),
                a.originalEndsAt(),
                a.closedOrEndsAt(),
                a.extensionCount(),
                shippingFee.quote(a.sellerProfileId(), a.closingOrCurrentPrice()),
                recentBids,
                viewer,
                a.winnerId() == null ? null
                        : AuctionItemMapper.maskUsername(auctionReferences.findUsername(a.winnerId()).orElse(null)),
                a.status() == AuctionStatus.SUSPENDED ? a.suspendedReason() : null,
                currentRules(),
                now);
    }

    // =========================================================================
    // Endpoint 3: Masked Bid History
    // =========================================================================

    @Transactional(readOnly = true)
    public BidHistory bids(long auctionId, int page, int size, Long currentUserId) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        Auction auction = auctions.findById(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found: " + auctionId));

        Paging paging = Paging.of(page, size);
        // A suspension cancels every bid; still show them (marked cancelled) so the history doesn't vanish
        boolean suspended = auction.status() == AuctionStatus.SUSPENDED;
        List<AuctionBid> bidRows = suspended
                ? auctionBids.findBids(auctionId, true, paging.size(), paging.offset())
                : auctionBids.findBids(auctionId, paging.size(), paging.offset());
        long total = suspended ? auctionBids.countBids(auctionId, true) : auctionBids.countBids(auctionId);

        List<Bid> bids = new ArrayList<>();
        if (!bidRows.isEmpty()) {
            Set<Long> bidderIds = Set.copyOf(bidRows.stream().map(AuctionBid::bidderId).toList());
            Map<Long, String> usernames = auctionReferences.findUsernames(bidderIds);

            for (AuctionBid b : bidRows) {
                String uname = usernames.get(b.bidderId());
                boolean isMine = currentUserId != null && Objects.equals(b.bidderId(), currentUserId);
                String alias = isMine ? uname : AuctionItemMapper.maskUsername(uname);
                String initials = AuctionItemMapper.bidderInitials(uname);

                bids.add(new Bid(alias, initials, isMine, b.amount(), b.createdAt(),
                        b.status() == BidStatus.CANCELLED));
            }
        }

        return new BidHistory(PageResponse.of(bids, paging.page(), paging.size(), total), now);
    }

    // =========================================================================
    // Bid Eligibility Rules Evaluation
    // =========================================================================

    public BidEligibility evaluateBidEligibility(Auction auction, Long userId, OffsetDateTime now) {
        return validator.evaluateBidEligibility(auction, userId, now);
    }

    public Viewer resolveViewer(Auction a, Long userId, OffsetDateTime now) {
        if (userId == null) {
            return null;
        }

        boolean ownAuction = validator.isOwnAuction(a.sellerProfileId(), userId);
        List<ErrorCode> blockers = validator.findBlockers(a, userId, now);
        Optional<OffsetDateTime> banUntil = bidderStrikes.findActiveBanUntil(userId, now);
        BigDecimal myHighestBid = auctionBids.findMaxBidByUser(a.id(), userId).orElse(null);
        AuctionViewerState state = resolveViewerState(a, userId);
        OffsetDateTime paymentDueAt = (state == AuctionViewerState.WON_AWAITING_PAYMENT) ? a.paymentDueAt() : null;

        return new Viewer(
                state,
                myHighestBid,
                ownAuction,
                blockers,
                banUntil.orElse(null),
                paymentDueAt);
    }

    private AuctionViewerState resolveViewerState(Auction a, Long userId) {
        if (userId == null) {
            return null;
        }
        boolean hasBid = auctionBids.hasBid(a.id(), userId);
        if (!hasBid && !Objects.equals(a.winnerId(), userId)) {
            return null;
        }

        if (a.status() == AuctionStatus.LIVE) {
            Optional<Long> leadingBidder = auctionBids.findLeadingBidderId(a.id());
            return leadingBidder.map(id -> id.equals(userId) ? AuctionViewerState.LEADING : AuctionViewerState.OUTBID)
                    .orElse(AuctionViewerState.OUTBID);
        }
        if (a.status() == AuctionStatus.AWAITING_PAYMENT) {
            return Objects.equals(a.winnerId(), userId) ? AuctionViewerState.WON_AWAITING_PAYMENT : AuctionViewerState.LOST;
        }
        if (a.status() == AuctionStatus.SOLD) {
            return Objects.equals(a.winnerId(), userId) ? AuctionViewerState.WON : AuctionViewerState.LOST;
        }
        return AuctionViewerState.LOST;
    }

    private Seller resolveSeller(long sellerProfileId) {
        var info = auctionReferences.findSellerInfo(sellerProfileId);
        String shopName = info.map(AuctionReferenceQueryRepository.SellerInfo::shopName).orElse("Shop");
        String username = info.map(AuctionReferenceQueryRepository.SellerInfo::username).orElse("seller");
        BigDecimal rating = info.map(AuctionReferenceQueryRepository.SellerInfo::rating).orElse(null);
        long soldCount = auctionReferences.getSellerCompletedSalesCount(sellerProfileId);
        return new Seller(sellerProfileId, shopName, username, rating, soldCount);
    }

    private List<Bid> resolveRecentBids(long auctionId, int limit, Long currentUserId) {
        List<AuctionBid> bidRows = auctionBids.findBids(auctionId, limit, 0);
        if (bidRows.isEmpty()) {
            return List.of();
        }
        Set<Long> bidderIds = Set.copyOf(bidRows.stream().map(AuctionBid::bidderId).toList());
        Map<Long, String> usernames = auctionReferences.findUsernames(bidderIds);

        List<Bid> bids = new ArrayList<>();
        for (AuctionBid b : bidRows) {
            String uname = usernames.get(b.bidderId());
            boolean isMine = currentUserId != null && Objects.equals(b.bidderId(), currentUserId);
            String alias = isMine ? uname : AuctionItemMapper.maskUsername(uname);
            String initials = AuctionItemMapper.bidderInitials(uname);
            bids.add(new Bid(alias, initials, isMine, b.amount(), b.createdAt()));
        }
        return bids;
    }

    public static String maskUsername(String username) {
        return AuctionItemMapper.maskUsername(username);
    }

    public static String bidderInitials(String username) {
        return AuctionItemMapper.bidderInitials(username);
    }
}
