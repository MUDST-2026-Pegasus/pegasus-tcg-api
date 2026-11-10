package com.pegasus.pegasustcgapi.repository.order;

import static com.pegasus.pegasustcgapi.jooq.tables.CollectionItem.COLLECTION_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnItem.RETURN_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static com.pegasus.pegasustcgapi.jooq.tables.Shipment.SHIPMENT;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ShipmentRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Writes that move an existing order along: status changes, their history, shipments, and
 * handing the bought cards to the buyer's collection.
 */
@Repository
public class OrderTransitionRepository {

    private final DSLContext dsl;
    private final Clock clock;

    @Autowired
    public OrderTransitionRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    public OrderTransitionRepository(DSLContext dsl) {
        this(dsl, Clock.systemUTC());
    }

    /**
     * PAID to PREPARING, stamping when the seller took it on. The status guard is in
     * the UPDATE itself, so two tabs accepting at once leave one history row, not two.
     *
     * @return rows changed: 0 when the order was no longer PAID
     */
    public int markSellerOrderAccepted(long sellerOrderId, OffsetDateTime acceptedAt) {
        return dsl.update(SELLER_ORDER)
                .set(SELLER_ORDER.STATUS, "PREPARING")
                .set(SELLER_ORDER.ACCEPTED_AT, acceptedAt)
                .set(SELLER_ORDER.UPDATED_AT, OffsetDateTime.now(clock))
                .where(SELLER_ORDER.ID.eq(sellerOrderId))
                .and(SELLER_ORDER.STATUS.eq("PAID"))
                .execute();
    }

    public ShipmentRecord insertShipment(
            long sellerOrderId,
            String carrierCode,
            String carrierName,
            String trackingNumber,
            String status,
            OffsetDateTime shippedAt,
            LocalDate estimatedDeliveryDate,
            String proofImageKey,
            long createdBy) {

        return dsl.insertInto(SHIPMENT)
                .set(SHIPMENT.SELLER_ORDER_ID, sellerOrderId)
                .set(SHIPMENT.CARRIER_CODE, carrierCode)
                .set(SHIPMENT.CARRIER_NAME, carrierName)
                .set(SHIPMENT.TRACKING_NUMBER, trackingNumber)
                .set(SHIPMENT.STATUS, status != null ? status : "IN_TRANSIT")
                .set(SHIPMENT.SHIPPED_AT, shippedAt)
                .set(SHIPMENT.ESTIMATED_DELIVERY_DATE, estimatedDeliveryDate)
                .set(SHIPMENT.PROOF_IMAGE_KEY, proofImageKey)
                .set(SHIPMENT.CREATED_BY, createdBy)
                .returning()
                .fetchSingle();
    }

    public SellerOrderStatusHistoryRecord insertStatusHistory(
            long sellerOrderId,
            String fromStatus,
            String toStatus,
            Long changedBy,
            String note) {

        return dsl.insertInto(SELLER_ORDER_STATUS_HISTORY)
                .set(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID, sellerOrderId)
                .set(SELLER_ORDER_STATUS_HISTORY.FROM_STATUS, fromStatus)
                .set(SELLER_ORDER_STATUS_HISTORY.TO_STATUS, toStatus)
                .set(SELLER_ORDER_STATUS_HISTORY.CHANGED_BY, changedBy)
                .set(SELLER_ORDER_STATUS_HISTORY.NOTE, note)
                .returning()
                .fetchSingle();
    }

    public int updateSellerOrderStatus(
            long sellerOrderId,
            Collection<String> allowedFromStatuses,
            String newStatus,
            OffsetDateTime shippedAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime autoCompleteAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt,
            String cancelReason) {

        var step = dsl.update(SELLER_ORDER)
                .set(SELLER_ORDER.STATUS, newStatus)
                .set(SELLER_ORDER.UPDATED_AT, OffsetDateTime.now(clock));

        if (shippedAt != null) {
            step = step.set(SELLER_ORDER.SHIPPED_AT, shippedAt);
        }
        if (deliveredAt != null) {
            step = step.set(SELLER_ORDER.DELIVERED_AT, deliveredAt);
        }
        if (autoCompleteAt != null) {
            step = step.set(SELLER_ORDER.AUTO_COMPLETE_AT, autoCompleteAt);
        }
        if (completedAt != null) {
            step = step.set(SELLER_ORDER.COMPLETED_AT, completedAt);
        }
        if (cancelledAt != null) {
            step = step.set(SELLER_ORDER.CANCELLED_AT, cancelledAt);
        }
        if (cancelReason != null) {
            step = step.set(SELLER_ORDER.CANCEL_REASON, cancelReason);
        }

        return step.where(SELLER_ORDER.ID.eq(sellerOrderId))
                .and(SELLER_ORDER.STATUS.in(allowedFromStatuses))
                .execute();
    }

    public void updateSalesOrderStatus(
            long salesOrderId,
            String newStatus,
            OffsetDateTime paidAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt) {

        var step = dsl.update(SALES_ORDER)
                .set(SALES_ORDER.STATUS, newStatus)
                .set(SALES_ORDER.UPDATED_AT, OffsetDateTime.now(clock));

        if (paidAt != null) {
            step = step.set(SALES_ORDER.PAID_AT, paidAt);
        }
        if (completedAt != null) {
            step = step.set(SALES_ORDER.COMPLETED_AT, completedAt);
        }
        if (cancelledAt != null) {
            step = step.set(SALES_ORDER.CANCELLED_AT, cancelledAt);
        }

        step.where(SALES_ORDER.ID.eq(salesOrderId)).execute();
    }

    public int grantPurchasedUnitsToCollection(long buyerUserId, long sellerOrderId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // A received return can cover only some physical units of an order item.
        // Use the same lowest-unit-id choice as restockReturn, including when the
        // seller elected not to restock the returned unit.
        List<Long> returnedUnits = new ArrayList<>();
        dsl.select(RETURN_ITEM.ORDER_ITEM_ID, RETURN_ITEM.QUANTITY)
                .from(RETURN_ITEM)
                .join(RETURN_REQUEST).on(RETURN_REQUEST.ID.eq(RETURN_ITEM.RETURN_REQUEST_ID))
                .join(REFUND).on(REFUND.RETURN_REQUEST_ID.eq(RETURN_REQUEST.ID))
                .where(RETURN_REQUEST.SELLER_ORDER_ID.eq(sellerOrderId))
                .and(RETURN_REQUEST.RECEIVED_AT.isNotNull())
                .and(REFUND.STATUS.eq("PROCESSED"))
                .fetch()
                .forEach(item -> returnedUnits.addAll(dsl.select(ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                        .from(ORDER_ITEM_UNIT)
                        .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(item.value1()))
                        .orderBy(ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc())
                        .limit(item.value2())
                        .fetch(ORDER_ITEM_UNIT.LISTING_UNIT_ID)));
        return dsl.insertInto(COLLECTION_ITEM,
                        COLLECTION_ITEM.USER_ID,
                        COLLECTION_ITEM.CATALOG_VARIANT_ID,
                        COLLECTION_ITEM.CONDITION_CODE,
                        COLLECTION_ITEM.QUANTITY,
                        COLLECTION_ITEM.SOURCE,
                        COLLECTION_ITEM.SOURCE_ORDER_ITEM_ID,
                        COLLECTION_ITEM.SOURCE_LISTING_UNIT_ID,
                        COLLECTION_ITEM.ACQUIRED_PRICE,
                        COLLECTION_ITEM.ACQUIRED_AT,
                        COLLECTION_ITEM.IS_PUBLIC)
                .select(
                        dsl.select(
                                        DSL.val(buyerUserId),
                                        ORDER_ITEM.CATALOG_VARIANT_ID,
                                        DSL.coalesce(ORDER_ITEM.CONDITION_SNAPSHOT, "NM"),
                                        DSL.val(1),
                                        DSL.val("PURCHASE"),
                                        ORDER_ITEM.ID,
                                        ORDER_ITEM_UNIT.LISTING_UNIT_ID,
                                        ORDER_ITEM.UNIT_PRICE,
                                        DSL.val(now),
                                        DSL.val(false))
                                .from(ORDER_ITEM_UNIT)
                                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(ORDER_ITEM_UNIT.ORDER_ITEM_ID))
                                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId))
                                .and(returnedUnits.isEmpty() ? DSL.trueCondition()
                                        : ORDER_ITEM_UNIT.LISTING_UNIT_ID.notIn(returnedUnits))
                                .andNotExists(
                                        dsl.selectOne()
                                                .from(COLLECTION_ITEM)
                                                .where(COLLECTION_ITEM.SOURCE_ORDER_ITEM_ID.eq(ORDER_ITEM.ID))
                                                .and(COLLECTION_ITEM.SOURCE_LISTING_UNIT_ID.eq(ORDER_ITEM_UNIT.LISTING_UNIT_ID))
                                )
                )
                .execute();
    }
}
