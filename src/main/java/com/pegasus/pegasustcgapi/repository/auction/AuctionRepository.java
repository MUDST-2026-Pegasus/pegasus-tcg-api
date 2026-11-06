package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.AuctionBid.AUCTION_BID;
import static com.pegasus.pegasustcgapi.jooq.tables.BidderStrike.BIDDER_STRIKE;
import static com.pegasus.pegasustcgapi.jooq.tables.BiddingBan.BIDDING_BAN;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Filter;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.MyTab;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Sort;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.Kpis;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.MarketGuide;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import java.util.Collection;
import java.util.HashMap;
import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionBidRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.AuctionRecord;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.BidStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Shared reads and writes for auctions, bids, strikes and bans [TCG-614, TCG-615, TCG-616].
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

    public record SellerAuctionFilter(
            long sellerProfileId,
            AuctionStatus status,
            String query,
            int limit,
            int offset) {
    }

    public List<Auction> findBySeller(SellerAuctionFilter filter) {
        var cond = AUCTION.SELLER_PROFILE_ID.eq(filter.sellerProfileId());
        if (filter.status() != null) {
            cond = cond.and(AUCTION.STATUS.eq(filter.status().name()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String q = filter.query().trim();
            cond = cond.and(AUCTION.AUCTION_NUMBER.containsIgnoreCase(q)
                    .or(DSL.exists(
                            DSL.selectOne()
                                    .from(CATALOG_VARIANT)
                                    .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                                    .where(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                                    .and(CATALOG_PRODUCT.NAME.containsIgnoreCase(q)))));
        }
        return dsl.selectFrom(AUCTION)
                .where(cond)
                .orderBy(AUCTION.CREATED_AT.desc(), AUCTION.ID.desc())
                .limit(filter.limit())
                .offset(filter.offset())
                .fetch(AuctionRepository::toAuction);
    }

    public long countBySeller(long sellerProfileId, AuctionStatus status, String query) {
        var cond = AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId);
        if (status != null) {
            cond = cond.and(AUCTION.STATUS.eq(status.name()));
        }
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            cond = cond.and(AUCTION.AUCTION_NUMBER.containsIgnoreCase(q)
                    .or(DSL.exists(
                            DSL.selectOne()
                                    .from(CATALOG_VARIANT)
                                    .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                                    .where(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                                    .and(CATALOG_PRODUCT.NAME.containsIgnoreCase(q)))));
        }
        return dsl.fetchCount(AUCTION, cond);
    }

    public Map<AuctionStatus, Long> statusCounts(long sellerProfileId) {
        Map<AuctionStatus, Long> counts = new EnumMap<>(AuctionStatus.class);
        for (AuctionStatus s : AuctionStatus.values()) {
            counts.put(s, 0L);
        }
        dsl.select(AUCTION.STATUS, DSL.count())
                .from(AUCTION)
                .where(AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .groupBy(AUCTION.STATUS)
                .fetch()
                .forEach(r -> {
                    try {
                        AuctionStatus st = AuctionStatus.valueOf(r.get(AUCTION.STATUS));
                        counts.put(st, r.get(1, Long.class));
                    } catch (Exception ignored) {
                    }
                });
        return counts;
    }

    public Kpis calculateKpis(long sellerProfileId, OffsetDateTime now) {
        OffsetDateTime thirtyDaysAgo = now.minusDays(30);

        var liveCountField = DSL.count().filterWhere(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()));
        var liveWithBidsField = DSL.count().filterWhere(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()).and(AUCTION.BID_COUNT.gt(0)));
        var awaitingPaymentCountField = DSL.count().filterWhere(AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name()));
        var nextPaymentDueAtField = DSL.min(AUCTION.PAYMENT_DUE_AT).filterWhere(AUCTION.STATUS.eq(AuctionStatus.AWAITING_PAYMENT.name()));
        var soldLast30DaysField = DSL.coalesce(
                DSL.sum(AUCTION.WINNING_AMOUNT).filterWhere(AUCTION.STATUS.eq(AuctionStatus.SOLD.name()).and(AUCTION.CLOSED_AT.ge(thirtyDaysAgo))),
                BigDecimal.ZERO);
        var closedLast30DaysField = DSL.count().filterWhere(
                AUCTION.STATUS.in(AuctionStatus.SOLD.name(), AuctionStatus.UNSOLD.name()).and(AUCTION.CLOSED_AT.ge(thirtyDaysAgo)));

        var record = dsl.select(
                liveCountField,
                liveWithBidsField,
                awaitingPaymentCountField,
                nextPaymentDueAtField,
                soldLast30DaysField,
                closedLast30DaysField)
                .from(AUCTION)
                .where(AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchOne();

        if (record == null) {
            return new Kpis(0L, 0L, 0L, null, BigDecimal.ZERO, 0L, null);
        }

        long liveCount = record.get(liveCountField).longValue();
        long liveWithBids = record.get(liveWithBidsField).longValue();
        long awaitingPaymentCount = record.get(awaitingPaymentCountField).longValue();
        OffsetDateTime nextPaymentDueAt = record.get(nextPaymentDueAtField);
        BigDecimal soldLast30Days = record.get(soldLast30DaysField);
        long closedLast30Days = record.get(closedLast30DaysField).longValue();

        return new Kpis(liveCount, liveWithBids, awaitingPaymentCount, nextPaymentDueAt,
                soldLast30Days != null ? soldLast30Days : BigDecimal.ZERO, closedLast30Days, null);
    }

    // ---------- public catalog [TCG-616] ----------

    public record PublicAuctionQuery(
            Filter filter,
            String query,
            Short gameId,
            Long sellerId,
            String sellerUsername,
            Long catalogVariantId,
            Sort sort,
            int limit,
            int offset,
            OffsetDateTime now) {
    }

    public Condition buildPublicConditions(PublicAuctionQuery query) {
        Condition cond = AUCTION.STATUS.eq(AuctionStatus.LIVE.name());
        if (query.now() != null) {
            cond = cond.and(AUCTION.ENDS_AT.gt(query.now()));
        }

        if (query.filter() != null) {
            switch (query.filter()) {
                case ENDING_SOON -> cond = cond.and(AUCTION.ENDS_AT.le(query.now().plusHours(1)).and(AUCTION.ENDS_AT.gt(query.now())));
                case BUY_NOW -> cond = cond.and(AUCTION.BUY_NOW_PRICE.isNotNull()).and(AUCTION.BID_COUNT.eq(0));
                case NO_BIDS -> cond = cond.and(AUCTION.BID_COUNT.eq(0));
                case NEW -> cond = cond.and(AUCTION.STARTED_AT.ge(query.now().minusHours(24)));
            }
        }

        if (query.gameId() != null) {
            cond = cond.and(DSL.exists(
                    DSL.selectOne()
                            .from(CATALOG_VARIANT)
                            .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                            .where(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                            .and(CATALOG_PRODUCT.GAME_ID.eq(query.gameId()))));
        }

        if (query.sellerId() != null) {
            cond = cond.and(AUCTION.SELLER_PROFILE_ID.eq(query.sellerId()));
        }
        if (query.sellerUsername() != null && !query.sellerUsername().isBlank()) {
            String username = query.sellerUsername().trim();
            cond = cond.and(DSL.exists(DSL.selectOne().from(SELLER_PROFILE)
                    .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                    .where(SELLER_PROFILE.ID.eq(AUCTION.SELLER_PROFILE_ID))
                    .and(USER_ACCOUNT.USERNAME.eq(username))));
        }

        if (query.catalogVariantId() != null) {
            cond = cond.and(AUCTION.CATALOG_VARIANT_ID.eq(query.catalogVariantId()));
        }

        if (query.query() != null && !query.query().isBlank()) {
            String q = query.query().trim();
            cond = cond.and(AUCTION.AUCTION_NUMBER.containsIgnoreCase(q)
                    .or(DSL.exists(
                            DSL.selectOne()
                                    .from(CATALOG_VARIANT)
                                    .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                                    .where(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                                    .and(CATALOG_PRODUCT.NAME.containsIgnoreCase(q)))));
        }

        return cond;
    }

    public OrderField<?>[] buildSortOrder(Sort sort) {
        Sort s = sort != null ? sort : Sort.ENDING_SOON;
        return switch (s) {
            case ENDING_SOON -> new OrderField<?>[]{AUCTION.ENDS_AT.asc(), AUCTION.ID.asc()};
            case NEWEST -> new OrderField<?>[]{AUCTION.STARTED_AT.desc(), AUCTION.ID.desc()};
            case PRICE_ASC -> new OrderField<?>[]{AUCTION.CURRENT_PRICE.asc(), AUCTION.ENDS_AT.asc()};
            case PRICE_DESC -> new OrderField<?>[]{AUCTION.CURRENT_PRICE.desc(), AUCTION.ENDS_AT.asc()};
            case MOST_BIDS -> new OrderField<?>[]{AUCTION.BID_COUNT.desc(), AUCTION.CURRENT_PRICE.desc()};
        };
    }

    public List<Auction> findPublicCatalog(PublicAuctionQuery query) {
        return dsl.selectFrom(AUCTION)
                .where(buildPublicConditions(query))
                .orderBy(buildSortOrder(query.sort()))
                .limit(query.limit())
                .offset(query.offset())
                .fetch(AuctionRepository::toAuction);
    }

    public long countPublicCatalog(PublicAuctionQuery query) {
        return dsl.fetchCount(AUCTION, buildPublicConditions(query));
    }

    public Map<Filter, Long> publicFilterCounts(OffsetDateTime now) {
        OffsetDateTime effectiveNow = now != null ? now : OffsetDateTime.now(clock);
        Map<Filter, Long> counts = new EnumMap<>(Filter.class);
        var baseCond = AUCTION.STATUS.eq(AuctionStatus.LIVE.name())
                .and(AUCTION.ENDS_AT.gt(effectiveNow));
        counts.put(Filter.ENDING_SOON, (long) dsl.fetchCount(AUCTION, baseCond.and(AUCTION.ENDS_AT.le(effectiveNow.plusHours(1))).and(AUCTION.ENDS_AT.gt(effectiveNow))));
        counts.put(Filter.BUY_NOW, (long) dsl.fetchCount(AUCTION, baseCond.and(AUCTION.BUY_NOW_PRICE.isNotNull()).and(AUCTION.BID_COUNT.eq(0))));
        counts.put(Filter.NO_BIDS, (long) dsl.fetchCount(AUCTION, baseCond.and(AUCTION.BID_COUNT.eq(0))));
        counts.put(Filter.NEW, (long) dsl.fetchCount(AUCTION, baseCond.and(AUCTION.STARTED_AT.ge(effectiveNow.minusHours(24)))));
        return counts;
    }

    public long countLiveAuctions(OffsetDateTime now) {
        var cond = AUCTION.STATUS.eq(AuctionStatus.LIVE.name());
        if (now != null) {
            cond = cond.and(AUCTION.ENDS_AT.gt(now));
        }
        return dsl.fetchCount(AUCTION, cond);
    }

    /** Current live auctions across every printing of one catalogue product. */
    public ProductAuctionStats productStats(long productId, OffsetDateTime now) {
        return dsl.select(DSL.count(), DSL.min(AUCTION.CURRENT_PRICE), DSL.min(AUCTION.ENDS_AT))
                .from(AUCTION)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(AUCTION.CATALOG_VARIANT_ID))
                .where(CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(productId))
                .and(AUCTION.STATUS.eq(AuctionStatus.LIVE.name()))
                .and(AUCTION.ENDS_AT.gt(now))
                .fetchSingle(r -> new ProductAuctionStats(r.value1(), r.value2(), r.value3()));
    }

    public record ProductAuctionStats(int openCount, BigDecimal lowestCurrentPrice,
            OffsetDateTime nearestEndAt) {}

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

    public long getSellerCompletedSalesCount(long sellerProfileId) {
        return dsl.fetchCount(SELLER_ORDER,
                SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId)
                        .and(SELLER_ORDER.STATUS.ne("CANCELLED")));
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
        return dsl.selectFrom(AUCTION_BID).where(AUCTION_BID.ID.eq(id)).fetchOptional().map(AuctionRepository::toBid);
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

    public record SellerInfo(String shopName, String username, BigDecimal rating) {}

    public Optional<SellerInfo> findSellerInfo(long sellerProfileId) {
        var row = dsl.select(
                USER_ACCOUNT.DISPLAY_NAME,
                USER_ACCOUNT.USERNAME,
                SELLER_RATING_SUMMARY.RATING_AVG)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(SELLER_RATING_SUMMARY).on(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID))
                .where(SELLER_PROFILE.ID.eq(sellerProfileId))
                .fetchOne();

        if (row == null) {
            return Optional.empty();
        }
        String shopName = row.get(USER_ACCOUNT.DISPLAY_NAME) != null ? row.get(USER_ACCOUNT.DISPLAY_NAME)
                : (row.get(USER_ACCOUNT.USERNAME) != null ? row.get(USER_ACCOUNT.USERNAME) : "Shop");
        String username = row.get(USER_ACCOUNT.USERNAME) != null ? row.get(USER_ACCOUNT.USERNAME) : "seller";
        BigDecimal rating = row.get(SELLER_RATING_SUMMARY.RATING_AVG);
        return Optional.of(new SellerInfo(shopName, username, rating));
    }

    public Map<Long, String> findUsernames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        return dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.in(userIds))
                .fetchMap(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME);
    }

    public Optional<String> findUsername(long userId) {
        return Optional.ofNullable(findUsernames(List.of(userId)).get(userId));
    }

    public record VariantConditionKey(long catalogVariantId, String conditionCode) {}

    public Map<VariantConditionKey, MarketGuide> findMarketGuides(Collection<Long> catalogVariantIds) {
        if (catalogVariantIds == null || catalogVariantIds.isEmpty()) {
            return Map.of();
        }

        var medianField = DSL.field("percentile_disc(0.5) WITHIN GROUP (ORDER BY {0})", BigDecimal.class, LISTING.PRICE);
        var countField = DSL.count();
        var minField = DSL.min(LISTING.PRICE);
        var maxField = DSL.max(LISTING.PRICE);

        var records = dsl.select(
                LISTING.CATALOG_VARIANT_ID,
                LISTING.CONDITION_CODE,
                countField,
                minField,
                maxField,
                medianField)
                .from(LISTING)
                .where(LISTING.CATALOG_VARIANT_ID.in(catalogVariantIds))
                .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()))
                .and(LISTING.DELETED_AT.isNull())
                .groupBy(LISTING.CATALOG_VARIANT_ID, LISTING.CONDITION_CODE)
                .fetch();

        Map<VariantConditionKey, MarketGuide> result = new HashMap<>();
        for (var r : records) {
            long variantId = r.get(LISTING.CATALOG_VARIANT_ID);
            String condition = r.get(LISTING.CONDITION_CODE);
            int count = r.get(countField);
            BigDecimal min = r.get(minField);
            BigDecimal max = r.get(maxField);
            BigDecimal median = r.get(medianField);
            result.put(new VariantConditionKey(variantId, condition), new MarketGuide(count, min, median, max));
        }
        return result;
    }

    public Map<Long, String> findHeldAuctionNumbers(Collection<Long> listingUnitIds) {
        if (listingUnitIds == null || listingUnitIds.isEmpty()) {
            return Map.of();
        }
        var records = dsl.select(AUCTION.LISTING_UNIT_ID, AUCTION.AUCTION_NUMBER)
                .distinctOn(AUCTION.LISTING_UNIT_ID)
                .from(AUCTION)
                .where(AUCTION.LISTING_UNIT_ID.in(listingUnitIds))
                .and(AUCTION.STATUS.in(
                        AuctionStatus.LIVE.name(),
                        AuctionStatus.AWAITING_PAYMENT.name()))
                .orderBy(AUCTION.LISTING_UNIT_ID, AUCTION.CREATED_AT.desc())
                .fetch();

        Map<Long, String> result = new HashMap<>();
        for (var r : records) {
            result.put(r.get(AUCTION.LISTING_UNIT_ID), r.get(AUCTION.AUCTION_NUMBER));
        }
        return result;
    }

    public Map<Long, String> findShopNames(Collection<Long> sellerProfileIds) {
        if (sellerProfileIds == null || sellerProfileIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.USERNAME)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(sellerProfileIds))
                .fetchMap(
                        r -> r.get(SELLER_PROFILE.ID),
                        r -> {
                            String displayName = r.get(USER_ACCOUNT.DISPLAY_NAME);
                            if (displayName != null && !displayName.isBlank()) {
                                return displayName;
                            }
                            String username = r.get(USER_ACCOUNT.USERNAME);
                            return username != null && !username.isBlank() ? username : "Shop";
                        }
                );
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

    static AuctionBid toBid(AuctionBidRecord r) {
        return new AuctionBid(r.getId(), r.getAuctionId(), r.getBidderId(), r.getAmount(),
                BidStatus.valueOf(r.getStatus()), r.getIdempotencyKey(), r.getCreatedAt());
    }
}
