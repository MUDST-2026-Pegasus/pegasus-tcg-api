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
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionViewerState;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository.ProductAuctionStats;
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
    private final AuctionEligibilityValidator validator;
    private final AuctionItemMapper itemMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ProductAuctionStats productStats(long productId) {
        return auctions.productStats(productId, OffsetDateTime.now(clock));
    }

    @Autowired
    public AuctionBrowseService(
            AuctionRepository auctions,
            AuctionEligibilityValidator validator,
            AuctionItemMapper itemMapper,
            Clock clock) {
        this.auctions = auctions;
        this.validator = validator;
        this.itemMapper = itemMapper;
        this.clock = clock;
    }

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
            Clock clock,
            com.pegasus.pegasustcgapi.service.setting.PlatformSettingService settings) {
        this(auctions,
             new AuctionEligibilityValidator(addresses, userVerification, auctions, sellers, settings),
             new AuctionItemMapper(cards, variants, products, games, cardSets, auctions),
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

        List<Auction> auctionRows = auctions.findPublicCatalog(query);
        long total = auctions.countPublicCatalog(query);

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
                AuctionPolicy.DEFAULT_SHIPPING,
                recentBids,
                viewer,
                a.winnerId() == null ? null
                        : AuctionItemMapper.maskUsername(auctions.findUsername(a.winnerId()).orElse(null)),
                a.status() == AuctionStatus.SUSPENDED ? a.suspendedReason() : null,
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
                String alias = isMine ? uname : AuctionItemMapper.maskUsername(uname);
                String initials = AuctionItemMapper.bidderInitials(uname);

                bids.add(new Bid(alias, initials, isMine, b.amount(), b.createdAt()));
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
        Optional<OffsetDateTime> banUntil = auctions.findActiveBanUntil(userId, now);
        BigDecimal myHighestBid = auctions.findMaxBidByUser(a.id(), userId).orElse(null);
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
        return AuctionViewerState.LOST;
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
