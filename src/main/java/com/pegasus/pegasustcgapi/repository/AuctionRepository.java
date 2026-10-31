package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionOffer.AUCTION_OFFER;
import static com.pegasus.pegasustcgapi.jooq.tables.BidderStrike.BIDDER_STRIKE;
import static com.pegasus.pegasustcgapi.jooq.tables.BiddingBan.BIDDING_BAN;

import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionBidRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionOfferRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionRecord;
import com.pegasus.pegasustcgapi.model.Auction;
import com.pegasus.pegasustcgapi.model.AuctionBid;
import com.pegasus.pegasustcgapi.model.AuctionOffer;
import com.pegasus.pegasustcgapi.model.AuctionOfferStatus;
import com.pegasus.pegasustcgapi.model.AuctionStatus;
import com.pegasus.pegasustcgapi.model.BidStatus;
import com.pegasus.pegasustcgapi.model.CardCondition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Shared reads and writes for auctions, bids, runner-up offers, strikes and bans [TCG-614].
 *
 * <p>Only the building blocks every auction card needs live here, so BE-2 to BE-11 do
 * not each write their own. Business rules (who may bid, when an auction closes, what
 * a strike leads to) belong in the services. Add new queries at the end of the section
 * they belong to, to keep merges between cards simple.
 */
@Repository
public class AuctionRepository {

    private final DSLContext dsl;
    private final Clock clock;

    public AuctionRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    // ---------- auction ----------

    /** AUC-YYYY-NNNN. The year is for people; the counter makes it unique and never resets. */
    public String nextAuctionNumber() {
        long counter = dsl.nextval(DSL.sequence(DSL.name("auction_number_seq"), Long.class));
        return "AUC-%d-%04d".formatted(OffsetDateTime.now(clock).getYear(), counter);
    }

    /** What a new auction is opened with. It starts LIVE with no bids, at the starting price. */
    public record NewAuction(
            String auctionNumber,
            long sellerProfileId,
            long listingUnitId,
            long catalogVariantId,
            CardCondition condition,
            BigDecimal startingPrice,
            BigDecimal bidIncrement,
            BigDecimal buyNowPrice,
            OffsetDateTime startedAt,
            OffsetDateTime endsAt,
            String sellerNote,
            Long relistedFromId) {
    }

    /** Throws {@code DuplicateKeyException} when the card is already in an open auction. */
    public long insert(NewAuction a) {
        return dsl.insertInto(AUCTION)
                .set(AUCTION.AUCTION_NUMBER, a.auctionNumber())
                .set(AUCTION.SELLER_PROFILE_ID, a.sellerProfileId())
                .set(AUCTION.LISTING_UNIT_ID, a.listingUnitId())
                .set(AUCTION.CATALOG_VARIANT_ID, a.catalogVariantId())
                .set(AUCTION.CONDITION_CODE, a.condition().name())
                .set(AUCTION.STARTING_PRICE, a.startingPrice())
                .set(AUCTION.BID_INCREMENT, a.bidIncrement())
                .set(AUCTION.BUY_NOW_PRICE, a.buyNowPrice())
                .set(AUCTION.CURRENT_PRICE, a.startingPrice())
                .set(AUCTION.STARTED_AT, a.startedAt())
                .set(AUCTION.ORIGINAL_ENDS_AT, a.endsAt())
                .set(AUCTION.ENDS_AT, a.endsAt())
                .set(AUCTION.SELLER_NOTE, a.sellerNote())
                .set(AUCTION.SELLER_NOTE_UPDATED_AT, a.sellerNote() == null ? null : a.startedAt())
                .set(AUCTION.STATUS, AuctionStatus.LIVE.name())
                .set(AUCTION.RELISTED_FROM_ID, a.relistedFromId())
                .returningResult(AUCTION.ID)
                .fetchSingle(AUCTION.ID);
    }

    public Optional<Auction> findById(long id) {
        return dsl.selectFrom(AUCTION).where(AUCTION.ID.eq(id)).fetchOptional().map(AuctionRepository::toAuction);
    }

    /** The seller's own auction; another shop's is empty, so callers answer 404. */
    public Optional<Auction> findByIdAndSeller(long id, long sellerProfileId) {
        return dsl.selectFrom(AUCTION)
                .where(AUCTION.ID.eq(id).and(AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId)))
                .fetchOptional()
                .map(AuctionRepository::toAuction);
    }

    /**
     * Locks the row until the transaction ends. Bidding, buy-now, closing and every
     * status change take this lock first, so two of them never act on the same auction at once.
     */
    public Optional<Auction> lockById(long id) {
        return dsl.selectFrom(AUCTION).where(AUCTION.ID.eq(id)).forUpdate().fetchOptional()
                .map(AuctionRepository::toAuction);
    }

    /**
     * Moves an auction from one status to another and bumps {@code version}. Returns
     * false when it was no longer in {@code from}, so a racing request loses cleanly.
     * Callers set the columns that go with the new status in the same transaction.
     */
    public boolean transition(long id, AuctionStatus from, AuctionStatus to) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, to.name())
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .where(AUCTION.ID.eq(id).and(AUCTION.STATUS.eq(from.name())))
                .execute() == 1;
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

    public Optional<AuctionBid> findBidByIdempotencyKey(long auctionId, String idempotencyKey) {
        return dsl.selectFrom(AUCTION_BID)
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId).and(AUCTION_BID.IDEMPOTENCY_KEY.eq(idempotencyKey)))
                .fetchOptional()
                .map(AuctionRepository::toBid);
    }

    /** Active bids, newest first. */
    public List<AuctionBid> findBids(long auctionId, int limit, int offset) {
        return dsl.selectFrom(AUCTION_BID)
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId).and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name())))
                .orderBy(AUCTION_BID.CREATED_AT.desc(), AUCTION_BID.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(AuctionRepository::toBid);
    }

    public int countBids(long auctionId) {
        return dsl.fetchCount(AUCTION_BID,
                AUCTION_BID.AUCTION_ID.eq(auctionId).and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name())));
    }

    /** Whether this user already has an active bid here, for {@code bidder_count}. */
    public boolean hasBid(long auctionId, long bidderId) {
        return dsl.fetchExists(AUCTION_BID, AUCTION_BID.AUCTION_ID.eq(auctionId)
                .and(AUCTION_BID.BIDDER_ID.eq(bidderId))
                .and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name())));
    }

    /**
     * The highest active bid by anyone other than {@code excludeBidderId}: the runner-up
     * once the winner has not paid. Empty when only one person bid.
     */
    public Optional<AuctionBid> findHighestBidExcluding(long auctionId, long excludeBidderId) {
        return dsl.selectFrom(AUCTION_BID)
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId)
                        .and(AUCTION_BID.STATUS.eq(BidStatus.ACTIVE.name()))
                        .and(AUCTION_BID.BIDDER_ID.ne(excludeBidderId)))
                .orderBy(AUCTION_BID.AMOUNT.desc(), AUCTION_BID.CREATED_AT.asc())
                .limit(1)
                .fetchOptional()
                .map(AuctionRepository::toBid);
    }

    // ---------- runner-up offers ----------

    /** Throws {@code DuplicateKeyException} when the auction already has an open offer. */
    public long insertOffer(long auctionId, long recipientId, long bidId, BigDecimal amount, OffsetDateTime expiresAt) {
        return dsl.insertInto(AUCTION_OFFER)
                .set(AUCTION_OFFER.AUCTION_ID, auctionId)
                .set(AUCTION_OFFER.RECIPIENT_ID, recipientId)
                .set(AUCTION_OFFER.BID_ID, bidId)
                .set(AUCTION_OFFER.AMOUNT, amount)
                .set(AUCTION_OFFER.STATUS, AuctionOfferStatus.PENDING.name())
                .set(AUCTION_OFFER.EXPIRES_AT, expiresAt)
                .returningResult(AUCTION_OFFER.ID)
                .fetchSingle(AUCTION_OFFER.ID);
    }

    /** The newest offer on an auction, whatever its status. */
    public Optional<AuctionOffer> findLatestOffer(long auctionId) {
        return dsl.selectFrom(AUCTION_OFFER)
                .where(AUCTION_OFFER.AUCTION_ID.eq(auctionId))
                .orderBy(AUCTION_OFFER.CREATED_AT.desc(), AUCTION_OFFER.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(AuctionRepository::toOffer);
    }

    // ---------- strikes and bans ----------

    /** Unpaid wins that still count towards a ban. */
    public int countActiveStrikes(long userId) {
        return dsl.fetchCount(BIDDER_STRIKE, BIDDER_STRIKE.USER_ID.eq(userId).and(BIDDER_STRIKE.CLEARED_AT.isNull()));
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

    // ---------- mapping ----------

    static Auction toAuction(AuctionRecord r) {
        return new Auction(
                r.getId(),
                r.getAuctionNumber(),
                r.getSellerProfileId(),
                r.getListingUnitId(),
                r.getCatalogVariantId(),
                CardCondition.valueOf(r.getConditionCode()),
                r.getStartingPrice(),
                r.getBidIncrement(),
                r.getBuyNowPrice(),
                r.getCurrentPrice(),
                r.getLeadingBidId(),
                r.getBidCount(),
                r.getBidderCount(),
                r.getStartedAt(),
                r.getOriginalEndsAt(),
                r.getEndsAt(),
                r.getExtensionCount(),
                r.getFirstBidAt(),
                r.getSellerNote(),
                r.getSellerNoteUpdatedAt(),
                r.getWinnerId(),
                r.getWinningAmount(),
                r.getClosedAt(),
                r.getPaymentDueAt(),
                r.getDecisionDueAt(),
                r.getSalesOrderId(),
                AuctionStatus.valueOf(r.getStatus()),
                r.getCancelledAt(),
                r.getSuspendedReason(),
                r.getSuspendedBy(),
                r.getSuspendedAt(),
                r.getRelistedFromId(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    static AuctionBid toBid(AuctionBidRecord r) {
        return new AuctionBid(r.getId(), r.getAuctionId(), r.getBidderId(), r.getAmount(),
                BidStatus.valueOf(r.getStatus()), r.getIdempotencyKey(), r.getCreatedAt());
    }

    static AuctionOffer toOffer(AuctionOfferRecord r) {
        return new AuctionOffer(r.getId(), r.getAuctionId(), r.getRecipientId(), r.getBidId(), r.getAmount(),
                AuctionOfferStatus.valueOf(r.getStatus()), r.getExpiresAt(), r.getRespondedAt(), r.getCreatedAt());
    }
}
