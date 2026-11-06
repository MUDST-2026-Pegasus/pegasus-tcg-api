package com.pegasus.pegasustcgapi.repository.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnItem.RETURN_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class StockForecastRepository {
    public record Row(long listingId, String listingName, String listingStatus, int availableStock, int sales) {}

    private final DSLContext db;

    public StockForecastRepository(DSLContext db) {
        this.db = db;
    }

    public List<Row> list(long sellerId, OffsetDateTime start, OffsetDateTime end) {
        return fetch(sellerId, start, end, LISTING.SELLER_PROFILE_ID.eq(sellerId));
    }

    public Optional<Row> detail(long sellerId, long listingId, OffsetDateTime start, OffsetDateTime end) {
        return fetch(sellerId, start, end, LISTING.SELLER_PROFILE_ID.eq(sellerId).and(LISTING.ID.eq(listingId)))
                .stream().findFirst();
    }

    private List<Row> fetch(long sellerId, OffsetDateTime start, OffsetDateTime end, Condition scope) {
        Field<Integer> returned = DSL.coalesce(DSL.field(DSL.select(DSL.sum(RETURN_ITEM.QUANTITY))
                .from(RETURN_ITEM)
                .join(RETURN_REQUEST).on(RETURN_REQUEST.ID.eq(RETURN_ITEM.RETURN_REQUEST_ID))
                .where(RETURN_ITEM.ORDER_ITEM_ID.eq(ORDER_ITEM.ID))
                .and(RETURN_REQUEST.STATUS.in("REFUNDED", "CLOSED"))).cast(Integer.class), DSL.inline(0));
        Field<Integer> sales = DSL.coalesce(DSL.field(DSL.select(DSL.sum(
                DSL.greatest(ORDER_ITEM.QUANTITY.minus(returned), DSL.inline(0))))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .where(ORDER_ITEM.LISTING_ID.eq(LISTING.ID))
                .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerId))
                .and(SELLER_ORDER.STATUS.eq("COMPLETED"))
                .and(SELLER_ORDER.COMPLETED_AT.ge(start))
                .and(SELLER_ORDER.COMPLETED_AT.lt(end))).cast(Integer.class), DSL.inline(0));
        Field<String> listingName = DSL.concat(CATALOG_PRODUCT.NAME, DSL.inline(" · "),
                LISTING.CONDITION_CODE, DSL.inline(" · #"), LISTING.ID.cast(String.class));

        return db.select(LISTING.ID, listingName, LISTING.STATUS, LISTING.QUANTITY_AVAILABLE, sales)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(scope).and(LISTING.DELETED_AT.isNull())
                .orderBy(LISTING.ID)
                .fetch(row -> new Row(row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }
}
