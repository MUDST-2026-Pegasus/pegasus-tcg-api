package com.pegasus.pegasustcgapi.repository.catalog;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.repository.listing.ListingConditions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Counts for the search page (TCG-775): how many results each sidebar option
 * would give, and the shops selling what a search found. Every count runs over
 * {@link CatalogProductRepository#matchingIds}, so it agrees with the result list.
 */
@Repository
public class SearchFacetRepository {

    private final DSLContext dsl;
    private final CatalogProductRepository products;

    public SearchFacetRepository(DSLContext dsl, CatalogProductRepository products) {
        this.dsl = dsl;
        this.products = products;
    }

    /** Leaves out the game filter, so picking Pokémon still shows how many One Piece cards match. */
    public List<Count> games(ProductSearchQuery query) {
        return dsl.select(GAME.ID.cast(Long.class), GAME.NAME, DSL.inline((Long) null), DSL.count())
                .from(CATALOG_PRODUCT).join(GAME).on(GAME.ID.eq(CATALOG_PRODUCT.GAME_ID))
                .where(CATALOG_PRODUCT.ID.in(products.matchingIds(query.withoutGames())))
                .groupBy(GAME.ID, GAME.NAME)
                .orderBy(DSL.count().desc(), GAME.NAME)
                .fetch(r -> new Count(r.value1(), r.value2(), null, r.value4()));
    }

    public List<Count> categories(ProductSearchQuery query) {
        return dsl.select(CATALOG_CATEGORY.ID, CATALOG_CATEGORY.NAME, CATALOG_CATEGORY.PARENT_ID, DSL.count())
                .from(CATALOG_PRODUCT).join(CATALOG_CATEGORY).on(CATALOG_CATEGORY.ID.eq(CATALOG_PRODUCT.CATEGORY_ID))
                .where(CATALOG_PRODUCT.ID.in(products.matchingIds(query.withoutCategory())))
                .groupBy(CATALOG_CATEGORY.ID, CATALOG_CATEGORY.NAME, CATALOG_CATEGORY.PARENT_ID)
                .orderBy(DSL.count().desc(), CATALOG_CATEGORY.NAME)
                .fetch(r -> new Count(r.value1().longValue(), r.value2(),
                        r.value3() == null ? null : r.value3().longValue(), r.value4()));
    }

    /** Products a buyer could get in each condition now; the id is unused, the name is the condition code. */
    public List<Count> conditions(ProductSearchQuery query) {
        Field<Integer> count = DSL.countDistinct(CATALOG_VARIANT.CATALOG_PRODUCT_ID);
        return dsl.select(LISTING.CONDITION_CODE, count)
                .from(listings())
                .where(ListingConditions.purchasableNow())
                .and(CATALOG_VARIANT.CATALOG_PRODUCT_ID.in(products.matchingIds(query.withoutConditions())))
                .groupBy(LISTING.CONDITION_CODE)
                .orderBy(LISTING.CONDITION_CODE)
                .fetch(r -> new Count(null, r.get(LISTING.CONDITION_CODE), null, r.get(count)));
    }

    /** Listings and distinct shops behind the products a search found. */
    public Totals totals(ProductSearchQuery query) {
        Condition inCondition = query.conditions().isEmpty() ? DSL.noCondition()
                : LISTING.CONDITION_CODE.in(query.conditions().stream().map(Enum::name).toList());
        var row = dsl.select(DSL.count(), DSL.countDistinct(LISTING.SELLER_PROFILE_ID))
                .from(listings())
                .where(ListingConditions.purchasableNow())
                .and(inCondition)
                .and(CATALOG_VARIANT.CATALOG_PRODUCT_ID.in(products.matchingIds(query)))
                .fetchSingle();
        return new Totals(row.value1(), row.value2());
    }

    /**
     * Shops with something a buyer can take now among the products the search found.
     *
     * @param minRating null for any rating
     * @param byRating  rating first, else most matching products first
     */
    public List<Shop> shops(ProductSearchQuery query, BigDecimal minRating, boolean byRating, int limit,
            int offset, OffsetDateTime now) {
        Field<Integer> matches = DSL.countDistinct(CATALOG_VARIANT.CATALOG_PRODUCT_ID).as("match_count");
        Field<BigDecimal> lowest = DSL.min(ListingConditions.EFFECTIVE_PRICE).as("lowest_price");
        Field<BigDecimal> rating = DSL.coalesce(SELLER_RATING_SUMMARY.RATING_AVG, BigDecimal.ZERO).as("rating_avg");
        Field<Integer> ratingCount = DSL.coalesce(SELLER_RATING_SUMMARY.RATING_COUNT, 0).as("rating_count");
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME,
                        USER_ACCOUNT.AVATAR_URL, rating, ratingCount, matches, lowest)
                .from(shopFrom(now))
                .where(shopConditions(query, minRating))
                .groupBy(SELLER_PROFILE.ID, USER_ACCOUNT.ID, SELLER_RATING_SUMMARY.RATING_AVG,
                        SELLER_RATING_SUMMARY.RATING_COUNT)
                .orderBy(byRating
                        ? new org.jooq.OrderField<?>[] {rating.desc(), matches.desc(), SELLER_PROFILE.ID.asc()}
                        : new org.jooq.OrderField<?>[] {matches.desc(), rating.desc(), SELLER_PROFILE.ID.asc()})
                .limit(limit).offset(offset)
                .fetch(r -> new Shop(r.get(SELLER_PROFILE.ID), r.get(USER_ACCOUNT.USERNAME),
                        r.get(USER_ACCOUNT.DISPLAY_NAME), r.get(USER_ACCOUNT.AVATAR_URL), r.get(rating),
                        r.get(ratingCount), r.get(matches), r.get(lowest)));
    }

    public long countShops(ProductSearchQuery query, BigDecimal minRating, OffsetDateTime now) {
        return dsl.select(DSL.countDistinct(SELLER_PROFILE.ID))
                .from(shopFrom(now))
                .where(shopConditions(query, minRating))
                .fetchSingle(0, long.class);
    }

    /** Up to three matched products per shop, newest listing first, for the shop card thumbnails. */
    public List<Preview> previews(ProductSearchQuery query, List<Long> sellerIds) {
        if (sellerIds.isEmpty()) {
            return List.of();
        }
        Field<Long> product = CATALOG_VARIANT.CATALOG_PRODUCT_ID;
        Field<Integer> position = DSL.rowNumber().over(DSL.partitionBy(LISTING.SELLER_PROFILE_ID)
                .orderBy(DSL.max(LISTING.CREATED_AT).desc(), product.desc())).as("position");
        return dsl.select(LISTING.SELLER_PROFILE_ID, product, position)
                .from(listings())
                .where(ListingConditions.purchasableNow())
                .and(LISTING.SELLER_PROFILE_ID.in(sellerIds))
                .and(product.in(products.matchingIds(query)))
                .groupBy(LISTING.SELLER_PROFILE_ID, product)
                .fetch(r -> new Preview(r.get(LISTING.SELLER_PROFILE_ID), r.get(product), r.get(position)))
                .stream().filter(p -> p.position() <= 3).toList();
    }

    private static org.jooq.Table<?> listings() {
        return LISTING
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID));
    }

    private static org.jooq.Table<?> shopFrom(OffsetDateTime now) {
        return listings()
                .leftJoin(LISTING_SALE_PRICE).on(ListingConditions.salePriceRunning(now))
                .leftJoin(SELLER_RATING_SUMMARY).on(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID));
    }
    private Condition shopConditions(ProductSearchQuery query, BigDecimal minRating) {
        return ListingConditions.purchasableNow()
                .and(CATALOG_VARIANT.CATALOG_PRODUCT_ID.in(products.matchingIds(query)))
                .and(minRating == null ? DSL.noCondition() : SELLER_RATING_SUMMARY.RATING_AVG.ge(minRating));
    }

    /** @param id null for conditions; parentId only for categories */
    public record Count(Long id, String name, Long parentId, int count) {
    }

    public record Totals(int listings, int shops) {
    }

    public record Shop(long sellerId, String username, String displayName, String avatarKey,
            BigDecimal ratingAvg, int ratingCount, int matchCount, BigDecimal lowestPrice) {
    }

    public record Preview(long sellerId, long productId, int position) {
    }
}
