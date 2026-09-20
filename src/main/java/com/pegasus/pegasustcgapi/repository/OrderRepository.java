package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.CollectionItem.COLLECTION_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static com.pegasus.pegasustcgapi.jooq.tables.Shipment.SHIPMENT;

import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ShipmentRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Persistence repository for sales orders, seller orders, and order items.
 */
@Repository
public class OrderRepository {

    private final DSLContext dsl;

    public OrderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record ListingSnapshotDetails(
            long listingId,
            long catalogVariantId,
            String condition,
            String productName,
            String variantLabel,
            String gameName,
            String imageKey) {
    }

    public Optional<SalesOrderRecord> findSalesOrderById(long id) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .fetchOptional();
    }

    public Optional<SalesOrderRecord> findSalesOrderByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional();
    }

    public List<SellerOrderRecord> findSellerOrdersBySalesOrderId(long salesOrderId) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(salesOrderId))
                .orderBy(SELLER_ORDER.ID.asc())
                .fetch();
    }

    public List<OrderItemRecord> findOrderItemsBySellerOrderId(long sellerOrderId) {
        return dsl.selectFrom(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(ORDER_ITEM.ID.asc())
                .fetch();
    }

    public List<Long> findUnitIdsByOrderItemId(long orderItemId) {
        return dsl.select(ORDER_ITEM_UNIT.LISTING_UNIT_ID)
                .from(ORDER_ITEM_UNIT)
                .where(ORDER_ITEM_UNIT.ORDER_ITEM_ID.eq(orderItemId))
                .orderBy(ORDER_ITEM_UNIT.LISTING_UNIT_ID.asc())
                .fetch(ORDER_ITEM_UNIT.LISTING_UNIT_ID);
    }

    public Map<Long, ListingSnapshotDetails> findListingDetails(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(
                LISTING.ID,
                LISTING.CATALOG_VARIANT_ID,
                LISTING.CONDITION_CODE,
                CATALOG_PRODUCT.NAME,
                CATALOG_VARIANT.LANGUAGE_CODE,
                CATALOG_VARIANT.FINISH,
                CATALOG_VARIANT.EDITION,
                CATALOG_VARIANT.PRINTING_NOTE,
                GAME.NAME,
                CATALOG_IMAGE.IMAGE_KEY)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .leftJoin(GAME).on(GAME.ID.eq(CATALOG_PRODUCT.GAME_ID))
                .leftJoin(CATALOG_IMAGE).on(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(CATALOG_PRODUCT.ID)
                        .and(CATALOG_IMAGE.IS_PRIMARY.isTrue()))
                .where(LISTING.ID.in(listingIds))
                .fetchMap(LISTING.ID, r -> {
                    String languageCode = r.get(CATALOG_VARIANT.LANGUAGE_CODE);
                    String finish = r.get(CATALOG_VARIANT.FINISH);
                    String edition = r.get(CATALOG_VARIANT.EDITION);
                    String printingNote = r.get(CATALOG_VARIANT.PRINTING_NOTE);
                    StringBuilder label = new StringBuilder(languageCode != null ? languageCode : "EN");
                    if (finish != null && !"NOT_APPLICABLE".equals(finish)) {
                        label.append(" / ").append(finish);
                    }
                    if (edition != null && !"NOT_APPLICABLE".equals(edition)) {
                        label.append(" / ").append(edition);
                    }
                    if (printingNote != null && !printingNote.isBlank()) {
                        label.append(" / ").append(printingNote);
                    }
                    return new ListingSnapshotDetails(
                            r.get(LISTING.ID),
                            r.get(LISTING.CATALOG_VARIANT_ID),
                            r.get(LISTING.CONDITION_CODE),
                            r.get(CATALOG_PRODUCT.NAME),
                            label.toString(),
                            r.get(GAME.NAME),
                            r.get(CATALOG_IMAGE.IMAGE_KEY));
                });
    }

    public SalesOrderRecord insertSalesOrder(
            String orderNumber,
            long buyerId,
            String currency,
            BigDecimal itemsSubtotal,
            BigDecimal shippingTotal,
            BigDecimal discountTotal,
            BigDecimal grandTotal,
            Long shippingAddressId,
            JSONB shippingAddressSnapshot,
            String buyerNote,
            String idempotencyKey) {

        return dsl.insertInto(SALES_ORDER)
                .set(SALES_ORDER.ORDER_NUMBER, orderNumber)
                .set(SALES_ORDER.BUYER_ID, buyerId)
                .set(SALES_ORDER.STATUS, "PENDING_PAYMENT")
                .set(SALES_ORDER.CURRENCY, currency)
                .set(SALES_ORDER.ITEMS_SUBTOTAL, itemsSubtotal)
                .set(SALES_ORDER.SHIPPING_TOTAL, shippingTotal)
                .set(SALES_ORDER.DISCOUNT_TOTAL, discountTotal)
                .set(SALES_ORDER.GRAND_TOTAL, grandTotal)
                .set(SALES_ORDER.SHIPPING_ADDRESS_ID, shippingAddressId)
                .set(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, shippingAddressSnapshot)
                .set(SALES_ORDER.BUYER_NOTE, buyerNote)
                .set(SALES_ORDER.IDEMPOTENCY_KEY, idempotencyKey)
                .returning()
                .fetchSingle();
    }

    public SellerOrderRecord insertSellerOrder(
            long salesOrderId,
            long sellerProfileId,
            String sellerOrderNumber,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal discountAmount,
            BigDecimal grandTotal,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount) {

        return dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, salesOrderId)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, sellerOrderNumber)
                .set(SELLER_ORDER.STATUS, "PENDING_PAYMENT")
                .set(SELLER_ORDER.ITEMS_SUBTOTAL, itemsSubtotal)
                .set(SELLER_ORDER.SHIPPING_FEE, shippingFee)
                .set(SELLER_ORDER.DISCOUNT_AMOUNT, discountAmount)
                .set(SELLER_ORDER.GRAND_TOTAL, grandTotal)
                .set(SELLER_ORDER.COMMISSION_AMOUNT, commissionAmount)
                .set(SELLER_ORDER.SELLER_NET_AMOUNT, sellerNetAmount)
                .returning()
                .fetchSingle();
    }

    public OrderItemRecord insertOrderItem(
            long sellerOrderId,
            Long listingId,
            long catalogVariantId,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String productNameSnapshot,
            String variantLabelSnapshot,
            String conditionSnapshot,
            String gameNameSnapshot,
            String imageKeySnapshot) {

        return dsl.insertInto(ORDER_ITEM)
                .set(ORDER_ITEM.SELLER_ORDER_ID, sellerOrderId)
                .set(ORDER_ITEM.LISTING_ID, listingId)
                .set(ORDER_ITEM.CATALOG_VARIANT_ID, catalogVariantId)
                .set(ORDER_ITEM.QUANTITY, quantity)
                .set(ORDER_ITEM.UNIT_PRICE, unitPrice)
                .set(ORDER_ITEM.LINE_TOTAL, lineTotal)
                .set(ORDER_ITEM.UNIT_COST_SNAPSHOT, BigDecimal.ZERO)
                .set(ORDER_ITEM.PRODUCT_NAME_SNAPSHOT, productNameSnapshot)
                .set(ORDER_ITEM.VARIANT_LABEL_SNAPSHOT, variantLabelSnapshot)
                .set(ORDER_ITEM.CONDITION_SNAPSHOT, conditionSnapshot)
                .set(ORDER_ITEM.GAME_NAME_SNAPSHOT, gameNameSnapshot)
                .set(ORDER_ITEM.IMAGE_KEY_SNAPSHOT, imageKeySnapshot)
                .returning()
                .fetchSingle();
    }

    public void insertOrderItemUnits(long orderItemId, Collection<Long> unitIds) {
        for (Long unitId : unitIds) {
            dsl.insertInto(ORDER_ITEM_UNIT)
                    .set(ORDER_ITEM_UNIT.ORDER_ITEM_ID, orderItemId)
                    .set(ORDER_ITEM_UNIT.LISTING_UNIT_ID, unitId)
                    .execute();
        }
    }

    public List<SalesOrderRecord> findSalesOrdersByBuyerId(long buyerId) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.BUYER_ID.eq(buyerId))
                .orderBy(SALES_ORDER.PLACED_AT.desc())
                .fetch();
    }

    public Optional<SalesOrderRecord> findSalesOrderByIdAndBuyerId(long id, long buyerId) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .and(SALES_ORDER.BUYER_ID.eq(buyerId))
                .fetchOptional();
    }

    public Optional<SalesOrderRecord> findSalesOrderByIdForUpdate(long id) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .forUpdate()
                .fetchOptional();
    }

    public Optional<SellerOrderRecord> findSellerOrderById(long id) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(id))
                .fetchOptional();
    }

    public List<SellerOrderRecord> findSellerOrdersBySellerProfileId(long sellerProfileId) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .orderBy(SELLER_ORDER.CREATED_AT.desc())
                .fetch();
    }

    public Optional<SellerOrderRecord> findSellerOrderByIdAndSellerProfileId(long id, long sellerProfileId) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(id))
                .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchOptional();
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

    public List<SellerOrderStatusHistoryRecord> findStatusHistoryBySellerOrderId(long sellerOrderId) {
        return dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(sellerOrderId))
                .orderBy(SELLER_ORDER_STATUS_HISTORY.CREATED_AT.asc(), SELLER_ORDER_STATUS_HISTORY.ID.asc())
                .fetch();
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
                .set(SELLER_ORDER.UPDATED_AT, OffsetDateTime.now());

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
                .set(SALES_ORDER.UPDATED_AT, OffsetDateTime.now());

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

    public List<SellerOrderRecord> findOverdueShippedOrders(OffsetDateTime now) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.STATUS.in("SHIPPED", "DELIVERED"))
                .and(SELLER_ORDER.AUTO_COMPLETE_AT.isNotNull())
                .and(SELLER_ORDER.AUTO_COMPLETE_AT.le(now))
                .orderBy(SELLER_ORDER.ID.asc())
                .fetch();
    }

    public int grantPurchasedUnitsToCollection(long buyerUserId, long sellerOrderId) {
        OffsetDateTime now = OffsetDateTime.now();
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
