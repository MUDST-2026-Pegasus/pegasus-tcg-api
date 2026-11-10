package com.pegasus.pegasustcgapi.repository.order;

import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static com.pegasus.pegasustcgapi.jooq.tables.Shipment.SHIPMENT;

import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ShipmentRecord;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderViews.ShippingChoice;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only lists, counts and batch reads over orders, their items, units, shipments and history.
 *
 * <p>The {@code ...ByIds} finders exist so a list of orders costs a fixed number of
 * queries instead of one per row: the caller passes every id it already holds and
 * assembles the result in memory.
 */
@Repository
public class OrderQueryRepository {

    private final DSLContext dsl;

    public OrderQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<String> findCurrencyBySalesOrderId(long id) {
        return dsl.select(SALES_ORDER.CURRENCY)
                .from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .fetchOptional(SALES_ORDER.CURRENCY);
    }

    public boolean hasPaymentUnderReview(long salesOrderId) {
        return dsl.fetchExists(dsl.selectOne().from(PAYMENT)
                .where(PAYMENT.SALES_ORDER_ID.eq(salesOrderId))
                .and(PAYMENT.STATUS.eq("AWAITING_VERIFICATION")));
    }

    /** The orders among {@code salesOrderIds} that have a slip waiting for review, in one query. */
    public Set<Long> findSalesOrderIdsWithPaymentUnderReview(Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) {
            return Set.of();
        }
        return dsl.selectDistinct(PAYMENT.SALES_ORDER_ID).from(PAYMENT)
                .where(PAYMENT.SALES_ORDER_ID.in(salesOrderIds))
                .and(PAYMENT.STATUS.eq("AWAITING_VERIFICATION"))
                .fetchSet(PAYMENT.SALES_ORDER_ID);
    }

    public List<SellerOrderRecord> findSellerOrdersBySalesOrderId(long salesOrderId) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId))
                .orderBy(SELLER_ORDER.ID.asc())
                .fetch();
    }

    /** Every sub-order of a page of sales orders, keyed by parent. */
    public Map<Long, List<SellerOrderRecord>> findSellerOrdersBySalesOrderIds(Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) {
            return Map.of();
        }
        return groupBy(dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.in(salesOrderIds))
                .orderBy(SELLER_ORDER.ID.asc())
                .fetch(), SellerOrderRecord::getSalesOrderId);
    }

    public List<OrderItemRecord> findOrderItemsBySellerOrderId(long sellerOrderId) {
        return dsl.selectFrom(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(ORDER_ITEM.ID.asc())
                .fetch();
    }

    public Map<Long, List<OrderItemRecord>> findOrderItemsBySellerOrderIds(Collection<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return groupBy(dsl.selectFrom(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.in(sellerOrderIds))
                .orderBy(ORDER_ITEM.ID.asc())
                .fetch(), OrderItemRecord::getSellerOrderId);
    }

    public List<Long> findUnitIdsByOrderItemId(long orderItemId) {
        return dsl.select(ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                .from(ORDER_ITEM_UNIT)
                .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(orderItemId))
                .orderBy(ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc())
                .fetch(ORDER_ITEM_UNIT.LISTING_UNIT_ID);
    }

    public Map<Long, List<Long>> findUnitIdsByOrderItemIds(Collection<Long> orderItemIds) {
        if (orderItemIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(ORDER_ITEM_UNIT.ORDER_ITEM_ID, ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                .from(ORDER_ITEM_UNIT)
                .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.in(orderItemIds))
                .orderBy(ORDER_ITEM_UNIT.ORDER_ITEM_ID.asc(), ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc())
                .fetchGroups(ORDER_ITEM_UNIT.ORDER_ITEM_ID, ORDER_ITEM_UNIT.LISTING_UNIT_ID);
    }

    /** One query for a whole sub-order's cards, keyed by the order item they belong to. */
    public Map<Long, List<Long>> findUnitIdsGroupedBySellerOrderId(long sellerOrderId) {
        return dsl.select(ORDER_ITEM_UNIT.ORDER_ITEM_ID, ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                .from(ORDER_ITEM_UNIT)
                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(ORDER_ITEM_UNIT.ORDER_ITEM_ID))
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(ORDER_ITEM_UNIT.ORDER_ITEM_ID.asc(), ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc())
                .fetchGroups(ORDER_ITEM_UNIT.ORDER_ITEM_ID, ORDER_ITEM_UNIT.LISTING_UNIT_ID);
    }

    public List<SalesOrderRecord> findSalesOrdersByBuyerId(long buyerId, int limit, int offset) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.BUYER_ID.eq(buyerId))
                .orderBy(SALES_ORDER.PLACED_AT.desc(), SALES_ORDER.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch();
    }

    public long countSalesOrdersByBuyerId(long buyerId) {
        return dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyerId));
    }

    /** @param status null for every sub-order, or one status to narrow to */
    public List<SellerOrderRecord> findSellerOrdersBySellerProfileId(
            long sellerProfileId, String status, int limit, int offset) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(status == null ? DSL.noCondition() : SELLER_ORDER.STATUS.eq(status))
                .orderBy(SELLER_ORDER.CREATED_AT.desc(), SELLER_ORDER.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch();
    }

    public long countSellerOrdersBySellerProfileId(long sellerProfileId, String status) {
        return dsl.fetchCount(SELLER_ORDER, SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(status == null ? DSL.noCondition() : SELLER_ORDER.STATUS.eq(status)));
    }

    /** In no particular order; callers that care key the result by id. */
    public List<SellerOrderRecord> findSellerOrdersByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.in(ids))
                .fetch();
    }

    /**
     * What the cards cost the seller, from the weighted average frozen on each line
     * at checkout, so restocking later never changes an old order's profit.
     */
    public Map<Long, BigDecimal> findCostTotalsBySellerOrderIds(Collection<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        Field<BigDecimal> cost = DSL.coalesce(
                DSL.sum(ORDER_ITEM.UNIT_COST_SNAPSHOT.mul(ORDER_ITEM.QUANTITY)), BigDecimal.ZERO);
        return dsl.select(ORDER_ITEM.SELLER_ORDER_ID, cost)
                .from(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.in(sellerOrderIds))
                .groupBy(ORDER_ITEM.SELLER_ORDER_ID)
                .fetchMap(ORDER_ITEM.SELLER_ORDER_ID, r -> r.get(cost));
    }

    /**
     * The delivery choice frozen on each order at checkout [TCG-503]. Orders with no
     * snapshot (placed before it existed and not backfillable) are left out.
     */
    public Map<Long, ShippingChoice> findShippingChoicesBySellerOrderIds(Collection<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SELLER_ORDER.ID, SELLER_ORDER.SHIPPING_OPTION_ID, SELLER_ORDER.SHIPPING_OPTION_NAME,
                        SELLER_ORDER.SHIPPING_CARRIER_CODE, SELLER_ORDER.SHIPPING_FEE,
                        SELLER_ORDER.SHIPPING_EST_DAYS_MIN, SELLER_ORDER.SHIPPING_EST_DAYS_MAX)
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.in(sellerOrderIds))
                .and(SELLER_ORDER.SHIPPING_OPTION_NAME.isNotNull())
                .fetchMap(SELLER_ORDER.ID, r -> new ShippingChoice(r.value2(), r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7()));
    }

    /** Orders this buyer paid for at this shop, the one being looked at included. */
    public long countPaidOrdersOfBuyerAtSeller(long sellerProfileId, long buyerId) {
        return dsl.fetchCount(dsl.selectOne()
                .from(SELLER_ORDER)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(SALES_ORDER.BUYER_ID.eq(buyerId))
                .and(SALES_ORDER.PAID_AT.isNotNull()));
    }

    public List<Long> findUnitIdsBySellerOrderId(long sellerOrderId) {
        return dsl.select(ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                .from(ORDER_ITEM_UNIT)
                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(ORDER_ITEM_UNIT.ORDER_ITEM_ID))
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc())
                .fetch(ORDER_ITEM_UNIT.LISTING_UNIT_ID);
    }

    public List<ShipmentRecord> findShipmentsBySellerOrderId(long sellerOrderId) {
        return dsl.selectFrom(SHIPMENT)
                .where(SHIPMENT.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(SHIPMENT.CREATED_AT.desc())
                .fetch();
    }

    public Map<Long, List<ShipmentRecord>> findShipmentsBySellerOrderIds(Collection<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return groupBy(dsl.selectFrom(SHIPMENT)
                .where(SHIPMENT.SELLER_ORDER_ID.in(sellerOrderIds))
                .orderBy(SHIPMENT.CREATED_AT.desc())
                .fetch(), ShipmentRecord::getSellerOrderId);
    }

    public List<SellerOrderStatusHistoryRecord> findStatusHistoryBySellerOrderId(long sellerOrderId) {
        return dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.CREATED_AT.asc(), SELLER_ORDER_STATUS_HISTORY.ID.asc())
                .fetch();
    }

    public Map<Long, List<SellerOrderStatusHistoryRecord>> findStatusHistoryBySellerOrderIds(
            Collection<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return groupBy(dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.in(sellerOrderIds))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.CREATED_AT.asc(), SELLER_ORDER_STATUS_HISTORY.ID.asc())
                .fetch(), SellerOrderStatusHistoryRecord::getSellerOrderId);
    }

    /**
     * A batch of sub-orders whose escrow window has run out. Capped so a scheduler
     * that missed a day works through the backlog in rounds instead of pulling it
     * all into one heap.
     */
    public List<SellerOrderRecord> findOverdueShippedOrders(OffsetDateTime now, int limit) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.STATUS.in("SHIPPED", "DELIVERED"))
                .and(SELLER_ORDER.AUTO_COMPLETE_AT.isNotNull())
                .and(SELLER_ORDER.AUTO_COMPLETE_AT.le(now))
                .orderBy(SELLER_ORDER.ID.asc())
                .limit(limit)
                .fetch();
    }

    /** {@code fetchGroups} hands back jOOQ's own Result type, so the rows are regrouped here. */
    private static <R> Map<Long, List<R>> groupBy(List<R> rows, Function<R, Long> key) {
        Map<Long, List<R>> grouped = new LinkedHashMap<>();
        for (R row : rows) {
            grouped.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * Sub-orders still waiting for a payment that was due before {@code cutoff}.
     *
     * <p>Every one of these is holding cards RESERVED, which is why they cannot be
     * left alone: the seller cannot delist a listing while an order still claims it.
     */
    public List<SellerOrderRecord> findExpiredPendingPaymentOrders(OffsetDateTime cutoff, int limit) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.STATUS.eq("PENDING_PAYMENT"))
                .and(SELLER_ORDER.SALES_ORDER_ID.in(
                        dsl.select(SALES_ORDER.ID)
                                .from(SALES_ORDER)
                                .where(SALES_ORDER.STATUS.eq("PENDING_PAYMENT"))
                                .and(SALES_ORDER.SOURCE.eq("CART"))
                                .and(SALES_ORDER.PLACED_AT.lt(cutoff))
                                .andNotExists(dsl.selectOne().from(PAYMENT)
                                        .where(PAYMENT.SALES_ORDER_ID.eq(SALES_ORDER.ID))
                                        .and(PAYMENT.STATUS.in("AWAITING_VERIFICATION", "SUCCEEDED")))))
                .orderBy(SELLER_ORDER.ID.asc())
                .limit(limit)
                .fetch();
    }
}
