package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;

import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionRecord;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Writes to {@code auction} and the loads that go with them: by id, by owner, with a row lock,
 * and the id sweeps the schedulers close from [TCG-614, TCG-615].
 *
 * <p>Business rules (who may bid, when an auction closes, what a strike leads to) belong in the
 * services. Lists, searches and counts live in {@link AuctionQueryRepository} and
 * {@link MyAuctionQueryRepository}; bids in {@link AuctionBidRepository}; strikes and bans in
 * {@link BidderStrikeRepository}; display lookups in {@link AuctionReferenceQueryRepository}.
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

    /** Alias for lockById */
    public Optional<Auction> lock(long id) {
        return lockById(id);
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

    public void attachSalesOrder(long auctionId, long orderId) {
        dsl.update(AUCTION).set(AUCTION.SALES_ORDER_ID, orderId)
                .where(AUCTION.ID.eq(auctionId)).execute();
    }

    public void markSoldForOrder(long auctionId, long orderId) {
        dsl.update(AUCTION).set(AUCTION.STATUS, AuctionStatus.SOLD.name())
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, OffsetDateTime.now(clock))
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.SALES_ORDER_ID.eq(orderId))
                .and(AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name()))
                .execute();
    }

    public boolean recordBuyNowWin(
            long auctionId,
            long winnerId,
            BigDecimal winningAmount,
            OffsetDateTime closedAt,
            OffsetDateTime paymentDueAt) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.AWAITING_PAYMENT.name())
                .set(AUCTION.WINNER_ID, winnerId)
                .set(AUCTION.WINNING_AMOUNT, winningAmount)
                .set(AUCTION.CLOSED_AT, closedAt)
                .set(AUCTION.PAYMENT_DUE_AT, paymentDueAt)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, closedAt)
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.BID_COUNT.eq(0))
                .execute() == 1;
    }

    public List<Long> findExpiredLiveAuctionIds(OffsetDateTime cutoff, int limit) {
        return dsl.select(AUCTION.ID)
                .from(AUCTION)
                .where(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.ENDS_AT.le(cutoff))
                .orderBy(AUCTION.ENDS_AT.asc(), AUCTION.ID.asc())
                .limit(limit)
                .fetch(AUCTION.ID);
    }

    /**
     * Unpaid wins past their deadline with an id above {@code afterId}, in id order, so a sweep
     * pages forward and an auction that keeps failing cannot hold back the ones after it.
     */
    public List<Long> findExpiredPaymentAuctionIds(OffsetDateTime now, long afterId, int limit) {
        return dsl.select(AUCTION.ID).from(AUCTION)
                .where(AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name()))
                .and(AUCTION.ID.gt(afterId))
                .and(AUCTION.PAYMENT_DUE_AT.le(now))
                .and(AUCTION.SALES_ORDER_ID.isNull().or(AUCTION.SALES_ORDER_ID.in(
                        dsl.select(SALES_ORDER.ID).from(SALES_ORDER)
                                .where(SALES_ORDER.STATUS.in("PENDING_PAYMENT", "CANCELLED"))
                                .andNotExists(dsl.selectOne().from(PAYMENT)
                                        .where(PAYMENT.SALES_ORDER_ID.eq(SALES_ORDER.ID))
                                        .and(PAYMENT.STATUS.in("AWAITING_VERIFICATION", "SUCCEEDED"))))))
                .orderBy(AUCTION.ID.asc())
                .limit(limit).fetch(AUCTION.ID);
    }

    public boolean recordUnpaidAuction(long auctionId, OffsetDateTime now) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.UNSOLD.name())
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, now)
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name()))
                .execute() == 1;
    }

    public boolean recordAuctionWon(
            long auctionId,
            long winnerId,
            BigDecimal winningAmount,
            OffsetDateTime closedAt,
            OffsetDateTime paymentDueAt) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.AWAITING_PAYMENT.name())
                .set(AUCTION.WINNER_ID, winnerId)
                .set(AUCTION.WINNING_AMOUNT, winningAmount)
                .set(AUCTION.CLOSED_AT, closedAt)
                .set(AUCTION.PAYMENT_DUE_AT, paymentDueAt)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, closedAt)
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.BID_COUNT.gt(0))
                .execute() == 1;
    }

    public boolean recordAuctionSuspended(long auctionId, String reason, OffsetDateTime now) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.SUSPENDED.name())
                .set(AUCTION.SUSPENDED_REASON, reason)
                .set(AUCTION.SUSPENDED_AT, now)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, now)
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .execute() == 1;
    }

    /** Admin suspension also applies while a winner is awaiting payment. */
    public boolean suspendByAdmin(long auctionId, long adminId, String reason, OffsetDateTime now) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.SUSPENDED.name())
                .set(AUCTION.SUSPENDED_REASON, reason)
                .set(AUCTION.SUSPENDED_BY, adminId)
                .set(AUCTION.SUSPENDED_AT, now)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, now)
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.STATUS.in(AuctionStatus.LIVE.name(), AuctionStatus.AWAITING_PAYMENT.name()))
                .execute() == 1;
    }

    public void cancelAllBids(long auctionId) {
        dsl.update(AUCTION_BID).set(AUCTION_BID.STATUS, BidStatus.CANCELLED.name())
                .where(AUCTION_BID.AUCTION_ID.eq(auctionId)).execute();
    }

    public boolean recordAuctionUnsold(long auctionId, OffsetDateTime closedAt) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.UNSOLD.name())
                .set(AUCTION.CLOSED_AT, closedAt)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .set(AUCTION.UPDATED_AT, closedAt)
                .where(AUCTION.ID.eq(auctionId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.BID_COUNT.eq(0))
                .execute() == 1;
    }

    public Optional<Auction> findOpenAuctionByListingUnit(long listingUnitId) {
        return dsl.selectFrom(AUCTION)
                .where(AUCTION.LISTING_UNIT_ID.eq(listingUnitId))
                .and(AUCTION.STATUS.in(
                        AuctionStatus.LIVE.name(),
                        AuctionStatus.AWAITING_PAYMENT.name()))
                .fetchOptional()
                .map(AuctionRepository::toAuction);
    }

    public boolean updateSettings(long id, long sellerProfileId, BigDecimal buyNowPrice, String sellerNote, OffsetDateTime now) {
        return dsl.update(AUCTION)
                .set(AUCTION.BUY_NOW_PRICE, buyNowPrice)
                .set(AUCTION.SELLER_NOTE, sellerNote)
                .set(AUCTION.SELLER_NOTE_UPDATED_AT, now)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .where(AUCTION.ID.eq(id))
                .and(AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.BID_COUNT.eq(0))
                .execute() == 1;
    }

    public boolean cancel(long id, long sellerProfileId, OffsetDateTime now) {
        return dsl.update(AUCTION)
                .set(AUCTION.STATUS, AuctionStatus.CANCELLED.name())
                .set(AUCTION.CANCELLED_AT, now)
                .set(AUCTION.VERSION, AUCTION.VERSION.plus(1))
                .where(AUCTION.ID.eq(id))
                .and(AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.BID_COUNT.eq(0))
                .execute() == 1;
    }

    // ---------- mapping ----------

    public static Auction toAuction(AuctionRecord r) {
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
}
