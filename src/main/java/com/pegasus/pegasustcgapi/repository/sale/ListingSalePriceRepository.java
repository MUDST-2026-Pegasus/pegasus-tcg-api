package com.pegasus.pegasustcgapi.repository.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingSalePriceRecord;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.Resolved;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code listing_sale_price}, the sale price each listing has
 * right now [TCG-676], and the listing facts it is worked out from.
 */
@Repository
public class ListingSalePriceRepository {

    private final DSLContext dsl;

    public ListingSalePriceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Game, category and set come from the catalogue; deleted listings are left out. */
    public List<ListingFacts> findFacts(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(LISTING.ID, LISTING.SELLER_PROFILE_ID, CATALOG_PRODUCT.GAME_ID,
                        CATALOG_PRODUCT.CATEGORY_ID, CATALOG_PRODUCT.CARD_SET_ID, LISTING.CONDITION_CODE,
                        LISTING.PRICE, LISTING.STATUS)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(LISTING.ID.in(listingIds))
                .and(LISTING.DELETED_AT.isNull())
                .orderBy(LISTING.ID)
                .fetch(r -> new ListingFacts(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5() == null ? null : r.value5().longValue(),
                        CardCondition.valueOf(r.value6()),
                        r.value7(),
                        ListingStatus.valueOf(r.value8())));
    }

    /**
     * The next {@code limit} listing ids after {@code afterId} that may need a row:
     * every ACTIVE listing when {@code includeActive}, and every listing that has
     * a row now (so one whose sale ended loses it).
     */
    public List<Long> nextCandidates(long afterId, int limit, boolean includeActive) {
        var withRow = DSL.select(LISTING_SALE_PRICE.LISTING_ID.as("id"))
                .from(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.LISTING_ID.gt(afterId));
        var ids = includeActive
                ? withRow.union(DSL.select(LISTING.ID.as("id"))
                        .from(LISTING)
                        .where(LISTING.ID.gt(afterId))
                        .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()))
                        .and(LISTING.DELETED_AT.isNull()))
                : withRow;
        var t = ids.asTable("candidate");
        var id = t.field("id", Long.class);
        return dsl.select(id).from(t).orderBy(id).limit(limit).fetch(id);
    }

    public Map<Long, Resolved> findByListingIds(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.LISTING_ID.in(listingIds))
                .fetchMap(ListingSalePriceRecord::getListingId, ListingSalePriceRepository::toResolved);
    }

    /**
     * A listing's sale price as a buyer is shown it, with the sale it comes from.
     *
     * @param perBuyerLimit cards one buyer may buy at this price; null means no limit
     */
    public record Shown(Resolved price, String slug, String name, SaleType saleType, Integer perBuyerLimit) {
    }

    /**
     * The rows still running at {@code now} [TCG-679]. A row whose sale has just
     * ended stays in the table until the next refresh; it is left out here so a
     * buyer is never shown a price that checkout would refuse.
     */
    public Map<Long, Shown> findShown(Collection<Long> listingIds, OffsetDateTime now) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(LISTING_SALE_PRICE.fields())
                .select(PRICE_SALE.SLUG, PRICE_SALE.NAME, PRICE_SALE.SALE_TYPE, PRICE_SALE.PER_BUYER_LIMIT)
                .from(LISTING_SALE_PRICE)
                .join(PRICE_SALE).on(PRICE_SALE.ID.eq(LISTING_SALE_PRICE.SALE_ID))
                .where(LISTING_SALE_PRICE.LISTING_ID.in(listingIds))
                .and(LISTING_SALE_PRICE.ENDS_AT.gt(now))
                .fetchMap(r -> r.get(LISTING_SALE_PRICE.LISTING_ID), r -> new Shown(
                        toResolved(r.into(LISTING_SALE_PRICE)),
                        r.get(PRICE_SALE.SLUG),
                        r.get(PRICE_SALE.NAME),
                        SaleType.valueOf(r.get(PRICE_SALE.SALE_TYPE)),
                        r.get(PRICE_SALE.PER_BUYER_LIMIT)));
    }

    public Optional<Resolved> findByListingId(long listingId) {
        return dsl.selectFrom(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.LISTING_ID.eq(listingId))
                .fetchOptional()
                .map(ListingSalePriceRepository::toResolved);
    }

    public void upsert(Resolved r, OffsetDateTime at) {
        dsl.insertInto(LISTING_SALE_PRICE)
                .set(LISTING_SALE_PRICE.LISTING_ID, r.listingId())
                .set(LISTING_SALE_PRICE.SALE_ID, r.saleId())
                .set(LISTING_SALE_PRICE.SALE_PRICE, r.salePrice())
                .set(LISTING_SALE_PRICE.DISCOUNT_AMOUNT, r.discountAmount())
                .set(LISTING_SALE_PRICE.FUNDED_BY, r.fundedBy().name())
                .set(LISTING_SALE_PRICE.ENDS_AT, r.endsAt())
                .set(LISTING_SALE_PRICE.QUOTA_LEFT, r.quotaLeft())
                .set(LISTING_SALE_PRICE.REFRESHED_AT, at)
                .onConflict(LISTING_SALE_PRICE.LISTING_ID)
                .doUpdate()
                .set(LISTING_SALE_PRICE.SALE_ID, r.saleId())
                .set(LISTING_SALE_PRICE.SALE_PRICE, r.salePrice())
                .set(LISTING_SALE_PRICE.DISCOUNT_AMOUNT, r.discountAmount())
                .set(LISTING_SALE_PRICE.FUNDED_BY, r.fundedBy().name())
                .set(LISTING_SALE_PRICE.ENDS_AT, r.endsAt())
                .set(LISTING_SALE_PRICE.QUOTA_LEFT, r.quotaLeft())
                .set(LISTING_SALE_PRICE.REFRESHED_AT, at)
                .execute();
    }

    public int delete(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return 0;
        }
        return dsl.deleteFrom(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.LISTING_ID.in(listingIds))
                .execute();
    }

    private static Resolved toResolved(ListingSalePriceRecord r) {
        return new Resolved(r.getListingId(), r.getSaleId(), r.getSalePrice(), r.getDiscountAmount(),
                OwnerType.valueOf(r.getFundedBy()), r.getEndsAt(), r.getQuotaLeft());
    }
}
