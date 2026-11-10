package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.BidderStrike.BIDDER_STRIKE;
import static com.pegasus.pegasustcgapi.jooq.tables.BiddingBan.BIDDING_BAN;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Strikes for unpaid wins and the bidding bans they lead to.
 */
@Repository
public class BidderStrikeRepository {

    private final DSLContext dsl;
    private final Clock clock;

    public BidderStrikeRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    // ---------- strikes and bans ----------

    /** Unpaid wins that still count towards a ban. */
    public int countActiveStrikes(long userId) {
        return dsl.fetchCount(BIDDER_STRIKE, BIDDER_STRIKE.USER_ID.eq(userId)
                .and(countableStrike(OffsetDateTime.now(clock))));
    }

    /**
     * A {@code bidder_strike} row that still counts: not cleared, and not struck before a ban
     * that has already ended. A lifted ban ends when it was lifted, not at its original
     * {@code banned_until}, so strikes earned after the lift keep counting.
     */
    public static Condition countableStrike(OffsetDateTime now) {
        var banEnd = DSL.coalesce(BIDDING_BAN.LIFTED_AT, BIDDING_BAN.BANNED_UNTIL);
        return BIDDER_STRIKE.CLEARED_AT.isNull()
                .andNotExists(DSL.selectOne().from(BIDDING_BAN)
                        .where(BIDDING_BAN.USER_ID.eq(BIDDER_STRIKE.USER_ID))
                        .and(banEnd.le(now))
                        .and(banEnd.ge(BIDDER_STRIKE.STRUCK_AT)));
    }

    /** Serializes strikes for one buyer, including strikes from different auctions. */
    public void lockBidder(long userId) {
        dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).forUpdate().fetchSingle();
    }

    public boolean insertStrike(long userId, long auctionId, Long salesOrderId, OffsetDateTime now) {
        return dsl.insertInto(BIDDER_STRIKE)
                .set(BIDDER_STRIKE.USER_ID, userId)
                .set(BIDDER_STRIKE.AUCTION_ID, auctionId)
                .set(BIDDER_STRIKE.SALES_ORDER_ID, salesOrderId)
                .set(BIDDER_STRIKE.STRUCK_AT, now)
                .onConflict(BIDDER_STRIKE.USER_ID, BIDDER_STRIKE.AUCTION_ID).doNothing().execute() == 1;
    }

    public void insertBan(long userId, OffsetDateTime now, OffsetDateTime until) {
        dsl.insertInto(BIDDING_BAN)
                .set(BIDDING_BAN.USER_ID, userId)
                .set(BIDDING_BAN.BANNED_FROM, now)
                .set(BIDDING_BAN.BANNED_UNTIL, until)
                .set(BIDDING_BAN.CREATED_AT, now)
                .execute();
    }

    /** When the user's current bidding ban ends; empty when they may bid. */
    public Optional<OffsetDateTime> findActiveBanUntil(long userId, OffsetDateTime now) {
        return dsl.select(DSL.max(BIDDING_BAN.BANNED_UNTIL))
                .from(BIDDING_BAN)
                .where(BIDDING_BAN.USER_ID.eq(userId)
                        .and(BIDDING_BAN.LIFTED_AT.isNull())
                        .and(BIDDING_BAN.BANNED_UNTIL.gt(now)))
                .fetchOptional(0, OffsetDateTime.class);
    }

    /** Clears both enforcement paths checked by AuctionEligibilityValidator. */
    public void liftBanAndClearStrikes(long userId, long adminId, String reason, OffsetDateTime now) {
        dsl.update(BIDDING_BAN).set(BIDDING_BAN.LIFTED_AT, now)
                .set(BIDDING_BAN.LIFTED_BY, adminId)
                .set(BIDDING_BAN.LIFT_REASON, reason)
                .where(BIDDING_BAN.USER_ID.eq(userId))
                .and(BIDDING_BAN.LIFTED_AT.isNull())
                .and(BIDDING_BAN.BANNED_UNTIL.gt(now)).execute();
        dsl.update(BIDDER_STRIKE).set(BIDDER_STRIKE.CLEARED_AT, now)
                .set(BIDDER_STRIKE.CLEARED_BY, adminId)
                .set(BIDDER_STRIKE.CLEAR_REASON, reason)
                .where(BIDDER_STRIKE.USER_ID.eq(userId))
                .and(BIDDER_STRIKE.CLEARED_AT.isNull()).execute();
    }

    public Optional<String> findLastStrikeCardName(long userId) {
        return dsl.select(CATALOG_PRODUCT.NAME).from(BIDDER_STRIKE)
                .join(AUCTION).on(AUCTION.ID.eq(BIDDER_STRIKE.AUCTION_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(BIDDER_STRIKE.USER_ID.eq(userId))
                .and(countableStrike(OffsetDateTime.now(clock)))
                .orderBy(BIDDER_STRIKE.STRUCK_AT.desc(), BIDDER_STRIKE.ID.desc())
                .limit(1).fetchOptional(CATALOG_PRODUCT.NAME);
    }
}
