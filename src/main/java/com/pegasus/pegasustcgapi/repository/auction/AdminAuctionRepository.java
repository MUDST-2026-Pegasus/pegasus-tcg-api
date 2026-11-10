package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;
import static com.pegasus.pegasustcgapi.jooq.tables.BidderStrike.BIDDER_STRIKE;
import static com.pegasus.pegasustcgapi.jooq.tables.BiddingBan.BIDDING_BAN;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminBid;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BanTab;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.Kpis;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.Strike;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.Tab;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Queries behind the admin auction pages [TCG-624]. Every list is paged in SQL. */
@Repository
public class AdminAuctionRepository {

    private final DSLContext dsl;

    public AdminAuctionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ---------- auctions ----------

    /** Every tab's count in one scan. */
    public Map<Tab, Long> countAuctionTabs(String q) {
        List<SelectField<?>> fields = new ArrayList<>();
        for (Tab tab : Tab.values()) fields.add(DSL.count().filterWhere(tabCondition(tab)));
        Record row = dsl.select(fields).from(AUCTION).where(search(q)).fetchSingle();
        Map<Tab, Long> counts = new EnumMap<>(Tab.class);
        for (Tab tab : Tab.values()) counts.put(tab, row.get(tab.ordinal(), Integer.class).longValue());
        return counts;
    }

    public List<Auction> findAuctions(Tab tab, String q, int limit, int offset) {
        return dsl.selectFrom(AUCTION)
                .where(search(q).and(tabCondition(tab)))
                .orderBy(AUCTION.CREATED_AT.desc(), AUCTION.ID.desc())
                .limit(limit).offset(offset)
                .fetch(AuctionRepository::toAuction);
    }

    public Kpis kpis(OffsetDateTime now) {
        Condition live = AUCTION.STATUS.eq(AuctionStatus.LIVE.name());
        return dsl.select(
                        DSL.count().filterWhere(live),
                        DSL.count().filterWhere(live.and(AUCTION.ENDS_AT.le(now.plusHours(1)))),
                        DSL.count().filterWhere(AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name())),
                        DSL.count().filterWhere(AUCTION.STATUS.eq(AuctionStatus.SUSPENDED.name())
                                .and(AUCTION.SUSPENDED_AT.ge(now.minusDays(30)))),
                        DSL.coalesce(DSL.sum(AUCTION.WINNING_AMOUNT).filterWhere(
                                AUCTION.STATUS.eq(AuctionStatus.SOLD.name())
                                        .and(AUCTION.CLOSED_AT.ge(now.minusDays(30)))), BigDecimal.ZERO))
                .from(AUCTION)
                .where(AUCTION.STATUS.in(AuctionStatus.LIVE.name(), AuctionStatus.AWAITING_PAYMENT.name(),
                        AuctionStatus.SUSPENDED.name(), AuctionStatus.SOLD.name()))
                .fetchSingle(r -> new Kpis(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }

    public Map<Long, String> sellerUsernames(Collection<Long> sellerProfileIds) {
        if (sellerProfileIds.isEmpty()) return Map.of();
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME).from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(sellerProfileIds))
                .fetchMap(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME);
    }

    public record SellerSummary(String displayName, String username, boolean verified,
            long auctionCount, long suspensionCount) {}

    public SellerSummary sellerSummary(long sellerProfileId) {
        Field<Integer> auctions = DSL.field(DSL.selectCount().from(AUCTION)
                .where(AUCTION.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID))).as("auctions");
        Field<Integer> suspended = DSL.field(DSL.selectCount().from(AUCTION)
                .where(AUCTION.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID))
                .and(AUCTION.STATUS.eq(AuctionStatus.SUSPENDED.name()))).as("suspended");
        return dsl.select(USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.USERNAME, SELLER_PROFILE.STATUS,
                        auctions, suspended)
                .from(SELLER_PROFILE).join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.eq(sellerProfileId))
                .fetchSingle(r -> new SellerSummary(r.value1(), r.value2(), "VERIFIED".equals(r.value3()),
                        r.value4(), r.value5()));
    }

    public List<AdminBid> findBids(long auctionId) {
        return dsl.select(AUCTION_BID.ID, AUCTION_BID.BIDDER_ID, AUCTION_BID.AMOUNT,
                        AUCTION_BID.CREATED_AT, AUCTION_BID.STATUS, USER_ACCOUNT.USERNAME, USER_ACCOUNT.CREATED_AT)
                .from(AUCTION_BID).join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(AUCTION_BID.BIDDER_ID))
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId))
                .orderBy(AUCTION_BID.CREATED_AT.desc(), AUCTION_BID.ID.desc())
                .fetch(r -> new AdminBid(r.get(AUCTION_BID.ID), r.get(AUCTION_BID.BIDDER_ID),
                        r.get(USER_ACCOUNT.USERNAME), r.get(USER_ACCOUNT.CREATED_AT), 0,
                        r.get(AUCTION_BID.AMOUNT), r.get(AUCTION_BID.CREATED_AT),
                        BidStatus.valueOf(r.get(AUCTION_BID.STATUS)), List.of()));
    }

    public Optional<String> findUsername(long userId) {
        return dsl.select(USER_ACCOUNT.USERNAME).from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId)).fetchOptional(USER_ACCOUNT.USERNAME);
    }

    private static Condition search(String q) {
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
            case ENDED -> AUCTION.STATUS.in(AuctionStatus.SOLD.name(), AuctionStatus.UNSOLD.name(),
                    AuctionStatus.CANCELLED.name());
            case SUSPENDED -> AUCTION.STATUS.eq(AuctionStatus.SUSPENDED.name());
        };
    }

    // ---------- bidders with strikes or bans ----------

    /**
     * One user as the banned-bidder table shows them.
     *
     * @param strikeCount strikes that still count, by the same rule bidding enforces
     * @param bannedUntil end of the ban in force now, if any
     * @param liftedAt    when an admin lifted the user's latest ban
     */
    public record BidderSummary(long userId, String username, String email, OffsetDateTime memberSince,
            int strikeCount, OffsetDateTime bannedUntil, OffsetDateTime liftedAt) {}

    public Optional<BidderSummary> findBidder(long userId, OffsetDateTime now) {
        Table<?> t = bidders(USER_ACCOUNT.ID.eq(userId), now);
        return dsl.selectFrom(t).fetchOptional(r -> toBidder(r, t));
    }

    public List<BidderSummary> findBidders(BanTab tab, String q, int strikeLimit, OffsetDateTime now,
            int limit, int offset) {
        Table<?> t = bidders(hasHistory().and(bidderSearch(q)), now);
        return dsl.selectFrom(t)
                .where(banTab(t, tab, strikeLimit))
                .orderBy(t.field(USER_ACCOUNT.ID).asc())
                .limit(limit).offset(offset)
                .fetch(r -> toBidder(r, t));
    }

    public Map<BanTab, Long> countBidderTabs(String q, int strikeLimit, OffsetDateTime now) {
        Table<?> t = bidders(hasHistory().and(bidderSearch(q)), now);
        List<SelectField<?>> fields = new ArrayList<>();
        for (BanTab tab : BanTab.values()) fields.add(DSL.count().filterWhere(banTab(t, tab, strikeLimit)));
        Record row = dsl.select(fields).from(t).fetchSingle();
        Map<BanTab, Long> counts = new EnumMap<>(BanTab.class);
        for (BanTab tab : BanTab.values()) counts.put(tab, row.get(tab.ordinal(), Integer.class).longValue());
        return counts;
    }

    /** Every strike of these users, newest first, cleared ones included. */
    public Map<Long, List<Strike>> findStrikes(Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<Long, List<Strike>> byUser = new LinkedHashMap<>();
        dsl.select(BIDDER_STRIKE.USER_ID, BIDDER_STRIKE.AUCTION_ID, BIDDER_STRIKE.STRUCK_AT, AUCTION.AUCTION_NUMBER)
                .from(BIDDER_STRIKE).join(AUCTION).on(AUCTION.ID.eq(BIDDER_STRIKE.AUCTION_ID))
                .where(BIDDER_STRIKE.USER_ID.in(userIds))
                .orderBy(BIDDER_STRIKE.STRUCK_AT.desc(), BIDDER_STRIKE.ID.desc())
                .forEach(r -> byUser.computeIfAbsent(r.value1(), id -> new ArrayList<>())
                        .add(new Strike(r.value2(), r.value4(), r.value3())));
        return byUser;
    }

    public record BanStats(long liftedLast30Days, long expiredLast30Days) {}

    public BanStats banStats(OffsetDateTime now) {
        OffsetDateTime from = now.minusDays(30);
        return dsl.select(
                        DSL.count().filterWhere(BIDDING_BAN.LIFTED_AT.ge(from)),
                        DSL.count().filterWhere(BIDDING_BAN.BANNED_UNTIL.ge(from)
                                .and(BIDDING_BAN.BANNED_UNTIL.le(now)).and(BIDDING_BAN.LIFTED_AT.isNull())))
                .from(BIDDING_BAN)
                .fetchSingle(r -> new BanStats(r.value1(), r.value2()));
    }

    private static final String STRIKES = "strike_count";
    private static final String BANNED_UNTIL = "active_banned_until";
    private static final String LIFTED_AT = "latest_lifted_at";

    /** Users matching {@code where}, with their strike and ban state as columns, so tabs filter in SQL. */
    private static Table<?> bidders(Condition where, OffsetDateTime now) {
        Field<Integer> strikes = DSL.field(DSL.selectCount().from(BIDDER_STRIKE)
                .where(BIDDER_STRIKE.USER_ID.eq(USER_ACCOUNT.ID))
                .and(BidderStrikeRepository.countableStrike(now))).as(STRIKES);
        // Same rule as BidderStrikeRepository.findActiveBanUntil.
        Field<OffsetDateTime> bannedUntil = DSL.field(DSL.select(DSL.max(BIDDING_BAN.BANNED_UNTIL))
                .from(BIDDING_BAN)
                .where(BIDDING_BAN.USER_ID.eq(USER_ACCOUNT.ID))
                .and(BIDDING_BAN.LIFTED_AT.isNull())
                .and(BIDDING_BAN.BANNED_UNTIL.gt(now))).as(BANNED_UNTIL);
        Field<OffsetDateTime> liftedAt = DSL.field(DSL.select(BIDDING_BAN.LIFTED_AT).from(BIDDING_BAN)
                .where(BIDDING_BAN.USER_ID.eq(USER_ACCOUNT.ID))
                .orderBy(BIDDING_BAN.CREATED_AT.desc(), BIDDING_BAN.ID.desc())
                .limit(1)).as(LIFTED_AT);
        return DSL.select(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.EMAIL, USER_ACCOUNT.CREATED_AT,
                        strikes, bannedUntil, liftedAt)
                .from(USER_ACCOUNT).where(where).asTable("bidder");
    }

    private static Condition banTab(Table<?> t, BanTab tab, int strikeLimit) {
        Field<Integer> strikes = t.field(STRIKES, Integer.class);
        Field<OffsetDateTime> bannedUntil = t.field(BANNED_UNTIL, OffsetDateTime.class);
        Field<OffsetDateTime> liftedAt = t.field(LIFTED_AT, OffsetDateTime.class);
        return switch (tab) {
            case BANNED -> bannedUntil.isNotNull();
            case AT_RISK -> bannedUntil.isNull().and(strikes.gt(0)).and(strikes.lt(strikeLimit));
            case HISTORY -> liftedAt.isNotNull().or(bannedUntil.isNull().and(strikes.eq(0)));
        };
    }

    private static Condition hasHistory() {
        return DSL.exists(DSL.selectOne().from(BIDDER_STRIKE).where(BIDDER_STRIKE.USER_ID.eq(USER_ACCOUNT.ID)))
                .or(DSL.exists(DSL.selectOne().from(BIDDING_BAN).where(BIDDING_BAN.USER_ID.eq(USER_ACCOUNT.ID))));
    }

    private static Condition bidderSearch(String q) {
        if (q == null || q.isBlank()) return DSL.trueCondition();
        String term = q.trim();
        return USER_ACCOUNT.USERNAME.containsIgnoreCase(term).or(USER_ACCOUNT.EMAIL.containsIgnoreCase(term));
    }

    private static BidderSummary toBidder(Record r, Table<?> t) {
        Integer strikes = r.get(t.field(STRIKES, Integer.class));
        return new BidderSummary(r.get(t.field(USER_ACCOUNT.ID)), r.get(t.field(USER_ACCOUNT.USERNAME)),
                r.get(t.field(USER_ACCOUNT.EMAIL)), r.get(t.field(USER_ACCOUNT.CREATED_AT)),
                strikes == null ? 0 : strikes,
                r.get(t.field(BANNED_UNTIL, OffsetDateTime.class)), r.get(t.field(LIFTED_AT, OffsetDateTime.class)));
    }
}
