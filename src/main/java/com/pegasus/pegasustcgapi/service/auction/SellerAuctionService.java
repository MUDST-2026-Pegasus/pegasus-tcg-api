package com.pegasus.pegasustcgapi.service.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

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
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.CardSet;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import com.pegasus.pegasustcgapi.model.catalog.Game;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service driving seller auctions under {@code /sellers/me/auctions} [TCG-615].
 */
@Service
public class SellerAuctionService {

    private final AuctionRepository auctions;
    private final ListingUnitRepository units;
    private final InventoryService inventoryService;
    private final SellerPort sellers;
    private final LedgerPort ledgerPort;
    private final CatalogVariantRepository variants;
    private final CatalogProductRepository products;
    private final GameRepository games;
    private final CardSetRepository cardSets;
    private final ListingCardRenderer cards;
    private final DSLContext dsl;
    private final Clock clock;

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

        this.auctions = auctions;
        this.units = units;
        this.inventoryService = inventoryService;
        this.sellers = sellers;
        this.ledgerPort = ledgerPort;
        this.variants = variants;
        this.products = products;
        this.games = games;
        this.cardSets = cardSets;
        this.cards = cards;
        this.dsl = dsl;
        this.clock = clock;
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

    public SellerAuctionPage list(long userId, AuctionStatus status, String q, int page, int size) {
        SellerProfile seller = requireVerifiedSeller(userId);
        Paging paging = Paging.of(page, size);

        SellerAuctionFilter filter = new SellerAuctionFilter(seller.id(), status, q, paging.size(), paging.offset());
        List<Auction> rows = auctions.findBySeller(filter);
        long total = auctions.countBySeller(seller.id(), status, q);

        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<AuctionStatus, Long> statusCounts = auctions.statusCounts(seller.id());
        Kpis kpis = auctions.calculateKpis(seller.id(), now);

        List<SellerAuctionRow> listRows = rows.stream().map(a -> {
            AuctionItem item = buildAuctionItem(a.catalogVariantId(), a.condition());
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

    public EligibleUnitPage eligibleUnits(long userId, String q, Short gameId, CardCondition condition, int page, int size) {
        SellerProfile seller = requireVerifiedSeller(userId);
        Paging paging = Paging.of(page, size);

        Condition cond = LISTING_UNIT.SELLER_PROFILE_ID.eq(seller.id())
                .and(LISTING_UNIT.STATUS.in(
                        ListingUnitStatus.IN_STOCK.name(),
                        ListingUnitStatus.LISTED.name(),
                        ListingUnitStatus.ON_AUCTION.name(),
                        ListingUnitStatus.RESERVED.name()))
                .and(LISTING_UNIT.DELETED_AT.isNull());

        if (condition != null) {
            cond = cond.and(LISTING_UNIT.CONDITION_CODE.eq(condition.name()));
        }

        if (gameId != null) {
            cond = cond.and(DSL.exists(
                    DSL.selectOne()
                            .from(CATALOG_VARIANT)
                            .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                            .where(CATALOG_VARIANT.ID.eq(LISTING_UNIT.CATALOG_VARIANT_ID))
                            .and(CATALOG_PRODUCT.GAME_ID.eq(gameId))));
        }

        if (q != null && !q.isBlank()) {
            String search = q.trim();
            cond = cond.and(DSL.exists(
                    DSL.selectOne()
                            .from(CATALOG_VARIANT)
                            .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                            .where(CATALOG_VARIANT.ID.eq(LISTING_UNIT.CATALOG_VARIANT_ID))
                            .and(CATALOG_PRODUCT.NAME.containsIgnoreCase(search)
                                    .or(CATALOG_PRODUCT.CARD_NUMBER.containsIgnoreCase(search))
                                    .or(CATALOG_VARIANT.SKU.containsIgnoreCase(search)))));
        }

        long totalInStock = dsl.fetchCount(LISTING_UNIT,
                LISTING_UNIT.SELLER_PROFILE_ID.eq(seller.id())
                        .and(LISTING_UNIT.STATUS.in(
                                ListingUnitStatus.IN_STOCK.name(),
                                ListingUnitStatus.LISTED.name(),
                                ListingUnitStatus.ON_AUCTION.name(),
                                ListingUnitStatus.RESERVED.name()))
                        .and(LISTING_UNIT.DELETED_AT.isNull()));

        var orderField = DSL.case_()
                .when(LISTING_UNIT.STATUS.in(ListingUnitStatus.IN_STOCK.name(), ListingUnitStatus.LISTED.name()), 0)
                .otherwise(1);

        List<ListingUnit> unitRows = dsl.selectFrom(LISTING_UNIT)
                .where(cond)
                .orderBy(orderField.asc(), LISTING_UNIT.CREATED_AT.desc(), LISTING_UNIT.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(ListingUnitRepository::toUnit);

        long count = dsl.fetchCount(LISTING_UNIT, cond);

        List<EligibleUnit> eligible = unitRows.stream().map(u -> {
            AuctionItem item = buildAuctionItem(u.catalogVariantId(), u.condition());
            String unitLabel = formatUnitLabel(u.publicUid(), u.id());
            MarketGuide market = calculateMarketGuide(u.catalogVariantId(), u.condition());

            UnitAvailability availability;
            String heldBy = null;

            switch (u.status()) {
                case IN_STOCK -> availability = UnitAvailability.AVAILABLE;
                case LISTED -> availability = UnitAvailability.LISTED;
                case ON_AUCTION -> {
                    availability = UnitAvailability.IN_AUCTION;
                    heldBy = findHeldAuctionNumber(u.id()).map(num -> "#" + num).orElse("อยู่ในประมูล");
                }
                case RESERVED -> {
                    availability = UnitAvailability.RESERVED;
                    heldBy = findHeldOrderNumber(u.id()).map(num -> "#" + num).orElse("จองในคำสั่งซื้อ");
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
        if (endsAt.isBefore(now.plus(Duration.ofHours(1)))) {
            throw new BadRequestException(ErrorCode.AUCTION_END_TOO_SOON, "The closing time must be at least 1 hour from now");
        }
        if (endsAt.isAfter(now.plus(Duration.ofDays(30)))) {
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

    private MarketGuide calculateMarketGuide(long catalogVariantId, CardCondition condition) {
        List<BigDecimal> prices = dsl.select(LISTING.PRICE)
                .from(LISTING)
                .where(LISTING.CATALOG_VARIANT_ID.eq(catalogVariantId))
                .and(LISTING.CONDITION_CODE.eq(condition.name()))
                .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()))
                .and(LISTING.DELETED_AT.isNull())
                .orderBy(LISTING.PRICE.asc())
                .fetch(LISTING.PRICE);

        if (prices.isEmpty()) {
            return null;
        }

        int count = prices.size();
        BigDecimal min = prices.getFirst();
        BigDecimal max = prices.getLast();
        BigDecimal median = prices.get(count / 2);
        return new MarketGuide(count, min, median, max);
    }

    private Optional<String> findHeldAuctionNumber(long listingUnitId) {
        return dsl.select(AUCTION.AUCTION_NUMBER)
                .from(AUCTION)
                .where(AUCTION.LISTING_UNIT_ID.eq(listingUnitId))
                .and(AUCTION.STATUS.in(
                        AuctionStatus.LIVE.name(),
                        AuctionStatus.AWAITING_PAYMENT.name()))
                .orderBy(AUCTION.CREATED_AT.desc())
                .limit(1)
                .fetchOptional(AUCTION.AUCTION_NUMBER);
    }

    private Optional<String> findHeldOrderNumber(long listingUnitId) {
        return dsl.select(SELLER_ORDER.SELLER_ORDER_NUMBER)
                .from(ORDER_ITEM_UNIT)
                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(ORDER_ITEM_UNIT.ORDER_ITEM_ID))
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .where(ORDER_ITEM_UNIT.LISTING_UNIT_ID.eq(listingUnitId))
                .orderBy(ORDER_ITEM_UNIT.CREATED_AT.desc())
                .limit(1)
                .fetchOptional(SELLER_ORDER.SELLER_ORDER_NUMBER);
    }

    private OffsetDateTime resolveDeadline(Auction a) {
        return a.status() == AuctionStatus.AWAITING_PAYMENT ? a.paymentDueAt() : null;
    }

    private SellerAuctionDetail toSellerAuctionDetail(Auction a, SellerProfile seller) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AuctionItem item = buildAuctionItem(a.catalogVariantId(), a.condition());
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
            Map<Long, String> usernames = dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.ID.in(userIds))
                    .fetchMap(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME);

            for (AuctionBid b : bidRows) {
                String uname = usernames.get(b.bidderId());
                String alias = maskUsername(uname);
                String initials = bidderInitials(uname);
                bids.add(new Bid(alias, initials, false, b.amount(), b.createdAt()));
            }
        }

        // Winner alias if auction ended with winner
        String winnerAlias = null;
        if (a.winnerId() != null) {
            String uname = dsl.select(USER_ACCOUNT.USERNAME)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.ID.eq(a.winnerId()))
                    .fetchOne(USER_ACCOUNT.USERNAME);
            winnerAlias = maskUsername(uname);
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

    private static String formatUnitLabel(UUID publicUid, long unitId) {
        if (publicUid != null) {
            String str = publicUid.toString();
            return "U-" + str.substring(str.length() - 4);
        }
        return "U-%04d".formatted(unitId % 10000);
    }
}
