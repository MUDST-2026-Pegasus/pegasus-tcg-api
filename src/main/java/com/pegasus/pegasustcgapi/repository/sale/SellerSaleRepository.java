package com.pegasus.pegasustcgapi.repository.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSaleListing.PRICE_SALE_LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;

import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.PlatformListing;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read side of a shop's own sales [TCG-678], always within one shop. Writes to
 * {@code price_sale} stay in {@link PriceSaleRepository}.
 */
@Repository
public class SellerSaleRepository {

    private final DSLContext dsl;

    public SellerSaleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** A sale with what the list shows next to it. */
    public record SaleRow(PriceSale sale, SaleStatus status, long soldUnits, long listingCount) {
    }

    /** A chosen listing of a LISTINGS sale, as the shop sees it. */
    public record ItemRow(long listingId, String productName, CardCondition condition, BigDecimal listPrice,
            BigDecimal rowSalePrice) {
    }

    public List<SaleRow> page(long shopId, SaleStatus status, Paging paging, OffsetDateTime now) {
        Field<String> st = AdminSaleRepository.status(now).as("sale_status");
        return dsl.select(PRICE_SALE.fields())
                .select(AdminSaleRepository.SOLD_UNITS, AdminSaleRepository.LISTING_COUNT, st)
                .from(PRICE_SALE)
                .where(ownedBy(shopId))
                .and(status == null ? DSL.noCondition() : AdminSaleRepository.status(now).eq(status.name()))
                .orderBy(PRICE_SALE.CREATED_AT.desc(), PRICE_SALE.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> toRow(r, st));
    }

    public long count(long shopId, SaleStatus status, OffsetDateTime now) {
        return dsl.fetchCount(PRICE_SALE, ownedBy(shopId)
                .and(status == null ? DSL.noCondition() : AdminSaleRepository.status(now).eq(status.name())));
    }

    public Map<SaleStatus, Long> statusCounts(long shopId, OffsetDateTime now) {
        Map<SaleStatus, Long> counts = new EnumMap<>(SaleStatus.class);
        for (SaleStatus s : SaleStatus.values()) counts.put(s, 0L);
        Field<String> st = AdminSaleRepository.status(now).as("sale_status");
        dsl.select(st, DSL.count())
                .from(PRICE_SALE)
                .where(ownedBy(shopId))
                .groupBy(st)
                .fetch()
                .forEach(r -> counts.put(SaleStatus.valueOf(r.value1()), r.value2().longValue()));
        return counts;
    }

    /** Another shop's sale, or Pegasus's, is absent. */
    public Optional<SaleRow> find(long id, long shopId, OffsetDateTime now) {
        Field<String> st = AdminSaleRepository.status(now).as("sale_status");
        return dsl.select(PRICE_SALE.fields())
                .select(AdminSaleRepository.SOLD_UNITS, AdminSaleRepository.LISTING_COUNT, st)
                .from(PRICE_SALE)
                .where(PRICE_SALE.ID.eq(id))
                .and(ownedBy(shopId))
                .fetchOptional(r -> toRow(r, st));
    }

    /** The row locked until the transaction ends; empty when it is not this shop's. */
    public Optional<PriceSale> lockOwn(long id, long shopId) {
        return dsl.selectFrom(PRICE_SALE)
                .where(PRICE_SALE.ID.eq(id))
                .and(ownedBy(shopId))
                .forUpdate()
                .fetchOptional()
                .map(PriceSaleRepository::toSale);
    }

    /** The shop's other sales that have not ended, for the overlap check. */
    public List<PriceSale> findOpen(long shopId, Long exceptId, OffsetDateTime now) {
        return dsl.selectFrom(PRICE_SALE)
                .where(ownedBy(shopId))
                .and(exceptId == null ? DSL.noCondition() : PRICE_SALE.ID.ne(exceptId))
                .and(PRICE_SALE.ENDS_AT.gt(now))
                .and(PRICE_SALE.ENDED_AT.isNull().or(PRICE_SALE.ENDED_AT.gt(now)))
                .orderBy(PRICE_SALE.ID)
                .fetch(PriceSaleRepository::toSale);
    }

    /** Every listing of the shop that is not deleted, whatever its status. */
    public List<Long> shopListingIds(long shopId) {
        return dsl.select(LISTING.ID)
                .from(LISTING)
                .where(LISTING.SELLER_PROFILE_ID.eq(shopId))
                .and(LISTING.DELETED_AT.isNull())
                .orderBy(LISTING.ID)
                .fetch(LISTING.ID);
    }

    /** The listings of a LISTINGS sale, in listing order. */
    public List<ItemRow> items(long saleId) {
        return dsl.select(LISTING.ID, CATALOG_PRODUCT.NAME, LISTING.CONDITION_CODE, LISTING.PRICE,
                        PRICE_SALE_LISTING.SALE_PRICE)
                .from(PRICE_SALE_LISTING)
                .join(LISTING).on(LISTING.ID.eq(PRICE_SALE_LISTING.LISTING_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(PRICE_SALE_LISTING.SALE_ID.eq(saleId))
                .orderBy(LISTING.ID)
                .fetch(r -> new ItemRow(r.value1(), r.value2(), CardCondition.valueOf(r.value3()), r.value4(),
                        r.value5()));
    }

    /** Drops the price_sale_listing rows of listings no longer in the sale. */
    public void removeListingsExcept(long saleId, Collection<Long> keep) {
        dsl.deleteFrom(PRICE_SALE_LISTING)
                .where(PRICE_SALE_LISTING.SALE_ID.eq(saleId))
                .and(keep.isEmpty() ? DSL.noCondition() : PRICE_SALE_LISTING.LISTING_ID.notIn(keep))
                .execute();
    }

    public long countActive(long shopId, OffsetDateTime now) {
        return dsl.fetchCount(PRICE_SALE, ownedBy(shopId)
                .and(AdminSaleRepository.status(now).eq(SaleStatus.ACTIVE.name())));
    }

    /** The shop's listings selling at one of its own sale prices now. */
    public long countListingsOnSale(long shopId) {
        return dsl.fetchCount(DSL.selectOne()
                .from(LISTING_SALE_PRICE)
                .join(PRICE_SALE).on(PRICE_SALE.ID.eq(LISTING_SALE_PRICE.SALE_ID))
                .where(ownedBy(shopId)));
    }

    /** What buyers paid, and what the shop gave away, on cards sold at its own sale prices since {@code from}. */
    public BigDecimal[] saleTotals(long shopId, OffsetDateTime from) {
        Record r = dsl.select(
                        DSL.coalesce(DSL.sum(ORDER_ITEM.LINE_TOTAL), BigDecimal.ZERO),
                        DSL.coalesce(DSL.sum(ORDER_ITEM.SALE_DISCOUNT.mul(ORDER_ITEM.QUANTITY)), BigDecimal.ZERO))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(shopId))
                .and(ORDER_ITEM.SALE_FUNDED_BY.eq(OwnerType.SELLER.name()))
                .and(SALES_ORDER.STATUS.in(AdminSaleRepository.PAID))
                .and(SALES_ORDER.PAID_AT.ge(from))
                .fetchSingle();
        return new BigDecimal[] {r.get(0, BigDecimal.class), r.get(1, BigDecimal.class)};
    }

    /** The shop's listings selling at a Pegasus campaign's price now, soonest-ending first. */
    public List<PlatformListing> platformListings(long shopId, Paging paging) {
        return dsl.select(LISTING.ID, CATALOG_PRODUCT.NAME, LISTING.CONDITION_CODE, LISTING.PRICE,
                        LISTING_SALE_PRICE.SALE_PRICE, PRICE_SALE.ID, PRICE_SALE.NAME, PRICE_SALE.SLUG,
                        LISTING_SALE_PRICE.ENDS_AT)
                .from(LISTING_SALE_PRICE)
                .join(LISTING).on(LISTING.ID.eq(LISTING_SALE_PRICE.LISTING_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .join(PRICE_SALE).on(PRICE_SALE.ID.eq(LISTING_SALE_PRICE.SALE_ID))
                .where(platformOn(shopId))
                .orderBy(LISTING_SALE_PRICE.ENDS_AT.asc(), LISTING.ID.asc())
                .limit(paging.size())
                .offset(paging.offset())
                // A running Pegasus campaign is what holds this price, so it locks it too.
                .fetch(r -> new PlatformListing(r.value1(), r.value2(), CardCondition.valueOf(r.value3()),
                        r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9(), true));
    }

    public long countPlatformListings(long shopId) {
        return dsl.fetchCount(DSL.selectOne()
                .from(LISTING_SALE_PRICE)
                .join(LISTING).on(LISTING.ID.eq(LISTING_SALE_PRICE.LISTING_ID))
                .where(platformOn(shopId)));
    }

    private static Condition platformOn(long shopId) {
        return LISTING.SELLER_PROFILE_ID.eq(shopId)
                .and(LISTING_SALE_PRICE.FUNDED_BY.eq(OwnerType.PLATFORM.name()));
    }

    private static Condition ownedBy(long shopId) {
        return PRICE_SALE.OWNER_TYPE.eq(OwnerType.SELLER.name()).and(PRICE_SALE.SELLER_PROFILE_ID.eq(shopId));
    }

    private static SaleRow toRow(Record r, Field<String> status) {
        return new SaleRow(PriceSaleRepository.toSale(r.into(PRICE_SALE)), SaleStatus.valueOf(r.get(status)),
                r.get(AdminSaleRepository.SOLD_UNITS), r.get(AdminSaleRepository.LISTING_COUNT));
    }
}
