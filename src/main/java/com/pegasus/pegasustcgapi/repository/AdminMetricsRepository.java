package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/**
 * The aggregates behind the admin overview. Every number is summed in SQL; nothing
 * pulls rows into Java to add them up.
 *
 * <p>Ranges are half-open, {@code [from, to)}. Week and month buckets are cut in the
 * zone the caller passes, so an order paid at 00:30 on the 1st in Bangkok falls in
 * the new month even though it is still the previous day in UTC.
 */
@Repository
public class AdminMetricsRepository {

    /**
     * Sold and not taken back. RETURN_REQUESTED is held out because the money is in
     * dispute; CANCELLED, RETURNED and REFUNDED were never kept.
     */
    public static final List<String> SOLD_STATUSES = SellerOrderStatus.namesOf(EnumSet.of(
            SellerOrderStatus.PAID,
            SellerOrderStatus.PREPARING,
            SellerOrderStatus.SHIPPED,
            SellerOrderStatus.DELIVERED,
            SellerOrderStatus.COMPLETED));

    public static final List<String> SINGLE_TYPES = List.of("SINGLE_CARD");

    public static final List<String> SEALED_TYPES = List.of(
            "BOOSTER_PACK", "BOOSTER_BOX", "ELITE_TRAINER_BOX", "STARTER_DECK", "BUNDLE");

    private final DSLContext dsl;

    public AdminMetricsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ---------- sales ----------

    /** Items only, shipping left out, the same base commission is worked out on. */
    public BigDecimal salesBetween(OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(sum(SELLER_ORDER.ITEMS_SUBTOTAL))
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(sold(from, to))
                .fetchSingle()
                .value1();
    }

    public Map<LocalDate, BigDecimal> weeklySales(OffsetDateTime from, OffsetDateTime to, ZoneId zone) {
        Field<LocalDateTime> week = trunc("week", SALES_ORDER.PAID_AT, zone);
        return dsl.select(week, sum(SELLER_ORDER.ITEMS_SUBTOTAL))
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(sold(from, to))
                .groupBy(week)
                .fetchMap(r -> r.value1().toLocalDate(), r -> r.value2());
    }

    /** Per month and per kind, from the order lines, which know what was sold. */
    public List<MonthlyKindSales> monthlySalesByKind(OffsetDateTime from, OffsetDateTime to, ZoneId zone) {
        Field<LocalDateTime> month = trunc("month", SALES_ORDER.PAID_AT, zone);
        Field<String> kind = DSL.when(CATALOG_PRODUCT.PRODUCT_TYPE.in(SINGLE_TYPES), DSL.inline("SINGLES"))
                .when(CATALOG_PRODUCT.PRODUCT_TYPE.in(SEALED_TYPES), DSL.inline("SEALED"))
                .else_(DSL.inline("OTHER"))
                .as("kind");

        return dsl.select(month, kind, sum(ORDER_ITEM.LINE_TOTAL))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(ORDER_ITEM.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(sold(from, to))
                .groupBy(month, kind)
                .fetch(r -> new MonthlyKindSales(r.value1().toLocalDate(), r.value2(), r.value3()));
    }

    /** Grouped by the game id and named from the game table, so a renamed game stays one slice. */
    public List<GameSales> salesByGame(OffsetDateTime from, OffsetDateTime to) {
        Field<BigDecimal> amount = sum(ORDER_ITEM.LINE_TOTAL);
        return dsl.select(GAME.ID, GAME.NAME, amount)
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(ORDER_ITEM.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .join(GAME).on(GAME.ID.eq(CATALOG_PRODUCT.GAME_ID))
                .where(sold(from, to))
                .groupBy(GAME.ID, GAME.NAME)
                .orderBy(amount.desc(), GAME.ID.asc())
                .fetch(r -> new GameSales(r.value1(), r.value2(), r.value3()));
    }

    public List<SellerSales> topSellers(OffsetDateTime from, OffsetDateTime to, int limit) {
        Field<BigDecimal> amount = sum(SELLER_ORDER.ITEMS_SUBTOTAL);
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME, amount)
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(SELLER_ORDER.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(sold(from, to))
                .groupBy(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME)
                .orderBy(amount.desc(), SELLER_PROFILE.ID.asc())
                .limit(limit)
                .fetch(r -> new SellerSales(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    // ---------- promotions ----------

    /** Pegasus's cost of its own codes: the subsidies it paid shops at release [TCG-570]. */
    public BigDecimal promotionCostBetween(OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(sum(LEDGER_ENTRY.AMOUNT))
                .from(LEDGER_ENTRY)
                .where(subsidy(from, to))
                .fetchSingle(r -> r.value1());
    }

    public Map<LocalDate, BigDecimal> weeklyPromotionCost(OffsetDateTime from, OffsetDateTime to, ZoneId zone) {
        Field<LocalDateTime> week = trunc("week", LEDGER_ENTRY.CREATED_AT, zone);
        return dsl.select(week, sum(LEDGER_ENTRY.AMOUNT))
                .from(LEDGER_ENTRY)
                .where(subsidy(from, to))
                .groupBy(week)
                .fetchMap(r -> r.value1().toLocalDate(), r -> r.value2());
    }

    private static Condition subsidy(OffsetDateTime from, OffsetDateTime to) {
        return LEDGER_ENTRY.ENTRY_TYPE.eq("PROMO_SUBSIDY")
                .and(LEDGER_ENTRY.CREATED_AT.ge(from))
                .and(LEDGER_ENTRY.CREATED_AT.lt(to));
    }

    // ---------- commission ----------

    /** Commission is only earned once a sub-order completes and escrow lets go. */
    public CommissionTotals commissionBetween(OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(sum(SELLER_ORDER.COMMISSION_AMOUNT), sum(SELLER_ORDER.ITEMS_SUBTOTAL))
                .from(SELLER_ORDER)
                .where(completed(from, to))
                .fetchSingle(r -> new CommissionTotals(r.value1(), r.value2()));
    }

    public Map<LocalDate, BigDecimal> weeklyCommission(OffsetDateTime from, OffsetDateTime to, ZoneId zone) {
        Field<LocalDateTime> week = trunc("week", SELLER_ORDER.COMPLETED_AT, zone);
        return dsl.select(week, sum(SELLER_ORDER.COMMISSION_AMOUNT))
                .from(SELLER_ORDER)
                .where(completed(from, to))
                .groupBy(week)
                .fetchMap(r -> r.value1().toLocalDate(), r -> r.value2());
    }

    // ---------- sellers and listings ----------

    public long sellersVerifiedBetween(OffsetDateTime from, OffsetDateTime to) {
        return dsl.fetchCount(SELLER_PROFILE, verified(from, to));
    }

    public Map<LocalDate, Long> weeklyVerifiedSellers(OffsetDateTime from, OffsetDateTime to, ZoneId zone) {
        Field<LocalDateTime> week = trunc("week", SELLER_PROFILE.VERIFIED_AT, zone);
        Field<Integer> total = DSL.count();
        return dsl.select(week, total)
                .from(SELLER_PROFILE)
                .where(verified(from, to))
                .groupBy(week)
                .fetchMap(r -> r.value1().toLocalDate(), r -> r.value2().longValue());
    }

    /** Right now: there is no history of listing status to look back on. */
    public ActiveListings activeListings() {
        return dsl.select(DSL.count(), DSL.countDistinct(LISTING.SELLER_PROFILE_ID))
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .where(LISTING.STATUS.eq("ACTIVE"))
                .and(LISTING.DELETED_AT.isNull())
                .and(SELLER_PROFILE.STATUS.ne("SUSPENDED"))
                .fetchSingle(r -> new ActiveListings(r.value1(), r.value2()));
    }

    // ---------- helpers ----------

    private static Condition sold(OffsetDateTime from, OffsetDateTime to) {
        return SELLER_ORDER.STATUS.in(SOLD_STATUSES)
                .and(SALES_ORDER.PAID_AT.ge(from))
                .and(SALES_ORDER.PAID_AT.lt(to));
    }

    private static Condition completed(OffsetDateTime from, OffsetDateTime to) {
        return SELLER_ORDER.STATUS.eq(SellerOrderStatus.COMPLETED.name())
                .and(SELLER_ORDER.COMPLETED_AT.ge(from))
                .and(SELLER_ORDER.COMPLETED_AT.lt(to));
    }

    private static Condition verified(OffsetDateTime from, OffsetDateTime to) {
        return SELLER_PROFILE.STATUS.eq("VERIFIED")
                .and(SELLER_PROFILE.VERIFIED_AT.ge(from))
                .and(SELLER_PROFILE.VERIFIED_AT.lt(to));
    }

    /** Zero rather than null when nothing matched, so callers never special-case an empty month. */
    private static Field<BigDecimal> sum(Field<BigDecimal> column) {
        return DSL.coalesce(DSL.sum(column), BigDecimal.ZERO);
    }

    /** Postgres weeks start on Monday, which is how a Thai calendar reads them too. */
    private static Field<LocalDateTime> trunc(String unit, Field<OffsetDateTime> column, ZoneId zone) {
        return DSL.field("date_trunc({0}, {1} AT TIME ZONE {2})", SQLDataType.LOCALDATETIME,
                DSL.inline(unit), column, DSL.inline(zone.getId()));
    }

    /** @param kind SINGLES, SEALED or OTHER */
    public record MonthlyKindSales(LocalDate month, String kind, BigDecimal amount) {
    }

    public record GameSales(short gameId, String gameName, BigDecimal amount) {
    }

    public record SellerSales(long sellerProfileId, String username, String displayName, BigDecimal amount) {
    }

    public record CommissionTotals(BigDecimal commission, BigDecimal itemsSubtotal) {
    }

    public record ActiveListings(long listings, long sellers) {
    }
}
