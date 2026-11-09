package com.pegasus.pegasustcgapi.repository.seller;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import com.pegasus.pegasustcgapi.repository.metrics.AdminMetricsRepository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingImage.LISTING_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerReview.SELLER_REVIEW;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/**
 * The aggregates behind one seller's dashboard. Every number is summed in SQL and
 * every query is pinned to one {@code seller_profile.id}; nothing pulls rows into
 * Java to add them up.
 *
 * <p>What counts as sold is {@link AdminMetricsRepository#SOLD_STATUSES}, paid for
 * within the range by {@code sales_order.paid_at}, so a shop's numbers add up to
 * the platform's. Ranges are half-open, {@code [from, to)}.
 */
@Repository
public class SellerDashboardRepository {

    private final DSLContext dsl;

    public SellerDashboardRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long liveAuctionCount(long sellerProfileId, OffsetDateTime now) {
        return dsl.fetchCount(AUCTION, AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(AUCTION.STATUS.eq("LIVE")).and(AUCTION.ENDS_AT.gt(now)));
    }

    public long awaitingAuctionPaymentCount(long sellerProfileId) {
        return dsl.fetchCount(AUCTION, AUCTION.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(AUCTION.STATUS.eq("AWAITING_PAYMENT")));
    }

    // ---------- sales ----------

    /**
     * @return items (the sales figure, shipping left out), grand total, commission
     *         and how many sub-orders, all zero when nothing sold
     */
    public SalesTotals salesBetween(long sellerProfileId, OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(
                        sum(SELLER_ORDER.ITEMS_SUBTOTAL),
                        sum(SELLER_ORDER.GRAND_TOTAL),
                        sum(SELLER_ORDER.COMMISSION_AMOUNT),
                        DSL.count())
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(sold(sellerProfileId, from, to))
                .fetchSingle(r -> new SalesTotals(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    /** The cards in those same sub-orders, at the average cost frozen on each line at checkout. */
    public BigDecimal costBetween(long sellerProfileId, OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(sum(ORDER_ITEM.UNIT_COST_SNAPSHOT.mul(ORDER_ITEM.QUANTITY)))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(sold(sellerProfileId, from, to))
                .fetchSingle()
                .value1();
    }

    /** Items sold per month, keyed by the first day of the month in {@code zone}. */
    public Map<LocalDate, BigDecimal> monthlySales(
            long sellerProfileId, OffsetDateTime from, OffsetDateTime to, ZoneId zone) {
        Field<LocalDateTime> month = DSL.field("date_trunc('month', {0} AT TIME ZONE {1})",
                SQLDataType.LOCALDATETIME, SALES_ORDER.PAID_AT, DSL.inline(zone.getId()));
        return dsl.select(month, sum(SELLER_ORDER.ITEMS_SUBTOTAL))
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(sold(sellerProfileId, from, to))
                .groupBy(month)
                .fetchMap(r -> r.value1().toLocalDate(), r -> r.value2());
    }

    // ---------- orders ----------

    /** Every status the shop has sub-orders in; one GROUP BY. */
    public Map<String, Long> orderCountsByStatus(long sellerProfileId) {
        Field<Integer> total = DSL.count();
        return dsl.select(SELLER_ORDER.STATUS, total)
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .groupBy(SELLER_ORDER.STATUS)
                .fetchMap(SELLER_ORDER.STATUS, r -> r.get(total).longValue());
    }

    /** Newest first, with the first line's name and the card count as scalar subqueries. */
    public List<RecentOrderRow> recentOrders(long sellerProfileId, int limit) {
        Field<String> firstItem = DSL.field(
                DSL.select(ORDER_ITEM.PRODUCT_NAME_SNAPSHOT)
                        .from(ORDER_ITEM)
                        .where(ORDER_ITEM.SELLER_ORDER_ID.eq(SELLER_ORDER.ID))
                        .orderBy(ORDER_ITEM.ID.asc())
                        .limit(1))
                .as("first_item");
        Field<String> firstImageKey = DSL.field(
                DSL.select(ORDER_ITEM.IMAGE_KEY_SNAPSHOT)
                        .from(ORDER_ITEM)
                        .where(ORDER_ITEM.SELLER_ORDER_ID.eq(SELLER_ORDER.ID))
                        .orderBy(ORDER_ITEM.ID.asc())
                        .limit(1))
                .as("first_image_key");
        Field<BigDecimal> itemCount = DSL.field(
                DSL.select(DSL.coalesce(DSL.sum(ORDER_ITEM.QUANTITY), BigDecimal.ZERO))
                        .from(ORDER_ITEM)
                        .where(ORDER_ITEM.SELLER_ORDER_ID.eq(SELLER_ORDER.ID)))
                .as("item_count");

        return dsl.select(SELLER_ORDER.ID, SELLER_ORDER.SELLER_ORDER_NUMBER, USER_ACCOUNT.USERNAME,
                        USER_ACCOUNT.DISPLAY_NAME, firstItem, firstImageKey, itemCount, SELLER_ORDER.GRAND_TOTAL,
                        SELLER_ORDER.STATUS, SELLER_ORDER.CREATED_AT)
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SALES_ORDER.BUYER_ID))
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .orderBy(SELLER_ORDER.CREATED_AT.desc(), SELLER_ORDER.ID.desc())
                .limit(limit)
                .fetch(r -> new RecentOrderRow(r.value1(), r.value2(), r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7().intValue(), r.value8(), r.value9(), r.value10()));
    }

    // ---------- listings ----------

    /** Right now: ACTIVE and not deleted. */
    public long activeListings(long sellerProfileId) {
        return dsl.fetchCount(LISTING, live(sellerProfileId));
    }

    public long listingsPublishedBetween(long sellerProfileId, OffsetDateTime from, OffsetDateTime to) {
        return dsl.fetchCount(LISTING, LISTING.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.PUBLISHED_AT.ge(from))
                .and(LISTING.PUBLISHED_AT.lt(to)));
    }

    public long lowStockCount(long sellerProfileId, int threshold) {
        return dsl.fetchCount(LISTING, lowStock(sellerProfileId, threshold));
    }

    /** Fewest left first, so the most urgent restock is at the top. */
    public List<LowStockRow> lowStockListings(long sellerProfileId, int threshold, int limit) {
        Field<String> listingImageKey = DSL.field(DSL.select(LISTING_IMAGE.IMAGE_KEY)
                .from(LISTING_IMAGE)
                .where(LISTING_IMAGE.LISTING_ID.eq(LISTING.ID))
                .orderBy(LISTING_IMAGE.IS_PRIMARY.desc(), LISTING_IMAGE.SORT_ORDER.asc(), LISTING_IMAGE.ID.asc())
                .limit(1));
        Field<String> catalogImageKey = DSL.field(DSL.select(CATALOG_IMAGE.IMAGE_KEY)
                .from(CATALOG_IMAGE)
                .where(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(CATALOG_PRODUCT.ID))
                .orderBy(CATALOG_IMAGE.IS_PRIMARY.desc(), CATALOG_IMAGE.SORT_ORDER.asc(), CATALOG_IMAGE.ID.asc())
                .limit(1));
        Field<String> imageKey = DSL.coalesce(listingImageKey, catalogImageKey).as("image_key");
        return dsl.select(LISTING.ID, CATALOG_PRODUCT.NAME, LISTING.LOT_LABEL, LISTING.CONDITION_CODE,
                        LISTING.QUANTITY_AVAILABLE, LISTING.QUANTITY_TOTAL, imageKey)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(lowStock(sellerProfileId, threshold))
                .orderBy(LISTING.QUANTITY_AVAILABLE.asc(), LISTING.ID.asc())
                .limit(limit)
                .fetch(r -> new LowStockRow(r.value1(), r.value2(), r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7()));
    }

    // ---------- reviews ----------

    /** Visible reviews only: one an admin hid does not count for or against the shop. */
    public ReviewTotals reviews(long sellerProfileId, OffsetDateTime from, OffsetDateTime to) {
        Condition visible = SELLER_REVIEW.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(SELLER_REVIEW.IS_VISIBLE.isTrue());
        return dsl.select(
                        DSL.avg(SELLER_REVIEW.RATING),
                        DSL.count(),
                        DSL.count().filterWhere(SELLER_REVIEW.CREATED_AT.ge(from)
                                .and(SELLER_REVIEW.CREATED_AT.lt(to))),
                        DSL.count().filterWhere(SELLER_REVIEW.REPLY_COMMENT.isNull()))
                .from(SELLER_REVIEW)
                .where(visible)
                .fetchSingle(r -> new ReviewTotals(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    // ---------- escrow ----------

    /**
     * What the shop's paid orders will pay it once escrow lets go: each hold still
     * HELD, less what was refunded from it and the commission frozen at checkout.
     *
     * <p>The money sits in the PLATFORM wallet until release, so the seller's own
     * wallet has nothing pending — it only ever receives the net, straight into
     * AVAILABLE. This is that net ahead of time. While a return is open the release
     * may take a smaller commission than the frozen one; the figure is then a
     * slight underestimate, never more than the shop will get.
     */
    public BigDecimal heldInEscrow(long sellerProfileId) {
        Field<BigDecimal> net = ESCROW_HOLD.AMOUNT
                .minus(ESCROW_HOLD.REFUNDED_AMOUNT)
                .minus(SELLER_ORDER.COMMISSION_AMOUNT);
        return dsl.select(sum(DSL.greatest(net, DSL.inline(BigDecimal.ZERO))))
                .from(ESCROW_HOLD)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ESCROW_HOLD.SELLER_ORDER_ID))
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(ESCROW_HOLD.STATUS.eq("HELD"))
                .fetchSingle()
                .value1();
    }

    // ---------- helpers ----------

    private static Condition sold(long sellerProfileId, OffsetDateTime from, OffsetDateTime to) {
        return SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(SELLER_ORDER.STATUS.in(AdminMetricsRepository.SOLD_STATUSES))
                .and(SALES_ORDER.PAID_AT.ge(from))
                .and(SALES_ORDER.PAID_AT.lt(to));
    }

    private static Condition live(long sellerProfileId) {
        return LISTING.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(LISTING.STATUS.eq("ACTIVE"))
                .and(LISTING.DELETED_AT.isNull());
    }

    /**
     * Low stock is ACTIVE with 1 to {@code threshold} cards left to sell. At 0 the
     * database moves the listing to SOLD_OUT on its own, which the products page
     * shows separately, so it is not counted twice here.
     */
    private static Condition lowStock(long sellerProfileId, int threshold) {
        return live(sellerProfileId)
                .and(LISTING.QUANTITY_AVAILABLE.between(1, threshold));
    }

    /** Zero rather than null when nothing matched, so callers never special-case an empty month. */
    private static Field<BigDecimal> sum(Field<BigDecimal> column) {
        return DSL.coalesce(DSL.sum(column), BigDecimal.ZERO);
    }

    /** @param items the sales figure; {@code grandTotal} adds the shipping buyers paid */
    public record SalesTotals(BigDecimal items, BigDecimal grandTotal, BigDecimal commission, int orders) {
    }

    public record RecentOrderRow(
            long sellerOrderId,
            String sellerOrderNumber,
            String buyerUsername,
            String buyerDisplayName,
            String firstItemName,
            String firstImageKey,
            int itemCount,
            BigDecimal grandTotal,
            String status,
            OffsetDateTime createdAt) {
    }

    public record LowStockRow(
            long listingId,
            String productName,
            String lotLabel,
            String conditionCode,
            int quantityAvailable,
            int quantityTotal,
            String imageKey) {
    }

    /** @param average null before the first review */
    public record ReviewTotals(BigDecimal average, int count, int thisMonth, int unreplied) {
    }
}
