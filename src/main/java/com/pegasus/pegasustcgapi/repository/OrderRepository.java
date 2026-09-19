package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;

import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
}
