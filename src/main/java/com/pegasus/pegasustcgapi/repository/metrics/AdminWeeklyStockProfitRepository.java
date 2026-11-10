package com.pegasus.pegasustcgapi.repository.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * SQL aggregates for the admin weekly stock and profit report.
 *
 * <p>Every product type belongs to exactly one group (SINGLE_CARD, SEALED or OTHER),
 * so the per-group figures always add up to the totals.
 */
@Repository
public class AdminWeeklyStockProfitRepository {

    private static final String PRODUCT_GROUP = """
            CASE WHEN cp.product_type = 'SINGLE_CARD' THEN 'SINGLE_CARD'
                 WHEN cp.product_type IN ('BOOSTER_PACK','BOOSTER_BOX','ELITE_TRAINER_BOX','STARTER_DECK','BUNDLE') THEN 'SEALED'
                 ELSE 'OTHER' END""";

    /** Binds game, game, group, group. */
    private static final String FILTERS = """
            (CAST(? AS varchar) IS NULL OR replace(lower(slug), '-', '') = replace(lower(CAST(? AS varchar)), '-', ''))
            AND (CAST(? AS varchar) IS NULL OR product_group = CAST(? AS varchar))""";

    /** Binds from, to. */
    private static final String COMPLETED_LINES = """
            completed_lines AS (
                SELECT so.id AS seller_order_id, so.commission_amount, so.completed_at,
                       oi.quantity, oi.line_total,
                       g.slug, g.name, %s AS product_group,
                       SUM(oi.line_total) OVER (PARTITION BY so.id) AS order_gmv
                  FROM seller_order so
                  JOIN order_item oi ON oi.seller_order_id = so.id
                  JOIN catalog_variant cv ON cv.id = oi.catalog_variant_id
                  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                  JOIN game g ON g.id = cp.game_id
                 WHERE so.status = 'COMPLETED'
                   AND so.completed_at >= CAST(? AS timestamptz)
                   AND so.completed_at < CAST(? AS timestamptz)
            )""".formatted(PRODUCT_GROUP);

    /** Binds asOf. */
    private static final String ACTIVE_STOCK = """
            active_stock AS (
                SELECT h.quantity_available, g.slug, g.name, %s AS product_group
                  FROM (SELECT DISTINCT ON (listing_id) listing_id, status, quantity_available, deleted_at
                          FROM listing_stock_history
                         WHERE changed_at < CAST(? AS timestamptz)
                         ORDER BY listing_id, changed_at DESC, id DESC) h
                  JOIN listing l ON l.id = h.listing_id
                  JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
                  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                  JOIN game g ON g.id = cp.game_id
                 WHERE h.status = 'ACTIVE' AND h.deleted_at IS NULL
            )""".formatted(PRODUCT_GROUP);

    private static final String GMV_SUM = "COALESCE(ROUND(SUM(line_total), 2), 0)";

    private static final String COMMISSION_SUM = """
            COALESCE(ROUND(SUM(CASE WHEN commission_amount > 0 THEN commission_amount * line_total / NULLIF(order_gmv, 0)
                              ELSE ROUND(line_total * 0.03, 2) END), 2), 0)""";

    /**
     * The platform's net profit is the commission it earns. The seller's unit cost is the seller's
     * money and stays out of this report; deduct only costs the platform itself pays.
     */
    private static final String PROFIT_SUM = COMMISSION_SUM;

    private final DSLContext dsl;

    public AdminWeeklyStockProfitRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Totals completedTotals(OffsetDateTime from, OffsetDateTime to, String game, String group) {
        Record r = dsl.fetchOne("WITH " + COMPLETED_LINES + " SELECT " + GMV_SUM + " AS gmv, "
                + COMMISSION_SUM + " AS commission, " + PROFIT_SUM + " AS profit"
                + " FROM completed_lines WHERE " + FILTERS,
                from, to, game, game, group, group);
        return new Totals(r.get("gmv", BigDecimal.class), r.get("commission", BigDecimal.class),
                r.get("profit", BigDecimal.class));
    }

    public List<DailyAmounts> dailyAmounts(OffsetDateTime from, OffsetDateTime to, String game, String group) {
        return dsl.fetch("WITH " + COMPLETED_LINES + """
                 SELECT (completed_at AT TIME ZONE 'Asia/Bangkok')::date AS day,
                        COALESCE(SUM(line_total) FILTER (WHERE product_group = 'SINGLE_CARD'), 0) AS singles,
                        COALESCE(SUM(line_total) FILTER (WHERE product_group = 'SEALED'), 0) AS sealed,
                        COALESCE(SUM(line_total) FILTER (WHERE product_group = 'OTHER'), 0) AS other
                   FROM completed_lines
                  WHERE """ + " " + FILTERS + " GROUP BY day ORDER BY day",
                from, to, game, game, group, group)
                .map(r -> new DailyAmounts(r.get("day", LocalDate.class),
                        r.get("singles", BigDecimal.class), r.get("sealed", BigDecimal.class),
                        r.get("other", BigDecimal.class)));
    }

    /** Completed-order money per Bangkok day; days without sales are absent. */
    public List<DailyMoney> dailyMoney(OffsetDateTime from, OffsetDateTime to, String game, String group) {
        return dsl.fetch("WITH " + COMPLETED_LINES + " SELECT (completed_at AT TIME ZONE 'Asia/Bangkok')::date AS day, "
                + GMV_SUM + " AS gmv, " + COMMISSION_SUM + " AS commission, " + PROFIT_SUM + " AS profit"
                + " FROM completed_lines WHERE " + FILTERS + " GROUP BY day ORDER BY day",
                from, to, game, game, group, group)
                .map(r -> new DailyMoney(r.get("day", LocalDate.class), r.get("gmv", BigDecimal.class),
                        r.get("commission", BigDecimal.class), r.get("profit", BigDecimal.class)));
    }

    /** Available units on ACTIVE listings at the end of each Bangkok day from start to end. */
    public List<DailyStock> dailyStock(LocalDate start, LocalDate end, String game, String group) {
        return dsl.fetch("""
                SELECT d.day, s.stock
                  FROM (SELECT generate_series(CAST(? AS date), CAST(? AS date), interval '1 day')::date AS day) d
                 CROSS JOIN LATERAL (
                    SELECT COALESCE(SUM(a.quantity_available), 0) AS stock
                      FROM (
                        SELECT h.quantity_available, g.slug, %s AS product_group
                          FROM (SELECT DISTINCT ON (listing_id) listing_id, status, quantity_available, deleted_at
                                  FROM listing_stock_history
                                 WHERE changed_at < ((d.day + 1)::timestamp AT TIME ZONE 'Asia/Bangkok')
                                 ORDER BY listing_id, changed_at DESC, id DESC) h
                          JOIN listing l ON l.id = h.listing_id
                          JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
                          JOIN catalog_product cp ON cp.id = cv.catalog_product_id
                          JOIN game g ON g.id = cp.game_id
                         WHERE h.status = 'ACTIVE' AND h.deleted_at IS NULL
                      ) a
                     WHERE %s
                 ) s
                 ORDER BY d.day
                """.formatted(PRODUCT_GROUP, FILTERS), start, end, game, game, group, group)
                .map(r -> new DailyStock(r.get("day", LocalDate.class), r.get("stock", Long.class)));
    }

    /** Available units on ACTIVE listings at the report cutoff. */
    public long activeStock(OffsetDateTime asOf, String game, String group) {
        Record r = dsl.fetchOne("WITH " + ACTIVE_STOCK
                + " SELECT COALESCE(SUM(quantity_available), 0) AS stock FROM active_stock WHERE " + FILTERS,
                asOf, game, game, group, group);
        return r.get("stock", Long.class);
    }

    /** Units sold from completed orders in the selected week. */
    public long completedQuantity(OffsetDateTime from, OffsetDateTime to, String game, String group) {
        Record r = dsl.fetchOne("WITH " + COMPLETED_LINES
                + " SELECT COALESCE(SUM(quantity), 0) AS quantity FROM completed_lines WHERE " + FILTERS,
                from, to, game, game, group, group);
        return r.get("quantity", Long.class);
    }

    public List<GameProfit> profitByGame(OffsetDateTime from, OffsetDateTime to, String game, String group) {
        return dsl.fetch("WITH " + COMPLETED_LINES + " SELECT slug, name, " + PROFIT_SUM
                + " AS profit FROM completed_lines WHERE " + FILTERS + " GROUP BY slug, name ORDER BY name",
                from, to, game, game, group, group)
                .map(r -> new GameProfit(r.get("slug", String.class), r.get("name", String.class),
                        r.get("profit", BigDecimal.class)));
    }

    public List<GameStock> activeStockByGame(OffsetDateTime asOf, String game, String group) {
        return dsl.fetch("WITH " + ACTIVE_STOCK
                + " SELECT slug, name, COALESCE(SUM(quantity_available), 0) AS stock FROM active_stock WHERE "
                + FILTERS + " GROUP BY slug, name ORDER BY name",
                asOf, game, game, group, group)
                .map(r -> new GameStock(r.get("slug", String.class), r.get("name", String.class),
                        r.get("stock", Long.class)));
    }

    public List<GameBreakdownStat> gameBreakdownRows(OffsetDateTime from, OffsetDateTime to, String game, String group) {
        return dsl.fetch("WITH " + ACTIVE_STOCK + ", " + COMPLETED_LINES + """
                , stock_by_game AS (
                    SELECT slug, name,
                           COALESCE(SUM(quantity_available), 0) AS remaining_stock,
                           COALESCE(SUM(quantity_available) FILTER (WHERE product_group = 'SINGLE_CARD'), 0) AS single_cards,
                           COALESCE(SUM(quantity_available) FILTER (WHERE product_group = 'SEALED'), 0) AS sealed_boxes,
                           COALESCE(SUM(quantity_available) FILTER (WHERE product_group = 'OTHER'), 0) AS other_items
                      FROM active_stock
                     WHERE """ + " " + FILTERS + """

                     GROUP BY slug, name
                ), sales_by_game AS (
                    SELECT slug, name, COALESCE(SUM(quantity), 0) AS sold_count,
                """ + GMV_SUM + " AS gmv, " + COMMISSION_SUM + " AS commission, " + PROFIT_SUM + """
                 AS profit
                      FROM completed_lines
                     WHERE """ + " " + FILTERS + """

                     GROUP BY slug, name
                )
                SELECT COALESCE(st.slug, sa.slug) AS slug,
                       COALESCE(st.name, sa.name) AS name,
                       COALESCE(st.remaining_stock, 0) AS remaining_stock,
                       COALESCE(st.single_cards, 0) AS single_cards,
                       COALESCE(st.sealed_boxes, 0) AS sealed_boxes,
                       COALESCE(st.other_items, 0) AS other_items,
                       COALESCE(sa.sold_count, 0) AS sold_count,
                       COALESCE(sa.gmv, 0) AS gmv,
                       COALESCE(sa.commission, 0) AS commission,
                       COALESCE(sa.profit, 0) AS profit
                  FROM stock_by_game st
                  FULL OUTER JOIN sales_by_game sa ON st.slug = sa.slug
                 ORDER BY name
                """, to, from, to, game, game, group, group, game, game, group, group)
                .map(r -> new GameBreakdownStat(
                        r.get("slug", String.class),
                        r.get("name", String.class),
                        r.get("remaining_stock", Long.class),
                        r.get("single_cards", Long.class),
                        r.get("sealed_boxes", Long.class),
                        r.get("other_items", Long.class),
                        r.get("sold_count", Long.class),
                        r.get("gmv", BigDecimal.class),
                        r.get("commission", BigDecimal.class),
                        r.get("profit", BigDecimal.class)));
    }

    /** One row per game and product group that has stock or sales. */
    public List<GameTypeBreakdownStat> gameTypeBreakdownRows(
            OffsetDateTime from, OffsetDateTime to, String game, String group) {
        return dsl.fetch("WITH " + ACTIVE_STOCK + ", " + COMPLETED_LINES + """
                , stock_rows AS (
                    SELECT slug, name, product_group, COALESCE(SUM(quantity_available), 0) AS remaining_stock
                      FROM active_stock
                     WHERE """ + " " + FILTERS + """

                     GROUP BY slug, name, product_group
                ), sales_rows AS (
                    SELECT slug, name, product_group, COALESCE(SUM(quantity), 0) AS sold_count,
                """ + GMV_SUM + " AS gmv, " + COMMISSION_SUM + " AS commission, " + PROFIT_SUM + """
                 AS profit
                      FROM completed_lines
                     WHERE """ + " " + FILTERS + """

                     GROUP BY slug, name, product_group
                )
                SELECT COALESCE(st.slug, sa.slug) AS slug,
                       COALESCE(st.name, sa.name) AS name,
                       COALESCE(st.product_group, sa.product_group) AS product_group,
                       COALESCE(st.remaining_stock, 0) AS remaining_stock,
                       COALESCE(sa.sold_count, 0) AS sold_count,
                       COALESCE(sa.gmv, 0) AS gmv,
                       COALESCE(sa.commission, 0) AS commission,
                       COALESCE(sa.profit, 0) AS profit
                  FROM stock_rows st
                  FULL OUTER JOIN sales_rows sa ON st.slug = sa.slug AND st.product_group = sa.product_group
                """, to, from, to, game, game, group, group, game, game, group, group)
                .map(r -> new GameTypeBreakdownStat(
                        r.get("slug", String.class),
                        r.get("name", String.class),
                        r.get("product_group", String.class),
                        r.get("remaining_stock", Long.class),
                        r.get("sold_count", Long.class),
                        r.get("gmv", BigDecimal.class),
                        r.get("commission", BigDecimal.class),
                        r.get("profit", BigDecimal.class)));
    }

    public record Totals(BigDecimal gmv, BigDecimal commission, BigDecimal netProfit) {}
    public record DailyAmounts(LocalDate date, BigDecimal singles, BigDecimal sealed, BigDecimal other) {}
    public record DailyMoney(LocalDate date, BigDecimal gmv, BigDecimal commission, BigDecimal netProfit) {}
    public record DailyStock(LocalDate date, long stock) {}
    public record GameProfit(String slug, String name, BigDecimal profit) {}
    public record GameStock(String slug, String name, long stock) {}
    public record GameBreakdownStat(
            String slug,
            String name,
            long remainingStock,
            long singleCards,
            long sealedBoxes,
            long otherItems,
            long soldCount,
            BigDecimal gmv,
            BigDecimal commission,
            BigDecimal profit) {}
    public record GameTypeBreakdownStat(
            String slug,
            String name,
            String productType,
            long remainingStock,
            long soldCount,
            BigDecimal gmv,
            BigDecimal commission,
            BigDecimal profit) {}
}
