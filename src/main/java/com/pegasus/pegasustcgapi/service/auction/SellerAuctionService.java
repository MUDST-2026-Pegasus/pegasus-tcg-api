package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.CreateAuction;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.UpdateAuction;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Bid;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.EligibleUnit;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.EligibleUnitPage;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.Kpis;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.MarketGuide;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.SellerAuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.SellerAuctionPage;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.SellerAuctionRow;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.UnitAvailability;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
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
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository.SellerAuctionFilter;
import com.pegasus.pegasustcgapi.repository.catalog.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.catalog.GameRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import com.pegasus.pegasustcgapi.service.listing.ListingCardRenderer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service driving seller auctions under {@code /sellers/me/auctions} [TCG-615, ISSUE-001, ISSUE-002, ISSUE-003, ISSUE-004, ISSUE-011].
 */
@Service
public class SellerAuctionService {

    private final AuctionRepository auctions;
    private final ListingUnitRepository units;
    private final InventoryService inventoryService;
    private final SellerPort sellers;
    private final LedgerPort ledgerPort;
    private final AuctionItemMapper auctionItemMapper;
    private final Clock clock;

    @Autowired
    public SellerAuctionService(
            AuctionRepository auctions,
            ListingUnitRepository units,
            InventoryService inventoryService,
            SellerPort sellers,
            LedgerPort ledgerPort,
            AuctionItemMapper auctionItemMapper,
            Clock clock) {
        this.auctions = auctions;
        this.units = units;
        this.inventoryService = inventoryService;
        this.sellers = sellers;
        this.ledgerPort = ledgerPort;
        this.auctionItemMapper = auctionItemMapper;
        this.clock = clock;
    }

    public SellerAuctionService(
            AuctionRepository auctions,
            ListingUnitRepository units,
            InventoryService inventoryService,
            SellerPort sellers,
            LedgerPort ledgerPort,
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            GameRepository games,
            CardSetRepository cardSets,
            ListingCardRenderer cards,
            DSLContext dsl,
            Clock clock) {
        this(auctions, units, inventoryService, sellers, ledgerPort,
                new AuctionItemMapper(cards, variants, products, games, cardSets, auctions),
                clock);
    }

    // ---------- Rule 1: Seller Verification ----------

    public SellerProfile requireVerifiedSeller(long userId) {
        try {
            SellerProfile profile = sellers.requireProfile(userId);
            if (profile == null || profile.status() != SellerStatus.VERIFIED) {
                throw new ForbiddenException(ErrorCode.SELLER_NOT_VERIFIED, "Seller profile is not verified");
            }
            return profile;
        } catch (NotFoundException e) {
            throw new ForbiddenException(ErrorCode.SELLER_NOT_VERIFIED, "Seller profile does not exist");
        }
    }

    // ---------- Rule 6: List Auctions ----------

    @Transactional(readOnly = true)
    public SellerAuctionPage list(long userId, AuctionStatus status, String q, int page, int size) {
        SellerProfile seller = requireVerifiedSeller(userId);
        Paging paging = Paging.of(page, size);

        SellerAuctionFilter filter = new SellerAuctionFilter(seller.id(), status, q, paging.size(), paging.offset());
        List<Auction> rows = auctions.findBySeller(filter);
        long total = auctions.countBySeller(seller.id(), status, q);

        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<AuctionStatus, Long> statusCounts = auctions.statusCounts(seller.id());
        Kpis kpis = auctions.calculateKpis(seller.id(), now);

        Set<Long> variantIds = rows.stream().map(Auction::catalogVariantId).collect(Collectors.toSet());
        var bundle = auctionItemMapper.buildAuctionItems(variantIds, List.of());

        List<SellerAuctionRow> listRows = rows.stream().map(a -> {
            AuctionItem item = bundle.buildAuctionItem(a.catalogVariantId(), a.condition());
            String unitLabel = formatUnitLabel(null, a.listingUnitId());
            OffsetDateTime deadline = resolveDeadline(a);
            return new SellerAuctionRow(
                    a.id(),
                    a.auctionNumber(),
                    item,
                    unitLabel,
                    a.startingPrice(),
                    a.currentPrice(),
                    a.bidCount(),
                    a.buyNowPrice(),
                    a.buyNowAvailable(),
                    a.status(),
                    a.endsAt(),
                    deadline,
                    a.salesOrderId());
        }).toList();

        return new SellerAuctionPage(PageResponse.of(listRows, paging.page(), paging.size(), total), statusCounts, kpis, now);
    }

    // ---------- Rule 2: Eligible Card Units for Auction ----------

    @Transactional(readOnly = true)
    public EligibleUnitPage eligibleUnits(long userId, String q, Short gameId, CardCondition condition, int page, int size) {
        SellerProfile seller = requireVerifiedSeller(userId);
        Paging paging = Paging.of(page, size);

        long totalInStock = units.countInStock(seller.id());
        List<ListingUnit> unitRows = units.findEligibleUnits(seller.id(), q, gameId, condition, paging.size(), paging.offset());
        long count = units.countEligibleUnits(seller.id(), q, gameId, condition);

        Set<Long> variantIds = unitRows.stream().map(ListingUnit::catalogVariantId).collect(Collectors.toSet());
        Set<Long> unitIds = unitRows.stream().map(ListingUnit::id).collect(Collectors.toSet());

        var bundle = auctionItemMapper.buildAuctionItems(variantIds, List.of());
        Map<Long, String> heldAuctions = auctions.findHeldAuctionNumbers(unitIds);
        Map<Long, String> heldOrders = units.findHeldOrderNumbers(unitIds);
        Map<AuctionRepository.VariantConditionKey, MarketGuide> marketGuides = auctions.findMarketGuides(variantIds);

        List<EligibleUnit> eligible = unitRows.stream().map(u -> {
            AuctionItem item = bundle.buildAuctionItem(u.catalogVariantId(), u.condition());
            String unitLabel = formatUnitLabel(u.publicUid(), u.id());
            MarketGuide market = marketGuides.get(new AuctionRepository.VariantConditionKey(u.catalogVariantId(), u.condition().name()));

            UnitAvailability availability;
            String heldBy = null;

            switch (u.status()) {
                case IN_STOCK -> availability = UnitAvailability.AVAILABLE;
                case LISTED -> availability = UnitAvailability.LISTED;
                case ON_AUCTION -> {
                    availability = UnitAvailability.IN_AUCTION;
                    String heldNum = heldAuctions.get(u.id());
                    heldBy = heldNum != null ? "#" + heldNum : "อยู่ในประมูล";
                }
                case RESERVED -> {
                    availability = UnitAvailability.RESERVED;
                    String heldNum = heldOrders.get(u.id());
                    heldBy = heldNum != null ? "#" + heldNum : "จองในคำสั่งซื้อ";
                }
                default -> availability = UnitAvailability.RESERVED;
            }

            return new EligibleUnit(u.publicUid(), unitLabel, item, u.acquisitionCost(), market, availability, heldBy);
        }).toList();

        return new EligibleUnitPage(PageResponse.of(eligible, paging.page(), paging.size(), count), totalInStock);
    }

    // ---------- Rule 3: Create Auction ----------

    @Transactional
    public SellerAuctionDetail create(long userId, CreateAuction request) {
        SellerProfile seller = requireVerifiedSeller(userId);

        Instant now = clock.instant();
        if (request.endsAt() == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Auction endsAt is required");
        }
        Instant endsAt = request.endsAt().toInstant();

        // Duration validation: between 1 hour and 30 days
        if (endsAt.isBefore(now.plus(AuctionPolicy.MIN_AUCTION_DURATION))) {
            throw new BadRequestException(ErrorCode.AUCTION_END_TOO_SOON, "The closing time must be at least 1 hour from now");
        }
        if (endsAt.isAfter(now.plus(AuctionPolicy.MAX_AUCTION_DURATION))) {
            throw new BadRequestException(ErrorCode.AUCTION_END_TOO_FAR, "The closing time must be within 30 days from now");
        }

        // Bid increment validation: strictly > 0
        BigDecimal bidStep = request.bidIncrement();
        if (bidStep == null || bidStep.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "bidIncrement must be strictly positive");
        }

        // Starting price validation: strictly > 0
        if (request.startingPrice() == null || request.startingPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "startingPrice must be strictly positive");
        }

        // Buy now price validation: if provided, must be strictly > startingPrice
        if (request.buyNowPrice() != null && request.buyNowPrice().compareTo(request.startingPrice()) <= 0) {
            throw new BadRequestException(ErrorCode.BUY_NOW_BELOW_START, "The buy-now price must be higher than the starting price");
        }

        // Card unit availability check
        ListingUnit unit = resolveAndLockUnit(seller.id(), request.unitUid(), null);
        if (unit.status() != ListingUnitStatus.IN_STOCK && unit.status() != ListingUnitStatus.LISTED) {
            throw new ConflictException(ErrorCode.UNIT_NOT_AVAILABLE, "This card is held by an order or another auction");
        }

        // If LISTED or IN_STOCK, move to ON_AUCTION
        units.moveToAuction(unit.id());

        // Create auction record in status LIVE
        String auctionNumber = auctions.nextAuctionNumber();
        OffsetDateTime startedAt = now.atOffset(ZoneOffset.UTC);
        OffsetDateTime endsAtOffset = request.endsAt();

        NewAuction newAuction = new NewAuction(
                auctionNumber,
                seller.id(),
                unit.id(),
                unit.catalogVariantId(),
                unit.condition(),
                request.startingPrice(),
                bidStep,
                request.buyNowPrice(),
                startedAt,
                endsAtOffset,
                request.sellerNote(),
                null);

        long auctionId = auctions.insert(newAuction);

        // Record inventory movement via InventoryService
        inventoryService.recordAuctionTransfer(unit, auctionId, userId);

        Auction created = auctions.findById(auctionId)
                .orElseThrow(() -> new IllegalStateException("Failed to retrieve created auction"));
        return toSellerAuctionDetail(created, seller);
    }

    // ---------- Rule 4: Edit Auction Settings ----------

    @Transactional
    public SellerAuctionDetail update(long userId, long auctionId, UpdateAuction request) {
        SellerProfile seller = requireVerifiedSeller(userId);
        Auction auction = lockOwnAuction(auctionId, seller.id());
        requireEditable(auction);

        if (request.buyNowPrice() != null && request.buyNowPrice().compareTo(auction.startingPrice()) <= 0) {
            throw new BadRequestException(ErrorCode.BUY_NOW_BELOW_START, "The buy-now price must be higher than the starting price");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!auctions.updateSettings(auctionId, seller.id(), request.buyNowPrice(), request.sellerNote(), now)) {
            throw new ConflictException(ErrorCode.AUCTION_HAS_BIDS, "This auction already has bids, so it can no longer be changed or cancelled");
        }

        Auction updated = auctions.findById(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found"));
        return toSellerAuctionDetail(updated, seller);
    }

    // ---------- Rule 5: Cancel Auction ----------

    @Transactional
    public SellerAuctionDetail cancel(long userId, long auctionId) {
        SellerProfile seller = requireVerifiedSeller(userId);
        Auction auction = lockOwnAuction(auctionId, seller.id());
        requireEditable(auction);

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!auctions.cancel(auctionId, seller.id(), now)) {
            throw new ConflictException(ErrorCode.AUCTION_HAS_BIDS, "This auction already has bids, so it can no longer be changed or cancelled");
        }

        // Revert listing_unit status from ON_AUCTION back to IN_STOCK
        units.revertFromAuction(auction.listingUnitId());

        // Record inventory movement via InventoryService
        units.findOfSeller(auction.listingUnitId(), seller.id())
                .ifPresent(u -> inventoryService.recordAuctionCancellation(u, auctionId, userId));

        Auction cancelled = auctions.findById(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found"));
        return toSellerAuctionDetail(cancelled, seller);
    }

    // ---------- Rule 6: Get Auction Detail ----------

    public SellerAuctionDetail get(long userId, long auctionId) {
        SellerProfile seller = requireVerifiedSeller(userId);

        Auction auction = auctions.findByIdAndSeller(auctionId, seller.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found"));

        return toSellerAuctionDetail(auction, seller);
    }

    // ---------- Helpers ----------

    /**
     * Locks the seller's own auction row, so a bid cannot land between the checks and the write.
     * Another shop's auction is 404.
     */
    private Auction lockOwnAuction(long auctionId, long sellerProfileId) {
        return auctions.lockById(auctionId)
                .filter(a -> a.sellerProfileId() == sellerProfileId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found"));
    }

    /** Buy-now, the note and cancelling may change only while LIVE with no bids. */
    private static void requireEditable(Auction auction) {
        if (auction.bidCount() > 0) {
            throw new ConflictException(ErrorCode.AUCTION_HAS_BIDS, "This auction already has bids, so it can no longer be changed or cancelled");
        }
        if (auction.status() != AuctionStatus.LIVE) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED, "This auction is no longer open");
        }
    }

    public ListingUnit resolveAndLockUnit(long sellerProfileId, UUID unitUid, Long listingUnitId) {
        if (unitUid != null) {
            return units.lockOfSeller(unitUid, sellerProfileId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_UNIT_NOT_FOUND, "Card not found in this seller's stock"));
        }
        if (listingUnitId != null) {
            return units.lockOfSeller(listingUnitId, sellerProfileId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_UNIT_NOT_FOUND, "Card not found in this seller's stock"));
        }
        throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Listing unit identifier is required");
    }

    private OffsetDateTime resolveDeadline(Auction a) {
        return a.status() == AuctionStatus.AWAITING_PAYMENT ? a.paymentDueAt() : null;
    }

    private SellerAuctionDetail toSellerAuctionDetail(Auction a, SellerProfile seller) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AuctionItem item = auctionItemMapper.buildAuctionItem(a.catalogVariantId(), a.condition());
        ListingUnit unit = units.findOfSeller(a.listingUnitId(), seller.id()).orElse(null);
        String unitLabel = unit != null ? formatUnitLabel(unit.publicUid(), unit.id()) : formatUnitLabel(null, a.listingUnitId());

        // Platform commission calculation
        CommissionQuote quote = ledgerPort.quoteCommission(seller.id(), a.currentPrice());
        BigDecimal commissionPercent = quote != null ? quote.ratePercent() : new BigDecimal("5.0");
        BigDecimal commissionFee = quote != null ? quote.amount() : a.currentPrice().multiply(new BigDecimal("0.05"));
        BigDecimal estimatedPayout = a.currentPrice().subtract(commissionFee);

        // Bids with masked bidder aliases
        List<AuctionBid> bidRows = auctions.findBids(a.id(), 50, 0);
        List<Bid> bids = new ArrayList<>();
        if (!bidRows.isEmpty()) {
            Set<Long> userIds = Set.copyOf(bidRows.stream().map(AuctionBid::bidderId).toList());
            Map<Long, String> usernames = auctions.findUsernames(userIds);

            for (AuctionBid b : bidRows) {
                String uname = usernames.get(b.bidderId());
                String alias = AuctionItemMapper.maskUsername(uname);
                String initials = AuctionItemMapper.bidderInitials(uname);
                bids.add(new Bid(alias, initials, false, b.amount(), b.createdAt()));
            }
        }

        // Winner alias if auction ended with winner
        String winnerAlias = null;
        if (a.winnerId() != null) {
            String uname = auctions.findUsername(a.winnerId()).orElse(null);
            winnerAlias = AuctionItemMapper.maskUsername(uname);
        }

        return new SellerAuctionDetail(
                a.id(),
                a.auctionNumber(),
                a.status(),
                item,
                unitLabel,
                a.startingPrice(),
                a.bidIncrement(),
                a.currentPrice(),
                a.bidCount(),
                a.bidderCount(),
                estimatedPayout,
                commissionPercent,
                a.buyNowPrice(),
                a.buyNowAvailable(),
                a.sellerNote(),
                a.sellerEditable(),
                a.sellerEditable(),
                a.startedAt(),
                a.originalEndsAt(),
                a.endsAt(),
                a.extensionCount(),
                a.firstBidAt(),
                winnerAlias,
                a.paymentDueAt(),
                a.salesOrderId(),
                bids,
                now);
    }

    private static String formatUnitLabel(UUID publicUid, long unitId) {
        if (publicUid != null) {
            String str = publicUid.toString();
            return "U-" + str.substring(str.length() - 4);
        }
        return "U-%04d".formatted(unitId % 10000);
    }
}
