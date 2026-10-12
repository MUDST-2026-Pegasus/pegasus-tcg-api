package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * The market seen from a catalogue entry rather than from a listing: what a card
 * sells for now, and how much of it has been selling lately.
 *
 * <p>"On sale" here means what a buyer could put in a cart this minute — an ACTIVE
 * listing with stock, from a seller the public market shows — so the prices on a
 * browse page agree with the listings behind them.
 */
@Repository
public class ProductMarketRepository {

    /** A sub-order in one of these has been paid for and not undone, so its cards count as sold. */
    private static final Set<SellerOrderStatus> SOLD = Set.of(
            SellerOrderStatus.PAID,
            SellerOrderStatus.PREPARING,
            SellerOrderStatus.SHIPPED,
            SellerOrderStatus.DELIVERED,
            SellerOrderStatus.COMPLETED);

    /** Column names of {@link #offers}, for a caller joining it. */
    static final String OFFER_PRODUCT_ID = "offer_product_id";
    static final String OFFER_LOWEST_PRICE = "offer_lowest_price";
    static final String OFFER_LISTING_COUNT = "offer_listing_count";

    private final DSLContext dsl;

    public ProductMarketRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Keyed by product id; a product with nothing on sale is absent.
     *
     * @param conditions only listings in one of these count; empty for any
     */
    public Map<Long, Offer> offersOf(Collection<Long> productIds, Set<CardCondition> conditions) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Field<Long> productId = CATALOG_VARIANT.CATALOG_PRODUCT_ID;
        Field<BigDecimal> lowest = DSL.min(LISTING.PRICE);
        Field<Integer> listings = DSL.count();

        return dsl.select(productId, lowest, listings)
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .where(onSale())
                .and(inCondition(conditions))
                .and(productId.in(productIds))
                .groupBy(productId)
                .fetchMap(productId, r -> new Offer(r.get(lowest), r.get(listings)));
    }

    /**
     * One row per product on sale — its cheapest listing and how many there are —
     * as a derived table a browse query can join to filter and sort by price.
     * Read its columns with the {@code OFFER_*} names.
     *
     * <p>Built from the same rule as {@link #offersOf}, so a page filtered by
     * price shows the prices it was filtered on.
     */
    static Table<?> offers(Set<CardCondition> conditions) {
        return DSL.select(
                        CATALOG_VARIANT.CATALOG_PRODUCT_ID.as(OFFER_PRODUCT_ID),
                        DSL.min(LISTING.PRICE).as(OFFER_LOWEST_PRICE),
                        DSL.count().as(OFFER_LISTING_COUNT))
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .where(onSale())
                .and(inCondition(conditions))
                .groupBy(CATALOG_VARIANT.CATALOG_PRODUCT_ID)
                .asTable("offer");
    }

    /**
     * Active products on sale now, most cards sold since {@code since} first.
     *
     * <p>A quiet week still fills the rail: ties, including everything at zero,
     * go to the card more sellers are offering, then to the newer one.
     *
     * @param gameId null ranks every game together
     */
    public List<TrendingRow> trending(Short gameId, OffsetDateTime since, int limit) {
        Table<?> sold = DSL.select(
                        CATALOG_VARIANT.CATALOG_PRODUCT_ID.as("product_id"),
                        DSL.sum(ORDER_ITEM.QUANTITY).as("units"))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(ORDER_ITEM.CATALOG_VARIANT_ID))
                .where(SELLER_ORDER.STATUS.in(SOLD.stream().map(Enum::name).toList()))
                .and(ORDER_ITEM.CREATED_AT.ge(since))
                .groupBy(CATALOG_VARIANT.CATALOG_PRODUCT_ID)
                .asTable("sold");

        Field<Long> soldProduct = sold.field("product_id", Long.class);
        Field<Long> units = DSL.coalesce(sold.field("units", BigDecimal.class), BigDecimal.ZERO)
                .cast(Long.class).as("units_sold");

        // Joining the offers keeps the rail to cards on sale and gives the tie-break
        // in the same pass: one aggregate, not a count per product.
        Table<?> offers = offers(Set.of());
        Field<Long> offerProduct = offers.field(OFFER_PRODUCT_ID, Long.class);
        Field<Integer> listings = offers.field(OFFER_LISTING_COUNT, Integer.class);

        Condition ofGame = gameId == null ? DSL.noCondition() : CATALOG_PRODUCT.GAME_ID.eq(gameId);

        return dsl.select(CATALOG_PRODUCT.ID, units)
                .from(CATALOG_PRODUCT)
                .join(offers).on(offerProduct.eq(CATALOG_PRODUCT.ID))
                .leftJoin(sold).on(soldProduct.eq(CATALOG_PRODUCT.ID))
                .where(CATALOG_PRODUCT.IS_ACTIVE.isTrue())
                .and(ofGame)
                .orderBy(units.desc(), listings.desc(), CATALOG_PRODUCT.CREATED_AT.desc(), CATALOG_PRODUCT.ID.desc())
                .limit(limit)
                .fetch(r -> new TrendingRow(r.get(CATALOG_PRODUCT.ID), r.get(units)));
    }

    private static Condition inCondition(Set<CardCondition> conditions) {
        return conditions == null || conditions.isEmpty()
                ? DSL.noCondition()
                : LISTING.CONDITION_CODE.in(conditions.stream().map(Enum::name).toList());
    }

    /** Same rule as the public market: ACTIVE, stock left, and a seller open for business. */
    private static Condition onSale() {
        return ListingRepository.sellerOpenForBusiness()
                .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()))
                .and(LISTING.QUANTITY_AVAILABLE.gt(0));
    }

    /** @param listingCount listings on sale in the conditions asked for (every condition when none were) */
    public record Offer(BigDecimal lowestPrice, int listingCount) {
    }

    public record TrendingRow(long productId, long unitsSold) {
    }
}
