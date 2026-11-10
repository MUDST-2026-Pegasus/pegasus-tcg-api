package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;

import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionBidRecord;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes for {@code auction_bid}, and the bid's effect on its auction row [TCG-615].
 */
@Repository
public class AuctionBidRepository {

    private final DSLContext dsl;

    public AuctionBidRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Long> findLeadingBidderId(long auctionId) {
        return dsl.select(AUCTION_BID.BIDDER_ID)
                .from(AUCTION_BID)
                .join(AUCTION).on(AUCTION.LEADING_BID_ID.eq(AUCTION_BID.ID))
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name()))
                .fetchOptional(0, Long.class);
    }

    public Optional<BigDecimal> findMaxBidByUser(long auctionId, long userId) {
        return dsl.select(DSL.max(AUCTION_BID.AMOUNT))
                .from(AUCTION_BID)
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId))
                .and(AUCTION_BID.BIDDER_ID.eq(userId))
                .and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name()))
                .fetchOptional(0, BigDecimal.class);
    }

    // ---------- bids ----------

    /** Throws {@code DuplicateKeyException} when the same idempotency key was already used on this auction. */
    public long insertBid(long auctionId, long bidderId, BigDecimal amount, String idempotencyKey) {
        return dsl.insertInto(AUCTION_BID)
                .set(AUCTION_BID.AUCTION_ID, auctionId)
                .set(AUCTION_BID.BIDDER_ID, bidderId)
                .set(AUCTION_BID.AMOUNT, amount)
                .set(AUCTION_BID.STATUS, BidStatus.ACTIVE.name())
                .set(AUCTION_BID.IDEMPOTENCY_KEY, idempotencyKey)
                .returningResult(AUCTION_BID.ID)
                .fetchSingle(AUCTION_BID.ID);
    }

    /**
     * Records a newly placed bid onto the auction: updates current_price, leading_bid_id,
     * bid_count, bidder_count (if new), ends_at & extension_count (if anti-sniping extended),
     * and bumps version.
     */
    public void recordBid(
            long auctionId,
            long bidId,
            BigDecimal amount,
            boolean isNewBidder,
            OffsetDateTime firstBidAt,
            OffsetDateTime endsAt,
            boolean extended,
            OffsetDateTime now) {
        var step = dsl.update(AUCTION)
                .set(AUCTION.CURRENT_PRICE, amount)
                .set(AUCTION.LEADING_BID_ID, bidId)
                .set(AUCTION.BID_COUNT, AUCTION.BID_COUNT.plus(1))
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, now);

        if (isNewBidder) {
            step.set(AUCTION.BIDDER_COUNT, AUCTION.BIDDER_COUNT.plus(1));
        }
        if (firstBidAt != null) {
            step.set(AUCTION.FIRST_BID_AT, firstBidAt);
        }
        if (extended) {
            step.set(AUCTION.ENDS_AT, endsAt);
            step.set(AUCTION.EXTENSION_COUNT, AUCTION.EXTENSION_COUNT.plus(1));
        }

        step.where(AUCTION.ID.eq(auctionId)).execute();
    }

    public Optional<AuctionBid> findBidById(long id) {
        return dsl.selectFrom(AUCTION_BID).where(AUCTION_BID.ID.eq(id)).fetchOptional().map(AuctionBidRepository::toBid);
    }

    public Optional<AuctionBid> findBidByIdempotencyKey(long auctionId, String idempotencyKey) {
        return dsl.selectFrom(AUCTION_BID)
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId).and(AUCTION_BID.IDEMPOTENCY_KEY.eq(idempotencyKey)))
                .fetchOptional()
                .map(AuctionBidRepository::toBid);
    }

    /** Active bids, newest first. */
    public List<AuctionBid> findBids(long auctionId, int limit, int offset) {
        return findBids(auctionId, false, limit, offset);
    }

    /**
     * Bids newest first; with {@code includeCancelled} also the ones an admin's suspension
     * cancelled, so the seller still sees who bid what [TCG-640].
     */
    public List<AuctionBid> findBids(long auctionId, boolean includeCancelled, int limit, int offset) {
        Condition status = includeCancelled ? DSL.noCondition() : AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name());
        return dsl.selectFrom(AUCTION_BID)
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId).and(status))
                .orderBy(AUCTION_BID.CREATED_AT.desc(), AUCTION_BID.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(AuctionBidRepository::toBid);
    }

    public int countBids(long auctionId) {
        return countBids(auctionId, false);
    }

    /** Bid count; with {@code includeCancelled} also the bids a suspension cancelled. */
    public int countBids(long auctionId, boolean includeCancelled) {
        Condition status = includeCancelled ? DSL.noCondition() : AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name());
        return dsl.fetchCount(AUCTION_BID, AUCTION_BID.AUCTION_ID.eq(auctionId).and(status));
    }

    /** Whether this user already has an active bid here, for {@code bidder_count}. */
    public boolean hasBid(long auctionId, long bidderId) {
        return dsl.fetchExists(AUCTION_BID, AUCTION_BID.AUCTION_ID.eq(auctionId)
                .and(AUCTION_BID.BIDDER_ID.eq(bidderId))
                .and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name())));
    }

    static AuctionBid toBid(AuctionBidRecord r) {
        return new AuctionBid(r.getId(), r.getAuctionId(), r.getBidderId(), r.getAmount(),
                BidStatus.valueOf(r.getStatus()), r.getIdempotencyKey(), r.getCreatedAt());
    }
}
