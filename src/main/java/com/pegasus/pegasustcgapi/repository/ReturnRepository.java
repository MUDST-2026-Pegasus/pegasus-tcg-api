package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnItem.RETURN_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ReturnItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ReturnRequestRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class ReturnRepository {

    private final DSLContext dsl;

    public ReturnRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<ReturnRequestRecord> lock(long id) {
        return dsl.selectFrom(RETURN_REQUEST).where(RETURN_REQUEST.ID.eq(id)).forUpdate().fetchOptional();
    }

    public Optional<ReturnRequestRecord> find(long id) {
        return dsl.selectFrom(RETURN_REQUEST).where(RETURN_REQUEST.ID.eq(id)).fetchOptional();
    }

    public List<ReturnRequestRecord> forOrder(long sellerOrderId) {
        return dsl.selectFrom(RETURN_REQUEST)
                .where(RETURN_REQUEST.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(RETURN_REQUEST.ID.desc()).fetch();
    }

    public List<ReturnRequestRecord> forSeller(long sellerProfileId, String status) {
        var query = dsl.selectFrom(RETURN_REQUEST)
                .where(RETURN_REQUEST.SELLER_ORDER_ID.in(
                        dsl.select(com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER.ID)
                                .from(com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER)
                                .where(com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))));
        return (status == null ? query : query.and(RETURN_REQUEST.STATUS.eq(status)))
                .orderBy(RETURN_REQUEST.REQUESTED_AT.asc(), RETURN_REQUEST.ID.asc()).fetch();
    }

    public List<SellerReturnRow> findSellerReturns(long sellerProfileId, String status) {
        var condition = SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId);
        if (status != null && !status.isBlank()) {
            condition = condition.and(RETURN_REQUEST.STATUS.eq(status));
        }

        return dsl.select(
                        RETURN_REQUEST.ID,
                        RETURN_REQUEST.SELLER_ORDER_ID,
                        SELLER_ORDER.SELLER_ORDER_NUMBER,
                        USER_ACCOUNT.USERNAME,
                        USER_ACCOUNT.DISPLAY_NAME,
                        SELLER_ORDER.GRAND_TOTAL,
                        SELLER_ORDER.DELIVERED_AT,
                        SELLER_ORDER.AUTO_COMPLETE_AT,
                        RETURN_REQUEST.REASON_CODE,
                        RETURN_REQUEST.DESCRIPTION,
                        RETURN_REQUEST.STATUS,
                        RETURN_REQUEST.RESOLUTION,
                        RETURN_REQUEST.REFUND_AMOUNT,
                        RETURN_REQUEST.EVIDENCE_KEYS,
                        RETURN_REQUEST.RETURN_TRACKING_NUMBER,
                        RETURN_REQUEST.REQUESTED_AT,
                        RETURN_REQUEST.REVIEWED_AT,
                        RETURN_REQUEST.RECEIVED_AT,
                        RETURN_REQUEST.CLOSED_AT)
                .from(RETURN_REQUEST)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(RETURN_REQUEST.SELLER_ORDER_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(RETURN_REQUEST.BUYER_ID))
                .where(condition)
                .orderBy(RETURN_REQUEST.REQUESTED_AT.asc(), RETURN_REQUEST.ID.asc())
                .fetch(r -> new SellerReturnRow(
                        r.get(RETURN_REQUEST.ID),
                        r.get(RETURN_REQUEST.SELLER_ORDER_ID),
                        r.get(SELLER_ORDER.SELLER_ORDER_NUMBER),
                        r.get(USER_ACCOUNT.USERNAME),
                        r.get(USER_ACCOUNT.DISPLAY_NAME),
                        r.get(SELLER_ORDER.GRAND_TOTAL),
                        r.get(SELLER_ORDER.DELIVERED_AT),
                        r.get(SELLER_ORDER.AUTO_COMPLETE_AT),
                        r.get(RETURN_REQUEST.REASON_CODE),
                        r.get(RETURN_REQUEST.DESCRIPTION),
                        r.get(RETURN_REQUEST.STATUS),
                        r.get(RETURN_REQUEST.RESOLUTION),
                        r.get(RETURN_REQUEST.REFUND_AMOUNT),
                        r.get(RETURN_REQUEST.EVIDENCE_KEYS),
                        r.get(RETURN_REQUEST.RETURN_TRACKING_NUMBER),
                        r.get(RETURN_REQUEST.REQUESTED_AT),
                        r.get(RETURN_REQUEST.REVIEWED_AT),
                        r.get(RETURN_REQUEST.RECEIVED_AT),
                        r.get(RETURN_REQUEST.CLOSED_AT)
                ));
    }

    public List<SellerReturnItemRow> findReturnItemsBatch(List<Long> returnRequestIds) {
        if (returnRequestIds == null || returnRequestIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(
                        RETURN_ITEM.ID,
                        RETURN_ITEM.RETURN_REQUEST_ID,
                        RETURN_ITEM.ORDER_ITEM_ID,
                        ORDER_ITEM.PRODUCT_NAME_SNAPSHOT,
                        ORDER_ITEM.VARIANT_LABEL_SNAPSHOT,
                        ORDER_ITEM.CONDITION_SNAPSHOT,
                        ORDER_ITEM.IMAGE_KEY_SNAPSHOT,
                        ORDER_ITEM.UNIT_PRICE,
                        RETURN_ITEM.QUANTITY,
                        ORDER_ITEM.QUANTITY,
                        RETURN_ITEM.RESTOCK)
                .from(RETURN_ITEM)
                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(RETURN_ITEM.ORDER_ITEM_ID))
                .where(RETURN_ITEM.RETURN_REQUEST_ID.in(returnRequestIds))
                .orderBy(RETURN_ITEM.ID.asc())
                .fetch(r -> new SellerReturnItemRow(
                        r.get(RETURN_ITEM.ID),
                        r.get(RETURN_ITEM.RETURN_REQUEST_ID),
                        r.get(RETURN_ITEM.ORDER_ITEM_ID),
                        r.get(ORDER_ITEM.PRODUCT_NAME_SNAPSHOT),
                        r.get(ORDER_ITEM.VARIANT_LABEL_SNAPSHOT),
                        r.get(ORDER_ITEM.CONDITION_SNAPSHOT),
                        r.get(ORDER_ITEM.IMAGE_KEY_SNAPSHOT),
                        r.get(ORDER_ITEM.UNIT_PRICE),
                        r.get(RETURN_ITEM.QUANTITY),
                        r.get(ORDER_ITEM.QUANTITY),
                        Boolean.TRUE.equals(r.get(RETURN_ITEM.RESTOCK))
                ));
    }

    public record SellerReturnRow(
            long id,
            long sellerOrderId,
            String sellerOrderNumber,
            String buyerUsername,
            String buyerDisplayName,
            BigDecimal sellerOrderGrandTotal,
            OffsetDateTime deliveredAt,
            OffsetDateTime autoCompleteAt,
            String reasonCode,
            String description,
            String status,
            String resolution,
            BigDecimal refundAmount,
            JSONB evidenceKeys,
            String returnTrackingNumber,
            OffsetDateTime requestedAt,
            OffsetDateTime reviewedAt,
            OffsetDateTime receivedAt,
            OffsetDateTime closedAt
    ) {}

    public record SellerReturnItemRow(
            long returnItemId,
            long returnRequestId,
            long orderItemId,
            String cardName,
            String variantLabel,
            String conditionCode,
            String imageKey,
            BigDecimal unitPrice,
            int quantityReturned,
            int quantityOrdered,
            boolean restock
    ) {}

    public Optional<OrderItemRecord> orderItem(long id, long sellerOrderId) {
        return dsl.selectFrom(ORDER_ITEM).where(ORDER_ITEM.ID.eq(id))
                .and(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId)).fetchOptional();
    }

    public ReturnRequestRecord create(long sellerOrderId, long buyerId, String reason,
            String description, String evidenceJson) {
        return dsl.insertInto(RETURN_REQUEST)
                .set(RETURN_REQUEST.SELLER_ORDER_ID, sellerOrderId)
                .set(RETURN_REQUEST.BUYER_ID, buyerId)
                .set(RETURN_REQUEST.REASON_CODE, reason)
                .set(RETURN_REQUEST.DESCRIPTION, description)
                .set(RETURN_REQUEST.EVIDENCE_KEYS, JSONB.valueOf(evidenceJson))
                .returning().fetchSingle();
    }

    public void addItem(long returnId, long orderItemId, int quantity) {
        dsl.insertInto(RETURN_ITEM).set(RETURN_ITEM.RETURN_REQUEST_ID, returnId)
                .set(RETURN_ITEM.ORDER_ITEM_ID, orderItemId)
                .set(RETURN_ITEM.QUANTITY, quantity).execute();
    }

    public List<ReturnItemRecord> items(long returnId) {
        return dsl.selectFrom(RETURN_ITEM).where(RETURN_ITEM.RETURN_REQUEST_ID.eq(returnId))
                .orderBy(RETURN_ITEM.ID.asc()).fetch();
    }

    public void setRestock(long returnItemId, boolean restock) {
        dsl.update(RETURN_ITEM).set(RETURN_ITEM.RESTOCK, restock)
                .where(RETURN_ITEM.ID.eq(returnItemId)).execute();
    }

    public List<Long> soldUnits(long orderItemId, int quantity) {
        return dsl.select(ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                .from(ORDER_ITEM_UNIT)
                .join(LISTING_UNIT).on(LISTING_UNIT.ID.eq(ORDER_ITEM_UNIT.LISTING_UNIT_ID))
                .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(orderItemId))
                .and(LISTING_UNIT.STATUS.eq("SOLD"))
                .orderBy(ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc()).limit(quantity)
                .fetch(ORDER_ITEM_UNIT.LISTING_UNIT_ID);
    }

    public void approve(long id, String resolution, BigDecimal amount, long sellerUserId,
            OffsetDateTime now, String next) {
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, next)
                .set(RETURN_REQUEST.RESOLUTION, resolution)
                .set(RETURN_REQUEST.REFUND_AMOUNT, amount)
                .set(RETURN_REQUEST.REVIEWED_BY, sellerUserId)
                .set(RETURN_REQUEST.REVIEWED_AT, now)
                .where(RETURN_REQUEST.ID.eq(id)).execute();
    }

    public void reject(long id, long sellerUserId, OffsetDateTime now) {
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, "REJECTED")
                .set(RETURN_REQUEST.RESOLUTION, "REJECTED")
                .set(RETURN_REQUEST.REVIEWED_BY, sellerUserId)
                .set(RETURN_REQUEST.REVIEWED_AT, now).set(RETURN_REQUEST.CLOSED_AT, now)
                .where(RETURN_REQUEST.ID.eq(id)).execute();
    }

    public void ship(long id, String tracking) {
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, "IN_TRANSIT")
                .set(RETURN_REQUEST.RETURN_TRACKING_NUMBER, tracking)
                .where(RETURN_REQUEST.ID.eq(id)).execute();
    }

    public void receive(long id, OffsetDateTime now) {
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, "RECEIVED")
                .set(RETURN_REQUEST.RECEIVED_AT, now)
                .where(RETURN_REQUEST.ID.eq(id)).execute();
    }

    public void markRefunded(long id) {
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, "REFUNDED")
                .where(RETURN_REQUEST.ID.eq(id)).execute();
    }

    public void close(long id, OffsetDateTime now) {
        dsl.update(RETURN_REQUEST).set(RETURN_REQUEST.STATUS, "CLOSED")
                .set(RETURN_REQUEST.CLOSED_AT, now)
                .where(RETURN_REQUEST.ID.eq(id)).execute();
    }

    public BigDecimal returnedItemValue(long sellerOrderId) {
        return dsl.select(DSL.coalesce(DSL.sum(ORDER_ITEM.UNIT_PRICE.mul(RETURN_ITEM.QUANTITY)), BigDecimal.ZERO))
                .from(RETURN_ITEM)
                .join(RETURN_REQUEST).on(RETURN_REQUEST.ID.eq(RETURN_ITEM.RETURN_REQUEST_ID))
                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(RETURN_ITEM.ORDER_ITEM_ID))
                .join(REFUND).on(REFUND.RETURN_REQUEST_ID.eq(RETURN_REQUEST.ID))
                .where(RETURN_REQUEST.SELLER_ORDER_ID.eq(sellerOrderId))
                .and(RETURN_REQUEST.RECEIVED_AT.isNotNull())
                .and(REFUND.STATUS.eq("PROCESSED"))
                .fetchSingle(0, BigDecimal.class);
    }
}
