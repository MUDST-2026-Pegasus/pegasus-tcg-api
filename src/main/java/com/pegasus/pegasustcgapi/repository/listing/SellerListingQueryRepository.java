package com.pegasus.pegasustcgapi.repository.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;

import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.SellerListingQuery;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only page and count behind the seller's own listings dashboard.
 */
@Repository
public class SellerListingQueryRepository {

    /** The card's name, read per row, so the dashboard can be sorted A to Z without joining the page. */
    private static final Field<String> PRODUCT_NAME = DSL.field(
            DSL.select(CATALOG_PRODUCT.NAME)
                    .from(CATALOG_VARIANT)
                    .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                    .where(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID)));

    private final DSLContext dsl;

    public SellerListingQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Newest change first unless the seller asks otherwise; the dashboard index is built for that one. */
    public List<Listing> findSellerPage(SellerListingQuery query) {
        return dsl.selectFrom(LISTING)
                .where(sellerConditions(query))
                .orderBy(sellerOrder(query.sort()))
                .limit(query.limit())
                .offset(query.offset())
                .fetch(ListingRepository::toListing);
    }

    public long countSeller(SellerListingQuery query) {
        return dsl.fetchCount(LISTING, sellerConditions(query));
    }

    private static Condition sellerConditions(SellerListingQuery query) {
        Condition condition = LISTING.SELLER_PROFILE_ID.eq(query.sellerProfileId())
                .and(LISTING.DELETED_AT.isNull());

        if (query.status() != null) {
            condition = condition.and(LISTING.STATUS.eq(query.status().name()));
        }
        if (query.variantId() != null) {
            condition = condition.and(LISTING.CATALOG_VARIANT_ID.eq(query.variantId()));
        }
        if (query.condition() != null) {
            condition = condition.and(LISTING.CONDITION_CODE.eq(query.condition().name()));
        }
        if (query.search() != null) {
            String pattern = ListingConditions.containsPattern(query.search());
            // A semi-join like the market's, so the page and the count share this condition unchanged.
            condition = condition.and(ListingConditions.ilike(LISTING.LOT_LABEL, pattern)
                    .or(LISTING.CATALOG_VARIANT_ID.in(
                            DSL.select(CATALOG_VARIANT.ID)
                                    .from(CATALOG_VARIANT)
                                    .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                                    .where(ListingConditions.ilike(CATALOG_PRODUCT.NAME, pattern)
                                            .or(ListingConditions.ilike(CATALOG_PRODUCT.NAME_LOCAL, pattern))
                                            .or(ListingConditions.ilike(CATALOG_PRODUCT.CARD_NUMBER, pattern))
                                            .or(ListingConditions.ilike(CATALOG_VARIANT.SKU, pattern))))));
        }
        return condition;
    }

    /** Every order ends on the id, so rows that tie keep their place from one page to the next. */
    private static List<SortField<?>> sellerOrder(SellerListingQuery.Sort sort) {
        return switch (sort) {
            case UPDATED -> List.of(LISTING.UPDATED_AT.desc(), LISTING.ID.desc());
            case NEWEST -> List.of(LISTING.CREATED_AT.desc(), LISTING.ID.desc());
            case PRICE_ASC -> List.of(LISTING.PRICE.asc(), LISTING.ID.asc());
            case PRICE_DESC -> List.of(LISTING.PRICE.desc(), LISTING.ID.desc());
            case STOCK_ASC -> List.of(LISTING.QUANTITY_AVAILABLE.asc(), LISTING.ID.asc());
            case STOCK_DESC -> List.of(LISTING.QUANTITY_AVAILABLE.desc(), LISTING.ID.desc());
            case NAME -> List.of(PRODUCT_NAME.asc(), LISTING.ID.asc());
        };
    }
}
