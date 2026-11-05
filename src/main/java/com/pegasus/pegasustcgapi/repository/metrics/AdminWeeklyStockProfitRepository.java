package com.pegasus.pegasustcgapi.repository.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** SQL aggregates for the admin weekly stock and profit report. */
@Repository
public class AdminWeeklyStockProfitRepository {

    private final DSLContext dsl;

    public AdminWeeklyStockProfitRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Totals completedTotals(OffsetDateTime from, OffsetDateTime to, String game, String type) {
        Record r = dsl.fetchOne("""
                WITH completed_lines AS (
                    SELECT so.id AS seller_order_id, so.commission_amount,
                           oi.line_total, oi.unit_cost_snapshot * oi.quantity AS cost,
                           cp.product_type, g.slug AS game,
                           SUM(oi.line_total) OVER (PARTITION BY so.id) AS order_gmv
                      FROM seller_order so
                      JOIN order_item oi ON oi.seller_order_id = so.id
                      JOIN catalog_variant cv ON cv.id = oi.catalog_variant_id
                      JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                      JOIN game g ON g.id = cp.game_id
                     WHERE so.status = 'COMPLETED' AND so.completed_at >= CAST(? AS timestamptz) AND so.completed_at < CAST(? AS timestamptz)
                )
                SELECT COALESCE(SUM(line_total), 0) AS gmv,
                       COALESCE(SUM(commission_amount * line_total / NULLIF(order_gmv, 0)), 0) AS commission,
                       COALESCE(SUM(line_total - cost - commission_amount * line_total / NULLIF(order_gmv, 0)), 0) AS profit
                  FROM completed_lines
                 WHERE (CAST(? AS varchar) IS NULL OR replace(lower(game), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
                   AND (CAST(? AS varchar) IS NULL
                        OR (CAST(? AS varchar) = 'SINGLE_CARD' AND product_type = 'SINGLE_CARD')
                        OR (CAST(? AS varchar) = 'SEALED' AND product_type IN
                            ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')))
                """, from, to, game, game, type, type, type);
        return new Totals(r.get("gmv", BigDecimal.class), r.get("commission", BigDecimal.class),
                r.get("profit", BigDecimal.class));
    }

    public List<DailyAmounts> dailyAmounts(OffsetDateTime from, OffsetDateTime to, String game, String type) {
        return dsl.fetch("""
                SELECT (so.completed_at AT TIME ZONE 'Asia/Bangkok')::date AS day,
                       COALESCE(SUM(oi.line_total) FILTER (WHERE cp.product_type = 'SINGLE_CARD'), 0) AS singles,
                       COALESCE(SUM(oi.line_total) FILTER (WHERE cp.product_type IN
                           ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')), 0) AS sealed
                  FROM seller_order so
                  JOIN order_item oi ON oi.seller_order_id = so.id
                  JOIN catalog_variant cv ON cv.id = oi.catalog_variant_id
                  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                  JOIN game g ON g.id = cp.game_id
                 WHERE so.status = 'COMPLETED' AND so.completed_at >= CAST(? AS timestamptz) AND so.completed_at < CAST(? AS timestamptz)
                   AND (CAST(? AS varchar) IS NULL OR replace(lower(g.slug), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
                   AND (CAST(? AS varchar) IS NULL OR (CAST(? AS varchar) = 'SINGLE_CARD' AND cp.product_type = 'SINGLE_CARD')
                        OR (CAST(? AS varchar) = 'SEALED' AND cp.product_type IN
                            ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')))
                 GROUP BY day ORDER BY day
                """, from, to, game, game, type, type, type)
                .map(r -> new DailyAmounts(r.get("day", LocalDate.class),
                        r.get("singles", BigDecimal.class), r.get("sealed", BigDecimal.class)));
    }

    /** Available units on ACTIVE listings at the report cutoff. */
    public long activeStock(OffsetDateTime asOf, String game, String type) {
        Record r = dsl.fetchOne("""
                WITH latest AS (
                    SELECT DISTINCT ON (listing_id) listing_id, status, quantity_available, deleted_at
                      FROM listing_stock_history
                     WHERE changed_at < CAST(? AS timestamptz)
                     ORDER BY listing_id, changed_at DESC, id DESC
                )
                SELECT COALESCE(SUM(h.quantity_available), 0) AS stock
                  FROM latest h
                  JOIN listing l ON l.id = h.listing_id
                  JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
                  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                  JOIN game g ON g.id = cp.game_id
                 WHERE h.status = 'ACTIVE' AND h.deleted_at IS NULL
                   AND (CAST(? AS varchar) IS NULL OR replace(lower(g.slug), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
                   AND (CAST(? AS varchar) IS NULL OR (CAST(? AS varchar) = 'SINGLE_CARD' AND cp.product_type = 'SINGLE_CARD')
                        OR (CAST(? AS varchar) = 'SEALED' AND cp.product_type IN
                            ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')))
                """, asOf, game, game, type, type, type);
        return r.get("stock", Long.class);
    }

    /** Units sold from completed orders in the selected week. */
    public long completedQuantity(OffsetDateTime from, OffsetDateTime to, String game, String type) {
        Record r = dsl.fetchOne("""
                SELECT COALESCE(SUM(oi.quantity), 0) AS quantity
                  FROM seller_order so
                  JOIN order_item oi ON oi.seller_order_id = so.id
                  JOIN catalog_variant cv ON cv.id = oi.catalog_variant_id
                  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                  JOIN game g ON g.id = cp.game_id
                 WHERE so.status = 'COMPLETED' AND so.completed_at >= CAST(? AS timestamptz) AND so.completed_at < CAST(? AS timestamptz)
                   AND (CAST(? AS varchar) IS NULL OR replace(lower(g.slug), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
                   AND (CAST(? AS varchar) IS NULL OR (CAST(? AS varchar) = 'SINGLE_CARD' AND cp.product_type = 'SINGLE_CARD')
                        OR (CAST(? AS varchar) = 'SEALED' AND cp.product_type IN
                            ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')))
                """, from, to, game, game, type, type, type);
        return r.get("quantity", Long.class);
    }

    public List<GameProfit> profitByGame(OffsetDateTime from, OffsetDateTime to, String game, String type) {
        return dsl.fetch("""
                WITH completed AS (
                    SELECT id, commission_amount FROM seller_order
                     WHERE status = 'COMPLETED' AND completed_at >= CAST(? AS timestamptz) AND completed_at < CAST(? AS timestamptz)
                ), all_lines AS (
                    SELECT c.id, SUM(oi.line_total) AS order_gmv
                      FROM completed c JOIN order_item oi ON oi.seller_order_id = c.id GROUP BY c.id
                ), game_lines AS (
                    SELECT c.id, c.commission_amount, g.slug, g.name, oi.line_total,
                           oi.unit_cost_snapshot * oi.quantity AS cost
                      FROM completed c JOIN order_item oi ON oi.seller_order_id = c.id
                      JOIN catalog_variant cv ON cv.id = oi.catalog_variant_id
                      JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                      JOIN game g ON g.id = cp.game_id
                     WHERE (CAST(? AS varchar) IS NULL OR replace(lower(g.slug), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
                       AND (CAST(? AS varchar) IS NULL OR (CAST(? AS varchar) = 'SINGLE_CARD' AND cp.product_type = 'SINGLE_CARD')
                            OR (CAST(? AS varchar) = 'SEALED' AND cp.product_type IN
                                ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')))
                ), profits AS (
                    SELECT gl.slug, gl.name, SUM(gl.line_total - gl.cost) AS gross_profit,
                           SUM(gl.commission_amount * gl.line_total / NULLIF(al.order_gmv, 0)) AS commission
                      FROM game_lines gl JOIN all_lines al ON al.id = gl.id
                     GROUP BY gl.slug, gl.name
                )
                SELECT p.slug, p.name, p.gross_profit - p.commission AS profit
                  FROM profits p
                 ORDER BY p.name
                """, from, to, game, game, type, type, type)
                .map(r -> new GameProfit(r.get("slug", String.class), r.get("name", String.class),
                        r.get("profit", BigDecimal.class)));
    }

    public List<GameStock> activeStockByGame(OffsetDateTime asOf, String game, String type) {
        return dsl.fetch("""
                WITH latest AS (
                    SELECT DISTINCT ON (listing_id) listing_id, status, quantity_available, deleted_at
                      FROM listing_stock_history
                     WHERE changed_at < CAST(? AS timestamptz)
                     ORDER BY listing_id, changed_at DESC, id DESC
                )
                SELECT g.slug, g.name, COALESCE(SUM(h.quantity_available), 0) AS stock
                  FROM latest h
                  JOIN listing l ON l.id = h.listing_id
                  JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
                  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                  JOIN game g ON g.id = cp.game_id
                 WHERE h.status = 'ACTIVE' AND h.deleted_at IS NULL
                   AND (CAST(? AS varchar) IS NULL OR replace(lower(g.slug), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
                   AND (CAST(? AS varchar) IS NULL OR (CAST(? AS varchar) = 'SINGLE_CARD' AND cp.product_type = 'SINGLE_CARD')
                        OR (CAST(? AS varchar) = 'SEALED' AND cp.product_type IN
                            ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE')))
                 GROUP BY g.slug, g.name ORDER BY g.name
                """, asOf, game, game, type, type, type)
                .map(r -> new GameStock(r.get("slug", String.class), r.get("name", String.class),
                        r.get("stock", Long.class)));
    }

    public record Totals(BigDecimal gmv, BigDecimal commission, BigDecimal netProfit) {}
    public record DailyAmounts(LocalDate date, BigDecimal singles, BigDecimal sealed) {}
    public record GameProfit(String slug, String name, BigDecimal profit) {}
    public record GameStock(String slug, String name, long stock) {}
}
