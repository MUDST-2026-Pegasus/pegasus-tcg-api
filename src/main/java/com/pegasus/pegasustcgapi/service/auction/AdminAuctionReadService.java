package com.pegasus.pegasustcgapi.service.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;
import static com.pegasus.pegasustcgapi.jooq.tables.BidderStrike.BIDDER_STRIKE;
import static com.pegasus.pegasustcgapi.jooq.tables.BiddingBan.BIDDING_BAN;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.*;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed admin views. Signal and report fields are empty until report storage exists. */
@Service
public class AdminAuctionReadService {
    private final DSLContext dsl;
    private final AuctionRepository auctions;
    private final AuctionItemMapper items;
    private final PlatformSettingService settings;
    private final Clock clock;

    public AdminAuctionReadService(DSLContext dsl, AuctionRepository auctions, AuctionItemMapper items,
            PlatformSettingService settings, Clock clock) {
        this.dsl = dsl;
        this.auctions = auctions;
        this.items = items;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminAuctionPage list(Tab tab, String q, int page, int size) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Paging paging = Paging.of(page, size);
        Condition search = search(q);
        Map<Tab, Long> counts = new EnumMap<>(Tab.class);
        for (Tab candidate : Tab.values()) {
            counts.put(candidate, (long) dsl.fetchCount(AUCTION, search.and(tabCondition(candidate))));
        }
        List<Auction> rows = dsl.selectFrom(AUCTION)
                .where(search.and(tabCondition(tab)))
                .orderBy(AUCTION.CREATED_AT.desc(), AUCTION.ID.desc())
                .limit(paging.size()).offset(paging.offset())
                .fetch(AuctionRepository::toAuction);
        var bundle = items.buildAuctionItems(rows.stream().map(Auction::catalogVariantId).collect(Collectors.toSet()),
                rows.stream().map(Auction::sellerProfileId).collect(Collectors.toSet()));
        Map<Long, String> usernames = sellerUsernames(rows.stream().map(Auction::sellerProfileId).collect(Collectors.toSet()));
        List<AdminAuctionRow> result = rows.stream().map(a -> new AdminAuctionRow(a.id(), a.auctionNumber(),
                bundle.buildAuctionItem(a.catalogVariantId(), a.condition()).card() == null ? null
                        : bundle.buildAuctionItem(a.catalogVariantId(), a.condition()).card().productName(),
                bundle.resolveShopName(a.sellerProfileId()), usernames.get(a.sellerProfileId()),
                a.currentPrice(), a.bidCount(), a.bidderCount(), a.status(), a.endsAt(), a.paymentDueAt(),
                List.of(), 0, a.suspendedAt())).toList();
        long live = dsl.fetchCount(AUCTION, AUCTION.STATUS.eq("LIVE"));
        long ending = dsl.fetchCount(AUCTION, AUCTION.STATUS.eq("LIVE").and(AUCTION.ENDS_AT.le(now.plusHours(1))));
        long awaiting = dsl.fetchCount(AUCTION, AUCTION.STATUS.eq("AWAITING_PAYMENT"));
        long suspended = dsl.fetchCount(AUCTION, AUCTION.STATUS.eq("SUSPENDED")
                .and(AUCTION.SUSPENDED_AT.ge(now.minusDays(30))));
        return new AdminAuctionPage(PageResponse.of(result, paging.page(), paging.size(), counts.get(tab)),
                counts, new Kpis(live, ending, awaiting, suspended), 0, now);
    }

    @Transactional(readOnly = true)
    public AdminAuctionDetail detail(long id) {
        Auction a = auctions.findById(id).orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND));
        var seller = dsl.select(SELLER_PROFILE.ID, SELLER_PROFILE.STATUS, USER_ACCOUNT.USERNAME,
                        USER_ACCOUNT.DISPLAY_NAME)
                .from(SELLER_PROFILE).join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.eq(a.sellerProfileId())).fetchSingle();
        long auctionCount = dsl.fetchCount(AUCTION, AUCTION.SELLER_PROFILE_ID.eq(a.sellerProfileId()));
        long suspensionCount = dsl.fetchCount(AUCTION, AUCTION.SELLER_PROFILE_ID.eq(a.sellerProfileId())
                .and(AUCTION.STATUS.eq("SUSPENDED")));
        List<AdminBid> bids = dsl.select(AUCTION_BID.ID, AUCTION_BID.BIDDER_ID, AUCTION_BID.AMOUNT,
                        AUCTION_BID.CREATED_AT, AUCTION_BID.STATUS, USER_ACCOUNT.USERNAME, USER_ACCOUNT.CREATED_AT)
                .from(AUCTION_BID).join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(AUCTION_BID.BIDDER_ID))
                .where(AUCTION_BID.AUCTION_ID.eq(id))
                .orderBy(AUCTION_BID.CREATED_AT.desc(), AUCTION_BID.ID.desc())
                .fetch(r -> new AdminBid(r.get(AUCTION_BID.ID), r.get(AUCTION_BID.BIDDER_ID),
                        r.get(USER_ACCOUNT.USERNAME), r.get(USER_ACCOUNT.CREATED_AT), 0,
                        r.get(AUCTION_BID.AMOUNT), r.get(AUCTION_BID.CREATED_AT),
                        BidStatus.valueOf(r.get(AUCTION_BID.STATUS)), List.of()));
        List<AdminAction> actions = a.suspendedAt() == null ? List.of() : List.of(new AdminAction(
                "SUSPEND", a.suspendedBy() == null ? null : dsl.select(USER_ACCOUNT.USERNAME).from(USER_ACCOUNT)
                        .where(USER_ACCOUNT.ID.eq(a.suspendedBy())).fetchOne(USER_ACCOUNT.USERNAME),
                a.suspendedReason(), a.suspendedAt()));
        return new AdminAuctionDetail(a.id(), a.auctionNumber(), a.status(),
                items.buildAuctionItem(a.catalogVariantId(), a.condition()),
                new AdminSeller(a.sellerProfileId(), seller.get(USER_ACCOUNT.DISPLAY_NAME),
                        seller.get(USER_ACCOUNT.USERNAME), "VERIFIED".equals(seller.get(SELLER_PROFILE.STATUS)),
                        auctionCount, suspensionCount), a.startingPrice(), a.bidIncrement(), a.currentPrice(),
                a.bidCount(), a.bidderCount(), a.buyNowPrice(), a.originalEndsAt(), a.endsAt(),
                a.extensionCount(), a.sellerNote(), a.sellerNoteUpdatedAt(), List.of(), 0, bids, actions,
                OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public BannedBidderPage bannedBidders(BanTab tab, String q, int page, int size) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Paging paging = Paging.of(page, size);
        Condition hasHistory = DSL.exists(DSL.selectOne().from(BIDDER_STRIKE)
                .where(BIDDER_STRIKE.USER_ID.eq(USER_ACCOUNT.ID)))
                .or(DSL.exists(DSL.selectOne().from(BIDDING_BAN)
                        .where(BIDDING_BAN.USER_ID.eq(USER_ACCOUNT.ID))));
        Condition search = q == null || q.isBlank() ? DSL.trueCondition()
                : USER_ACCOUNT.USERNAME.containsIgnoreCase(q.trim())
                        .or(USER_ACCOUNT.EMAIL.containsIgnoreCase(q.trim()));
        List<BannedBidder> all = dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
                .where(hasHistory.and(search)).orderBy(USER_ACCOUNT.ID.asc()).fetch(USER_ACCOUNT.ID)
                .stream().map(this::bannedBidder).toList();
        Map<BanTab, Long> counts = new EnumMap<>(BanTab.class);
        for (BanTab candidate : BanTab.values()) {
            counts.put(candidate, all.stream().filter(b -> banTab(b, candidate, now)).count());
        }
        List<BannedBidder> matching = all.stream().filter(b -> banTab(b, tab, now)).toList();
        List<BannedBidder> slice = matching.stream().skip(paging.offset()).limit(paging.size()).toList();
        long lifted = dsl.fetchCount(BIDDING_BAN, BIDDING_BAN.LIFTED_AT.ge(now.minusDays(30)));
        long expired = dsl.fetchCount(BIDDING_BAN, BIDDING_BAN.BANNED_UNTIL.ge(now.minusDays(30))
                .and(BIDDING_BAN.BANNED_UNTIL.le(now)).and(BIDDING_BAN.LIFTED_AT.isNull()));
        return new BannedBidderPage(PageResponse.of(slice, paging.page(), paging.size(), counts.get(tab)),
                counts, lifted, expired);
    }

    @Transactional(readOnly = true)
    public BannedBidder bannedBidder(long userId) {
        var user = dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.EMAIL, USER_ACCOUNT.CREATED_AT)
                .from(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).fetchOne();
        if (user == null) throw new NotFoundException(ErrorCode.USER_NOT_FOUND);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Strike> strikes = dsl.select(BIDDER_STRIKE.AUCTION_ID, BIDDER_STRIKE.STRUCK_AT, AUCTION.AUCTION_NUMBER)
                .from(BIDDER_STRIKE).join(AUCTION).on(AUCTION.ID.eq(BIDDER_STRIKE.AUCTION_ID))
                .where(BIDDER_STRIKE.USER_ID.eq(userId))
                .orderBy(BIDDER_STRIKE.STRUCK_AT.desc())
                .fetch(r -> new Strike(r.get(BIDDER_STRIKE.AUCTION_ID), r.get(AUCTION.AUCTION_NUMBER),
                        r.get(BIDDER_STRIKE.STRUCK_AT)));
        int activeStrikes = dsl.fetchCount(BIDDER_STRIKE, BIDDER_STRIKE.USER_ID.eq(userId)
                .and(BIDDER_STRIKE.CLEARED_AT.isNull()));
        var ban = dsl.selectFrom(BIDDING_BAN).where(BIDDING_BAN.USER_ID.eq(userId))
                .orderBy(BIDDING_BAN.CREATED_AT.desc()).limit(1).fetchOne();
        OffsetDateTime bannedUntil = ban != null && ban.getLiftedAt() == null
                && ban.getBannedUntil().isAfter(now) ? ban.getBannedUntil() : null;
        String email = user.get(USER_ACCOUNT.EMAIL);
        String masked = email == null ? null : email.replaceFirst("(?<=.).*(?=@)", "***");
        return new BannedBidder(userId, user.get(USER_ACCOUNT.USERNAME), masked,
                user.get(USER_ACCOUNT.CREATED_AT), activeStrikes,
                settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT), bannedUntil,
                ban == null ? null : ban.getLiftedAt(), strikes);
    }

    private static boolean banTab(BannedBidder b, BanTab tab, OffsetDateTime now) {
        return switch (tab) {
            case BANNED -> b.bannedUntil() != null && b.bannedUntil().isAfter(now);
            case AT_RISK -> b.bannedUntil() == null && b.strikeCount() > 0 && b.strikeCount() < b.strikeLimit();
            case HISTORY -> b.liftedAt() != null || (b.bannedUntil() == null && b.strikeCount() == 0);
        };
    }

    private Condition search(String q) {
        if (q == null || q.isBlank()) return DSL.trueCondition();
        String term = q.trim();
        return AUCTION.AUCTION_NUMBER.containsIgnoreCase(term)
                .or(DSL.exists(DSL.selectOne().from(CATALOG_VARIANT)
                        .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                        .where(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                        .and(CATALOG_PRODUCT.NAME.containsIgnoreCase(term))))
                .or(DSL.exists(DSL.selectOne().from(SELLER_PROFILE)
                        .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                        .where(SELLER_PROFILE.ID.eq(AUCTION.SELLER_PROFILE_ID))
                        .and(USER_ACCOUNT.USERNAME.containsIgnoreCase(term)
                                .or(USER_ACCOUNT.DISPLAY_NAME.containsIgnoreCase(term)))));
    }

    private static Condition tabCondition(Tab tab) {
        return switch (tab) {
            case ALL -> DSL.trueCondition();
            case FLAGGED -> DSL.falseCondition();
            case LIVE -> AUCTION.STATUS.eq(AuctionStatus.LIVE.name());
            case AWAITING_PAYMENT -> AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name());
            case ENDED -> AUCTION.STATUS.in("SOLD", "UNSOLD", "CANCELLED");
            case SUSPENDED -> AUCTION.STATUS.eq(AuctionStatus.SUSPENDED.name());
        };
    }

    private Map<Long, String> sellerUsernames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME).from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(ids)).fetchMap(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME);
    }
}
