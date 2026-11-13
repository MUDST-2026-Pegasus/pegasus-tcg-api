package com.pegasus.pegasustcgapi.repository.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerShippingOption.SELLER_SHIPPING_OPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.Delivery;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.Offer;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.PublicListing;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.PublicListingQuery;
import com.pegasus.pegasustcgapi.repository.metrics.AdminMetricsRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.SelectOnConditionStep;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries for what buyers see: the public listing pages, one listing, and the
 * competing offers of a market.
 */
@Repository
public class PublicListingQueryRepository {

    // Aliased: listing, seller_profile and user_account each have a status and a
    // deleted_at, and a record carrying two columns of one name maps ambiguously.
    private static final Field<String> SELLER_STATUS = SELLER_PROFILE.STATUS.as("seller_status");
    private static final Field<UserStatus> ACCOUNT_STATUS = USER_ACCOUNT.STATUS.as("account_status");
    private static final Field<OffsetDateTime> ACCOUNT_DELETED_AT = USER_ACCOUNT.DELETED_AT.as("account_deleted_at");

    /** The discount as a share of the asking price; 0 off sale. */
    private static final Field<BigDecimal> DISCOUNT_SHARE = DSL.coalesce(
            LISTING_SALE_PRICE.DISCOUNT_AMOUNT.div(LISTING.PRICE), DSL.inline(BigDecimal.ZERO));

    private final DSLContext dsl;

    public PublicListingQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<PublicListing> findPublicPage(PublicListingQuery query) {
        return enrich(publicSelect()
                .leftJoin(LISTING_SALE_PRICE).on(ListingConditions.salePriceRunning(query.now()))
                .where(publicConditions(query))
                .orderBy(order(query.sort()))
                .limit(query.limit())
                .offset(query.offset())
                .fetch(PublicListingQueryRepository::toPublic));
    }

    public long countPublic(PublicListingQuery query) {
        return dsl.selectCount()
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(LISTING_SALE_PRICE).on(ListingConditions.salePriceRunning(query.now()))
                .where(publicConditions(query))
                .fetchSingle(0, long.class);
    }

    /**
     * These listings as the market shows them, in id order: on sale or sold out,
     * from a seller open for business; the rest are left out.
     */
    public List<PublicListing> findPublicByIds(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        return enrich(publicSelect()
                .where(LISTING.ID.in(listingIds))
                .and(ListingConditions.sellerOpenForBusiness())
                .and(LISTING.STATUS.in(ListingStatus.ACTIVE.name(), ListingStatus.SOLD_OUT.name()))
                .orderBy(LISTING.ID)
                .fetch(PublicListingQueryRepository::toPublic));
    }

    /** A page a buyer may open: on sale or sold out, from a seller who is open for business. */
    public Optional<PublicListing> findPublic(long listingId) {
        List<PublicListing> rows = publicSelect()
                .where(LISTING.ID.eq(listingId))
                .and(ListingConditions.sellerOpenForBusiness())
                .and(LISTING.STATUS.in(ListingStatus.ACTIVE.name(), ListingStatus.SOLD_OUT.name()))
                .fetch(PublicListingQueryRepository::toPublic);
        return enrich(rows).stream().findFirst();
    }

    /** Deleted listings are left out; everything else comes back with the seller's standing. */
    public List<Offer> findOffers(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        List<SelectField<?>> fields = new ArrayList<>(List.of(LISTING.fields()));
        fields.add(SELLER_STATUS);
        fields.add(SELLER_PROFILE.VACATION_MODE);
        fields.add(ACCOUNT_STATUS);
        fields.add(ACCOUNT_DELETED_AT);
        fields.add(USER_ACCOUNT.DISPLAY_NAME);
        fields.add(USER_ACCOUNT.USERNAME);

        return dsl.select(fields)
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(LISTING.ID.in(listingIds))
                .and(LISTING.DELETED_AT.isNull())
                .fetch(r -> new Offer(
                        ListingRepository.toListing(r.into(LISTING)),
                        SellerStatus.valueOf(r.get(SELLER_STATUS)),
                        r.get(SELLER_PROFILE.VACATION_MODE),
                        r.get(ACCOUNT_STATUS) == UserStatus.ACTIVE && r.get(ACCOUNT_DELETED_AT) == null,
                        r.get(USER_ACCOUNT.DISPLAY_NAME),
                        r.get(USER_ACCOUNT.USERNAME)));
    }

    private SelectOnConditionStep<Record> publicSelect() {
        List<SelectField<?>> fields = new ArrayList<>(List.of(LISTING.fields()));
        fields.add(USER_ACCOUNT.USERNAME);
        fields.add(USER_ACCOUNT.DISPLAY_NAME);
        fields.add(USER_ACCOUNT.AVATAR_URL);
        fields.add(SELLER_PROFILE.STATUS);
        fields.add(SELLER_RATING_SUMMARY.RATING_AVG);
        fields.add(SELLER_RATING_SUMMARY.RATING_COUNT);

        return dsl.select(fields)
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(SELLER_RATING_SUMMARY).on(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID));
    }

    /** Two grouped reads for the whole page, regardless of how many listings it contains. */
    private List<PublicListing> enrich(List<PublicListing> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> sellerIds = rows.stream().map(r -> r.listing().sellerProfileId()).distinct().toList();
        Map<Long, Integer> sales = dsl.select(SELLER_ORDER.SELLER_PROFILE_ID, DSL.count())
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.in(sellerIds))
                .and(SELLER_ORDER.STATUS.in(AdminMetricsRepository.SOLD_STATUSES))
                .groupBy(SELLER_ORDER.SELLER_PROFILE_ID)
                .fetchMap(SELLER_ORDER.SELLER_PROFILE_ID, DSL.count());

        Map<Long, List<ShippingOption>> shipping = new HashMap<>();
        dsl.select(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID, SELLER_SHIPPING_OPTION.EST_DAYS_MIN,
                        SELLER_SHIPPING_OPTION.EST_DAYS_MAX, SELLER_SHIPPING_OPTION.BASE_FEE,
                        SELLER_SHIPPING_OPTION.FREE_THRESHOLD)
                .from(SELLER_SHIPPING_OPTION)
                .where(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.in(sellerIds))
                .and(SELLER_SHIPPING_OPTION.IS_ACTIVE.isTrue())
                .fetch().forEach(r -> shipping.computeIfAbsent(r.value1(), ignored -> new ArrayList<>())
                        .add(new ShippingOption(r.value2(), r.value3(), r.value4(), r.value5())));

        return rows.stream().map(row -> {
            List<ShippingOption> options = shipping.getOrDefault(row.listing().sellerProfileId(), List.of());
            ShippingOption fastest = options.stream()
                    .filter(o -> o.maxDays() != null)
                    .min(Comparator.comparing(ShippingOption::maxDays)
                            .thenComparing(ShippingOption::minDays, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            boolean free = options.stream().anyMatch(o -> o.baseFee().signum() == 0
                    || o.freeThreshold() != null && row.listing().price().compareTo(o.freeThreshold()) >= 0);
            return new PublicListing(row.listing(), row.sellerUsername(), row.sellerDisplayName(),
                    row.sellerAvatarUrl(), row.verified(), row.ratingAvg(), row.ratingCount(),
                    sales.getOrDefault(row.listing().sellerProfileId(), 0),
                    new Delivery(fastest == null ? null : fastest.minDays(),
                            fastest == null ? null : fastest.maxDays(), free, options.size()));
        }).toList();
    }

    private record ShippingOption(Short minDays, Short maxDays, BigDecimal baseFee, BigDecimal freeThreshold) {}

    private static Condition publicConditions(PublicListingQuery query) {
        Condition condition = ListingConditions.sellerOpenForBusiness()
                .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()));

        if (query.sellerUserId() != null) {
            condition = condition.and(USER_ACCOUNT.ID.eq(query.sellerUserId()));
        }
        if (query.variantId() != null) {
            condition = condition.and(LISTING.CATALOG_VARIANT_ID.eq(query.variantId()));
        }
        if (query.condition() != null) {
            condition = condition.and(LISTING.CONDITION_CODE.eq(query.condition().name()));
        }
        // A buyer filtering on price means the price they would pay.
        if (query.minPrice() != null) {
            condition = condition.and(ListingConditions.EFFECTIVE_PRICE.ge(query.minPrice()));
        }
        if (query.maxPrice() != null) {
            condition = condition.and(ListingConditions.EFFECTIVE_PRICE.le(query.maxPrice()));
        }
        if (Boolean.TRUE.equals(query.onSale())) {
            condition = condition.and(LISTING_SALE_PRICE.LISTING_ID.isNotNull());
        } else if (Boolean.FALSE.equals(query.onSale())) {
            condition = condition.and(LISTING_SALE_PRICE.LISTING_ID.isNull());
        }
        if (query.saleId() != null) {
            condition = condition.and(LISTING_SALE_PRICE.SALE_ID.eq(query.saleId()));
        }
        if (query.minDiscount() != null) {
            // discount / price >= minDiscount / 100, kept free of division.
            condition = condition.and(LISTING_SALE_PRICE.DISCOUNT_AMOUNT.mul(DSL.inline(100))
                    .ge(LISTING.PRICE.mul(query.minDiscount())));
        }
        if (query.productIds() != null) {
            condition = condition.and(LISTING.CATALOG_VARIANT_ID.in(
                    DSL.select(CATALOG_VARIANT.ID).from(CATALOG_VARIANT)
                            .where(CATALOG_VARIANT.CATALOG_PRODUCT_ID.in(query.productIds()))));
        }
        if (query.productId() != null || query.gameId() != null) {
            // A semi-join, so the page and the count share this condition unchanged.
            Condition card = DSL.noCondition();
            if (query.productId() != null) {
                card = card.and(CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(query.productId()));
            }
            if (query.gameId() != null) {
                card = card.and(CATALOG_PRODUCT.GAME_ID.eq(query.gameId()));
            }
            condition = condition.and(LISTING.CATALOG_VARIANT_ID.in(
                    DSL.select(CATALOG_VARIANT.ID)
                            .from(CATALOG_VARIANT)
                            .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                            .where(card)));
        }
        return condition;
    }

    /** Price sorts use the price a buyer pays now, so a card on sale sorts at its sale price. */
    private static List<SortField<?>> order(PublicListingQuery.Sort sort) {
        return switch (sort) {
            case PRICE_ASC -> List.of(ListingConditions.EFFECTIVE_PRICE.asc(), LISTING.ID.asc());
            case PRICE_DESC -> List.of(ListingConditions.EFFECTIVE_PRICE.desc(), LISTING.ID.desc());
            case NEWEST -> List.of(LISTING.PUBLISHED_AT.desc().nullsLast(), LISTING.ID.desc());
            case DISCOUNT_DESC -> List.of(DISCOUNT_SHARE.desc(), ListingConditions.EFFECTIVE_PRICE.asc(),
                    LISTING.ID.asc());
            case ENDING_SOON -> List.of(LISTING_SALE_PRICE.ENDS_AT.asc().nullsLast(),
                    ListingConditions.EFFECTIVE_PRICE.asc(), LISTING.ID.asc());
        };
    }

    private static PublicListing toPublic(Record r) {
        return new PublicListing(
                ListingRepository.toListing(r.into(LISTING)),
                r.get(USER_ACCOUNT.USERNAME),
                r.get(USER_ACCOUNT.DISPLAY_NAME),
                r.get(USER_ACCOUNT.AVATAR_URL),
                SellerStatus.VERIFIED.name().equals(r.get(SELLER_PROFILE.STATUS)),
                r.get(SELLER_RATING_SUMMARY.RATING_COUNT) == null || r.get(SELLER_RATING_SUMMARY.RATING_COUNT) == 0
                        ? null : r.get(SELLER_RATING_SUMMARY.RATING_AVG),
                r.get(SELLER_RATING_SUMMARY.RATING_COUNT) == null ? 0 : r.get(SELLER_RATING_SUMMARY.RATING_COUNT),
                0, new Delivery(null, null, false, 0));
    }
}
