package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.VariantMarketStat.VARIANT_MARKET_STAT;

import com.pegasus.pegasustcgapi.jooq.tables.records.VariantMarketStatRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.MarketStat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Computes and reads {@code variant_market_stat}, the daily market per printing and condition [RQ-5]. */
@Repository
public class VariantMarketStatRepository {

    private final DSLContext dsl;

    public VariantMarketStatRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Writes the day's row for every market that has an active listing, in one
     * statement.
     *
     * <p>The first computation of a day wins; running again that day inserts
     * nothing. That is what makes the nightly job safe to repeat: the second run
     * reads the same median as the first — not one taken from prices the first run
     * already moved — and so arrives at the same prices and changes nothing.
     *
     * <p>Plain SQL because {@code percentile_cont} returns double precision and has
     * to be cast back to numeric before it is rounded to money, which reads far
     * more plainly here than through the DSL. The sample is what a buyer can see:
     * ACTIVE listings of verified sellers who are not on vacation.
     *
     * @param soldSince the start of the 30-day window for {@code sold_quantity_30d}
     * @return how many markets got a row
     */
    public int computeDay(LocalDate statDate, OffsetDateTime computedAt, OffsetDateTime soldSince) {
        return dsl.execute("""
                INSERT INTO variant_market_stat (
                       catalog_variant_id, condition_code, stat_date,
                       median_price, avg_price, min_price, max_price, p25_price, p75_price,
                       active_listing_count, sold_quantity_30d, sample_size, computed_at)
                SELECT l.catalog_variant_id,
                       l.condition_code,
                       CAST(? AS date),
                       round(CAST(percentile_cont(0.5)  WITHIN GROUP (ORDER BY l.price) AS numeric), 2),
                       round(avg(l.price), 2),
                       min(l.price),
                       max(l.price),
                       round(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY l.price) AS numeric), 2),
                       round(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY l.price) AS numeric), 2),
                       count(*),
                       coalesce(max(sold.quantity), 0),
                       count(*),
                       CAST(? AS timestamptz)
                  FROM listing l
                  JOIN seller_profile sp ON sp.id = l.seller_profile_id
                  LEFT JOIN (
                        SELECT oi.catalog_variant_id,
                               oi.condition_snapshot AS condition_code,
                               CAST(sum(oi.quantity) AS int) AS quantity
                          FROM order_item oi
                          JOIN seller_order so ON so.id = oi.seller_order_id
                         WHERE so.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
                           AND oi.created_at >= CAST(? AS timestamptz)
                         GROUP BY oi.catalog_variant_id, oi.condition_snapshot
                       ) sold
                    ON sold.catalog_variant_id = l.catalog_variant_id
                   AND sold.condition_code = l.condition_code
                 WHERE l.status = 'ACTIVE'
                   AND l.deleted_at IS NULL
                   AND sp.status = 'VERIFIED'
                   AND NOT sp.vacation_mode
                 GROUP BY l.catalog_variant_id, l.condition_code
                ON CONFLICT (catalog_variant_id, condition_code, stat_date) DO NOTHING
                """, statDate, computedAt, soldSince);
    }

    public Optional<MarketStat> find(MarketKey key, LocalDate statDate) {
        return dsl.selectFrom(VARIANT_MARKET_STAT)
                .where(VARIANT_MARKET_STAT.CATALOG_VARIANT_ID.eq(key.catalogVariantId()))
                .and(VARIANT_MARKET_STAT.CONDITION_CODE.eq(key.condition().name()))
                .and(VARIANT_MARKET_STAT.STAT_DATE.eq(statDate))
                .fetchOptional()
                .map(VariantMarketStatRepository::toStat);
    }

    /** The most recent day there was a market at all. */
    public Optional<MarketStat> latest(MarketKey key) {
        return dsl.selectFrom(VARIANT_MARKET_STAT)
                .where(VARIANT_MARKET_STAT.CATALOG_VARIANT_ID.eq(key.catalogVariantId()))
                .and(VARIANT_MARKET_STAT.CONDITION_CODE.eq(key.condition().name()))
                .orderBy(VARIANT_MARKET_STAT.STAT_DATE.desc())
                .limit(1)
                .fetchOptional()
                .map(VariantMarketStatRepository::toStat);
    }

    private static MarketStat toStat(VariantMarketStatRecord r) {
        return new MarketStat(
                r.getCatalogVariantId(),
                CardCondition.valueOf(r.getConditionCode()),
                r.getStatDate(),
                r.getMedianPrice(),
                r.getAvgPrice(),
                r.getMinPrice(),
                r.getMaxPrice(),
                r.get(VARIANT_MARKET_STAT.P25_PRICE),
                r.get(VARIANT_MARKET_STAT.P75_PRICE),
                r.getActiveListingCount(),
                r.get(VARIANT_MARKET_STAT.SOLD_QUANTITY_30D),
                r.getSampleSize(),
                r.getComputedAt());
    }
}
