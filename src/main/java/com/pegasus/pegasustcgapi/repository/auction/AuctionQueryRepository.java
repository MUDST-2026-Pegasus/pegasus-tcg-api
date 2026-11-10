package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Filter;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.Sort;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.Kpis;
import java.util.Collection;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only lists, searches and counts over {@code auction}: the seller's desk and the
 * public catalogue [TCG-616].
 */
@Repository
public class AuctionQueryRepository {

    private final DSLContext dsl;
    private final Clock clock;

    public AuctionQueryRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    /**
     * The shop's sub-order id for each buyer order, keyed by {@code sales_order.id}. Auctions store the
     * buyer order, but the seller's order pages are keyed by {@code seller_order.id}.
     */
    public Map<Long, Long> findSellerOrderIds(long sellerProfileId, Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SELLER_ORDER.SALES_ORDER_ID, SELLER_ORDER.ID)
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(SELLER_ORDER.SALES_ORDER_ID.in(salesOrderIds))
                .fetchMap(SELLER_ORDER.SALES_ORDER_ID, SELLER_ORDER.ID);
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
}
