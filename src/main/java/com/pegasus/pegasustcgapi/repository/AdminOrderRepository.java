package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerReview.SELLER_REVIEW;
import static com.pegasus.pegasustcgapi.jooq.tables.Shipment.SHIPMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.UserAccount;
import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Sub-orders across every seller, for the admin back office.
 *
 * <p>One row is one {@code seller_order}: that is the unit a seller packs and ships,
 * and the unit the admin board shows. The per-row extras — card count, tracking
 * number, return reason and review — are scalar subqueries, so a page is still one
 * statement however many sellers are on it.
 *
 * <p>The seller's own order desk reads through here too, always with
 * {@link AdminOrderQuery#sellerProfileId()} pinned to the caller's shop.
 */
@Repository
public class AdminOrderRepository {

    private static final UserAccount SELLER_ACCOUNT = USER_ACCOUNT.as("seller_account");
    private static final UserAccount BUYER = USER_ACCOUNT.as("buyer");

    private static final Field<BigDecimal> ITEM_COUNT = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(ORDER_ITEM.QUANTITY), BigDecimal.ZERO))
                    .from(ORDER_ITEM)
                    .where(ORDER_ITEM.SELLER_ORDER_ID.eq(SELLER_ORDER.ID)))
            .as("item_count");

    private static final Field<String> CARRIER_NAME = DSL.field(
            DSL.select(SHIPMENT.CARRIER_NAME)
                    .from(SHIPMENT)
                    .where(SHIPMENT.SELLER_ORDER_ID.eq(SELLER_ORDER.ID))
                    .orderBy(SHIPMENT.CREATED_AT.desc(), SHIPMENT.ID.desc())
                    .limit(1))
            .as("carrier_name");

    private static final Field<String> TRACKING_NUMBER = DSL.field(
            DSL.select(SHIPMENT.TRACKING_NUMBER)
                    .from(SHIPMENT)
                    .where(SHIPMENT.SELLER_ORDER_ID.eq(SELLER_ORDER.ID))
                    .orderBy(SHIPMENT.CREATED_AT.desc(), SHIPMENT.ID.desc())
                    .limit(1))
            .as("tracking_number");

    /** The newest request is the one an open dispute is about. */
    private static final Field<String> RETURN_REASON = DSL.field(
            DSL.select(RETURN_REQUEST.REASON_CODE)
                    .from(RETURN_REQUEST)
                    .where(RETURN_REQUEST.SELLER_ORDER_ID.eq(SELLER_ORDER.ID))
                    .orderBy(RETURN_REQUEST.REQUESTED_AT.desc(), RETURN_REQUEST.ID.desc())
                    .limit(1))
            .as("return_reason");

    /** Hidden reviews still count here: the admin is the one who hid them. */
    private static final Field<Short> REVIEW_RATING = DSL.field(
            DSL.select(SELLER_REVIEW.RATING)
                    .from(SELLER_REVIEW)
                    .where(SELLER_REVIEW.SELLER_ORDER_ID.eq(SELLER_ORDER.ID)))
            .as("review_rating");

    private static final List<Field<?>> ROW_FIELDS = List.of(
            SELLER_ORDER.ID, SELLER_ORDER.SELLER_ORDER_NUMBER, SELLER_ORDER.SALES_ORDER_ID,
            SALES_ORDER.ORDER_NUMBER, SELLER_ORDER.STATUS, SELLER_ORDER.GRAND_TOTAL,
            SELLER_ORDER.SELLER_PROFILE_ID, SELLER_ACCOUNT.USERNAME, SELLER_ACCOUNT.DISPLAY_NAME,
            SALES_ORDER.BUYER_ID, BUYER.USERNAME, BUYER.DISPLAY_NAME, BUYER.AVATAR_URL,
            SELLER_ORDER.CREATED_AT, SALES_ORDER.PAID_AT, SELLER_ORDER.SHIPPED_AT,
            SELLER_ORDER.COMPLETED_AT, SELLER_ORDER.CANCELLED_AT,
            ITEM_COUNT, CARRIER_NAME, TRACKING_NUMBER, RETURN_REASON, REVIEW_RATING);

    private final DSLContext dsl;

    public AdminOrderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<AdminOrderRow> findPage(AdminOrderQuery query) {
        return select()
                .where(conditions(query))
                .orderBy(order(query.sort()))
                .limit(query.limit())
                .offset(query.offset())
                .fetch(AdminOrderRepository::toRow);
    }

    public long count(AdminOrderQuery query) {
        return dsl.fetchCount(joined(dsl.selectOne()).where(conditions(query)));
    }

    public Optional<AdminOrderRow> findById(long sellerOrderId) {
        return select()
                .where(SELLER_ORDER.ID.eq(sellerOrderId))
                .fetchOptional(AdminOrderRepository::toRow);
    }

    /** Every status that has at least one sub-order; one GROUP BY for the whole board. */
    public Map<String, Long> countByStatus() {
        return countByStatus(null);
    }

    /** @param sellerProfileId one shop's sub-orders only, or null for every shop */
    public Map<String, Long> countByStatus(Long sellerProfileId) {
        Field<Integer> total = DSL.count();
        return dsl.select(SELLER_ORDER.STATUS, total)
                .from(SELLER_ORDER)
                .where(sellerProfileId == null
                        ? DSL.noCondition()
                        : SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .groupBy(SELLER_ORDER.STATUS)
                .fetchMap(SELLER_ORDER.STATUS, r -> r.get(total).longValue());
    }

    /**
     * Unpaid sub-orders older than the payment deadline. The timeout sweep should
     * keep this at zero, so anything else means the sweep has stalled.
     */
    public long countUnpaidSince(OffsetDateTime cutoff) {
        return dsl.fetchCount(SELLER_ORDER,
                SELLER_ORDER.STATUS.eq("PENDING_PAYMENT")
                        .and(SELLER_ORDER.CREATED_AT.lt(cutoff)));
    }

    public Optional<OrderContext> findOrderContext(long salesOrderId) {
        return dsl.select(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, SALES_ORDER.BUYER_NOTE)
                .from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(salesOrderId))
                .fetchOptional(r -> new OrderContext(
                        r.value1() == null ? null : r.value1().data(),
                        r.value2()));
    }

    /** {@link #findOrderContext} for a whole page of parent orders in one query. */
    public Map<Long, OrderContext> findOrderContexts(Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SALES_ORDER.ID, SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, SALES_ORDER.BUYER_NOTE)
                .from(SALES_ORDER)
                .where(SALES_ORDER.ID.in(salesOrderIds))
                .fetchMap(SALES_ORDER.ID, r -> new OrderContext(
                        r.value2() == null ? null : r.value2().data(),
                        r.value3()));
    }

    /** The newest attempt, whatever happened to it, so a failed slip is visible too. */
    public Optional<PaymentRecord> findLatestPayment(long salesOrderId) {
        return dsl.selectFrom(PAYMENT)
                .where(PAYMENT.SALES_ORDER_ID.eq(salesOrderId))
                .orderBy(PAYMENT.ID.desc())
                .limit(1)
                .fetchOptional();
    }

    private SelectJoinStep<Record> select() {
        return joined(dsl.select(ROW_FIELDS));
    }

    private static <R extends Record> SelectJoinStep<R> joined(SelectSelectStep<R> select) {
        return select.from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(SELLER_ORDER.SELLER_PROFILE_ID))
                .join(SELLER_ACCOUNT).on(SELLER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .join(BUYER).on(BUYER.ID.eq(SALES_ORDER.BUYER_ID));
    }

    private static Condition conditions(AdminOrderQuery query) {
        Condition condition = DSL.noCondition();

        if (query.statuses() != null && !query.statuses().isEmpty()) {
            condition = condition.and(SELLER_ORDER.STATUS.in(query.statuses()));
        }
        if (query.sellerProfileId() != null) {
            condition = condition.and(SELLER_ORDER.SELLER_PROFILE_ID.eq(query.sellerProfileId()));
        }
        if (query.buyerId() != null) {
            condition = condition.and(SALES_ORDER.BUYER_ID.eq(query.buyerId()));
        }
        if (query.from() != null) {
            condition = condition.and(SELLER_ORDER.CREATED_AT.ge(query.from()));
        }
        if (query.to() != null) {
            condition = condition.and(SELLER_ORDER.CREATED_AT.lt(query.to()));
        }
        if (query.search() != null) {
            String pattern = containsPattern(query.search());
            condition = condition.and(ilike(SALES_ORDER.ORDER_NUMBER, pattern)
                    .or(ilike(SELLER_ORDER.SELLER_ORDER_NUMBER, pattern))
                    .or(ilike(BUYER.USERNAME, pattern))
                    .or(ilike(BUYER.DISPLAY_NAME, pattern))
                    .or(ilike(SELLER_ACCOUNT.USERNAME, pattern))
                    .or(ilike(SELLER_ACCOUNT.DISPLAY_NAME, pattern)));
        }
        return condition;
    }

    /** Every order ends on the id, so rows that tie keep their place from one page to the next. */
    private static List<SortField<?>> order(AdminOrderQuery.Sort sort) {
        return switch (sort) {
            case NEWEST -> List.of(SELLER_ORDER.CREATED_AT.desc(), SELLER_ORDER.ID.desc());
            case OLDEST -> List.of(SELLER_ORDER.CREATED_AT.asc(), SELLER_ORDER.ID.asc());
            case PAID_OLDEST -> List.of(SALES_ORDER.PAID_AT.asc().nullsLast(), SELLER_ORDER.ID.asc());
            case SHIPPED_NEWEST -> List.of(SELLER_ORDER.SHIPPED_AT.desc().nullsLast(), SELLER_ORDER.ID.desc());
            case COMPLETED_NEWEST -> List.of(SELLER_ORDER.COMPLETED_AT.desc().nullsLast(), SELLER_ORDER.ID.desc());
            case TOTAL_DESC -> List.of(SELLER_ORDER.GRAND_TOTAL.desc(), SELLER_ORDER.ID.desc());
        };
    }

    /** "%text%" with the LIKE wildcards in the text taken literally. */
    private static String containsPattern(String text) {
        return "%" + text.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    /** A real {@code ILIKE}, as in {@link ListingRepository}. */
    private static Condition ilike(Field<String> column, String pattern) {
        return DSL.condition("{0} ILIKE {1} ESCAPE '\\'", column, DSL.val(pattern));
    }

    private static AdminOrderRow toRow(Record r) {
        return new AdminOrderRow(
                r.get(SELLER_ORDER.ID),
                r.get(SELLER_ORDER.SELLER_ORDER_NUMBER),
                r.get(SELLER_ORDER.SALES_ORDER_ID),
                r.get(SALES_ORDER.ORDER_NUMBER),
                r.get(SELLER_ORDER.STATUS),
                r.get(SELLER_ORDER.GRAND_TOTAL),
                r.get(ITEM_COUNT).intValue(),
                r.get(SELLER_ORDER.SELLER_PROFILE_ID),
                r.get(SELLER_ACCOUNT.USERNAME),
                r.get(SELLER_ACCOUNT.DISPLAY_NAME),
                r.get(SALES_ORDER.BUYER_ID),
                r.get(BUYER.USERNAME),
                r.get(BUYER.DISPLAY_NAME),
                r.get(BUYER.AVATAR_URL),
                r.get(SELLER_ORDER.CREATED_AT),
                r.get(SALES_ORDER.PAID_AT),
                r.get(SELLER_ORDER.SHIPPED_AT),
                r.get(SELLER_ORDER.COMPLETED_AT),
                r.get(SELLER_ORDER.CANCELLED_AT),
                r.get(CARRIER_NAME),
                r.get(TRACKING_NUMBER),
                r.get(RETURN_REASON),
                r.get(REVIEW_RATING));
    }

    /**
     * @param statuses empty for every status
     * @param search   matched, case-insensitively and anywhere in the text, against both
     *                 order numbers and the buyer's and seller's username and display name
     * @param from     inclusive, on when the sub-order was placed
     * @param to       exclusive
     */
    public record AdminOrderQuery(
            Collection<String> statuses,
            Long sellerProfileId,
            Long buyerId,
            OffsetDateTime from,
            OffsetDateTime to,
            String search,
            Sort sort,
            int limit,
            int offset) {

        public enum Sort {
            NEWEST,
            OLDEST,
            /** Longest-waiting paid order first: what is late to ship. */
            PAID_OLDEST,
            SHIPPED_NEWEST,
            COMPLETED_NEWEST,
            TOTAL_DESC
        }
    }

    public record AdminOrderRow(
            long sellerOrderId,
            String sellerOrderNumber,
            long salesOrderId,
            String orderNumber,
            String status,
            BigDecimal grandTotal,
            int itemCount,
            long sellerProfileId,
            String sellerUsername,
            String sellerDisplayName,
            long buyerId,
            String buyerUsername,
            String buyerDisplayName,
            String buyerAvatarKey,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt,
            OffsetDateTime shippedAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt,
            String carrierName,
            String trackingNumber,
            String returnReasonCode,
            Short reviewRating) {
    }

    /** The parts of the parent order the seller's own view leaves out. */
    public record OrderContext(String shippingAddressSnapshot, String buyerNote) {
    }
}
