package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.MyTab;
import java.util.Collection;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries behind the buyer's own auctions page: the ones they bid on or won.
 */
@Repository
public class MyAuctionQueryRepository {

    private final DSLContext dsl;

    public MyAuctionQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ---------- buyer's auctions ----------

    private Condition myAuctionCondition(long userId, MyTab tab) {
        return myAuctionParticipation(userId).and(myAuctionTabCondition(userId, tab));
    }

    private Condition myAuctionParticipation(long userId) {
        return AUCTION.WINNER_ID.eq(userId).or(DSL.exists(
                DSL.selectOne().from(AUCTION_BID)
                        .where(AUCTION_BID.AUCTION_ID.eq(AUCTION.ID))
                        .and(AUCTION_BID.BIDDER_ID.eq(userId))));
    }

    private Condition myAuctionTabCondition(long userId, MyTab tab) {
        Condition winner = AUCTION.WINNER_ID.eq(userId);
        return switch (tab) {
            case ALL -> DSL.trueCondition();
            case BIDDING -> AUCTION.STATUS.eq(AuctionStatus.LIVE.name());
            case TO_PAY -> AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name()).and(winner);
            case WON -> AUCTION.STATUS.eq(AuctionStatus.SOLD.name()).and(winner);
            case LOST -> AUCTION.STATUS.notIn(AuctionStatus.LIVE.name(), AuctionStatus.AWAITING_PAYMENT.name(),
                    AuctionStatus.SOLD.name())
                    .or(AUCTION.STATUS.in(AuctionStatus.AWAITING_PAYMENT.name(), AuctionStatus.SOLD.name())
                            .and(AUCTION.WINNER_ID.ne(userId).or(AUCTION.WINNER_ID.isNull())));
        };
    }

    public List<Auction> findMyAuctions(long userId, MyTab tab, int limit, int offset) {
        return dsl.selectFrom(AUCTION)
                .where(myAuctionCondition(userId, tab))
                .orderBy(AUCTION.UPDATED_AT.desc(), AUCTION.ID.desc())
                .limit(limit).offset(offset)
                .fetch(AuctionRepository::toAuction);
    }

    public long countMyAuctions(long userId, MyTab tab) {
        return dsl.fetchCount(AUCTION, myAuctionCondition(userId, tab));
    }

    /** Counts every tab in one scan of the buyer's auctions. */
    public Map<MyTab, Long> countMyAuctionTabs(long userId) {
        var bidding = DSL.count().filterWhere(myAuctionTabCondition(userId, MyTab.BIDDING));
        var toPay = DSL.count().filterWhere(myAuctionTabCondition(userId, MyTab.TO_PAY));
        var won = DSL.count().filterWhere(myAuctionTabCondition(userId, MyTab.WON));
        var lost = DSL.count().filterWhere(myAuctionTabCondition(userId, MyTab.LOST));
        var counts = dsl.select(DSL.count(), bidding, toPay, won, lost)
                .from(AUCTION).where(myAuctionParticipation(userId)).fetchSingle();
        Map<MyTab, Long> result = new EnumMap<>(MyTab.class);
        result.put(MyTab.ALL, counts.value1().longValue());
        result.put(MyTab.BIDDING, counts.value2().longValue());
        result.put(MyTab.TO_PAY, counts.value3().longValue());
        result.put(MyTab.WON, counts.value4().longValue());
        result.put(MyTab.LOST, counts.value5().longValue());
        return result;
    }

    public Map<Long, BigDecimal> findMyHighestBids(long userId, Collection<Long> auctionIds) {
        if (auctionIds.isEmpty()) return Map.of();
        var maxAmount = DSL.max(AUCTION_BID.AMOUNT);
        return dsl.select(AUCTION_BID.AUCTION_ID, maxAmount).from(AUCTION_BID)
                .where(AUCTION_BID.BIDDER_ID.eq(userId))
                .and(AUCTION_BID.AUCTION_ID.in(auctionIds))
                .groupBy(AUCTION_BID.AUCTION_ID)
                .fetchMap(AUCTION_BID.AUCTION_ID, maxAmount);
    }

    public Set<Long> findMyLeadingAuctionIds(long userId, Collection<Long> auctionIds) {
        if (auctionIds.isEmpty()) return Set.of();
        return dsl.select(AUCTION.ID).from(AUCTION)
                .join(AUCTION_BID).on(AUCTION.LEADING_BID_ID.eq(AUCTION_BID.ID))
                .where(AUCTION.ID.in(auctionIds))
                .and(AUCTION_BID.BIDDER_ID.eq(userId))
                .fetchSet(AUCTION.ID);
    }
}
