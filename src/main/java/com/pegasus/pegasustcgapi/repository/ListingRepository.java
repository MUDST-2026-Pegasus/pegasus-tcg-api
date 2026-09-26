package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.model.UserStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
 * Reads and writes {@code listing}.
 *
 * <p>Nothing here writes {@code quantity_*}. Those belong to the trigger on
 * {@code listing_unit}, which also flips ACTIVE and SOLD_OUT as stock runs out
 * and comes back.
 *
 * <p>Lock order, everywhere in this module: a listing's cards first, then the
 * listing. A method here that locks a listing row is only for work that touches
 * that row alone. Work that also changes cards locks them first through
 * {@link ListingUnitRepository}, and lets the trigger take the listing.
 */
@Repository
public class ListingRepository {

    // Aliased: listing, seller_profile and user_account each have a status and a
    // deleted_at, and a record carrying two columns of one name maps ambiguously.
    private static final Field<String> SELLER_STATUS = SELLER_PROFILE.STATUS.as("seller_status");
    private static final Field<UserStatus> ACCOUNT_STATUS = USER_ACCOUNT.STATUS.as("account_status");
    private static final Field<OffsetDateTime> ACCOUNT_DELETED_AT = USER_ACCOUNT.DELETED_AT.as("account_deleted_at");

    private final DSLContext dsl;

    public ListingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ---------- the seller's own ----------

    /** Scoped to the seller, so another seller's id reads as not found rather than forbidden. */
    public Optional<Listing> findOfSeller(long listingId, long sellerProfileId) {
        return dsl.selectFrom(LISTING)
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(LISTING.DELETED_AT.isNull())
                .fetchOptional()
                .map(ListingRepository::toListing);
    }

    /**
     * Same, holding the row to the end of the transaction, so the price read here
     * is the price that gets replaced — and written into the history as the old one.
     */
    public Optional<Listing> lockOfSeller(long listingId, long sellerProfileId) {
        return dsl.selectFrom(LISTING)
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(LISTING.DELETED_AT.isNull())
                .forNoKeyUpdate()
                .fetchOptional()
                .map(ListingRepository::toListing);
    }

    /** Newest change first, the order the dashboard index is built for. */
    public List<Listing> findSellerPage(SellerListingQuery query) {
        return dsl.selectFrom(LISTING)
                .where(sellerConditions(query))
                .orderBy(LISTING.UPDATED_AT.desc(), LISTING.ID.desc())
                .limit(query.limit())
                .offset(query.offset())
                .fetch(ListingRepository::toListing);
    }

    public long countSeller(SellerListingQuery query) {
        return dsl.fetchCount(LISTING, sellerConditions(query));
    }

    /**
     * The seller's other live listings of the same printing in the same condition.
     * Allowed on purpose — there is no unique index — and shown so the seller can
     * decide whether two of them should really be one.
     */
    public List<Listing> findSimilar(long sellerProfileId, MarketKey key, long exceptListingId) {
        return dsl.selectFrom(LISTING)
                .where(LISTING.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(LISTING.CATALOG_VARIANT_ID.eq(key.catalogVariantId()))
                .and(LISTING.CONDITION_CODE.eq(key.condition().name()))
                .and(LISTING.ID.ne(exceptListingId))
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.STATUS.ne(ListingStatus.DELISTED.name()))
                .orderBy(LISTING.ID)
                .fetch(ListingRepository::toListing);
    }

    /** Always starts as a DRAFT with no cards; the counts arrive with the cards. */
    public long insert(long sellerProfileId, MarketKey key, Details details, Pricing pricing) {
        return dsl.insertInto(LISTING)
                .set(LISTING.SELLER_PROFILE_ID, sellerProfileId)
                .set(LISTING.CATALOG_VARIANT_ID, key.catalogVariantId())
                .set(LISTING.CONDITION_CODE, key.condition().name())
                .set(LISTING.GRADING_COMPANY, details.gradingCompany())
                .set(LISTING.GRADE_VALUE, details.gradeValue())
                .set(LISTING.LOT_LABEL, details.lotLabel())
                .set(LISTING.PUBLIC_NOTE, details.publicNote())
                .set(LISTING.PRICE, pricing.price())
                .set(LISTING.PRICING_MODE, pricing.mode().name())
                .set(LISTING.AUTO_PRICE_OFFSET_PERCENT, pricing.offsetPercent())
                .set(LISTING.AUTO_PRICE_FLOOR, pricing.floor())
                .set(LISTING.AUTO_PRICE_CEILING, pricing.ceiling())
                .set(LISTING.STATUS, ListingStatus.DRAFT.name())
                .returningResult(LISTING.ID)
                .fetchSingle(LISTING.ID);
    }

    public boolean updateDetails(long listingId, Details details) {
        return dsl.update(LISTING)
                .set(LISTING.GRADING_COMPANY, details.gradingCompany())
                .set(LISTING.GRADE_VALUE, details.gradeValue())
                .set(LISTING.LOT_LABEL, details.lotLabel())
                .set(LISTING.PUBLIC_NOTE, details.publicNote())
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .execute() > 0;
    }

    /** @return false when the listing closed in the meantime */
    public boolean updatePricing(long listingId, Pricing pricing) {
        return dsl.update(LISTING)
                .set(LISTING.PRICE, pricing.price())
                .set(LISTING.PRICING_MODE, pricing.mode().name())
                .set(LISTING.AUTO_PRICE_OFFSET_PERCENT, pricing.offsetPercent())
                .set(LISTING.AUTO_PRICE_FLOOR, pricing.floor())
                .set(LISTING.AUTO_PRICE_CEILING, pricing.ceiling())
                .set(LISTING.VERSION, LISTING.VERSION.plus(1))
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.STATUS.notIn(ListingStatus.DELISTED.name(), ListingStatus.BLOCKED.name()))
                .execute() > 0;
    }

    /**
     * Puts a listing on sale — or straight into SOLD_OUT when nothing is left. The
     * row decides, not the caller, so a card taken in the meantime cannot leave an
     * empty listing ACTIVE.
     *
     * @return false when the listing was no longer in one of {@code from}
     */
    public boolean activate(long listingId, Collection<ListingStatus> from, OffsetDateTime at) {
        return dsl.update(LISTING)
                .set(LISTING.STATUS, DSL.when(LISTING.QUANTITY_AVAILABLE.gt(0), ListingStatus.ACTIVE.name())
                        .otherwise(ListingStatus.SOLD_OUT.name()))
                .set(LISTING.PUBLISHED_AT, DSL.coalesce(LISTING.PUBLISHED_AT, DSL.val(at)))
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.STATUS.in(names(from)))
                .execute() > 0;
    }

    /**
     * @param from every status the move is allowed from. A set rather than the one
     *             the caller read, since the trigger may have flipped ACTIVE and
     *             SOLD_OUT since.
     * @return false when the listing was no longer in one of them
     */
    public boolean changeStatus(long listingId, ListingStatus to, Collection<ListingStatus> from) {
        return dsl.update(LISTING)
                .set(LISTING.STATUS, to.name())
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.STATUS.in(names(from)))
                .execute() > 0;
    }

    /**
     * Gone from the seller's dashboard as well as the market. The row stays: order
     * lines and the stock ledger point at it.
     *
     * @return false when cards on it are held by an open order, or it was already gone
     */
    public boolean softDelete(long listingId, OffsetDateTime at) {
        return dsl.update(LISTING)
                .set(LISTING.DELETED_AT, at)
                .set(LISTING.STATUS, ListingStatus.DELISTED.name())
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.QUANTITY_RESERVED.eq(0))
                .execute() > 0;
    }

    // ---------- the market ----------

    public List<PublicListing> findPublicPage(PublicListingQuery query) {
        return publicSelect()
                .where(publicConditions(query))
                .orderBy(order(query.sort()))
                .limit(query.limit())
                .offset(query.offset())
                .fetch(ListingRepository::toPublic);
    }

    public long countPublic(PublicListingQuery query) {
        return dsl.selectCount()
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(publicConditions(query))
                .fetchSingle(0, long.class);
    }

    /** A page a buyer may open: on sale or sold out, from a seller who is open for business. */
    public Optional<PublicListing> findPublic(long listingId) {
        return publicSelect()
                .where(LISTING.ID.eq(listingId))
                .and(sellerOpenForBusiness())
                .and(LISTING.STATUS.in(ListingStatus.ACTIVE.name(), ListingStatus.SOLD_OUT.name()))
                .fetchOptional()
                .map(ListingRepository::toPublic);
    }

    // ---------- checkout ----------

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

        return dsl.select(fields)
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(LISTING.ID.in(listingIds))
                .and(LISTING.DELETED_AT.isNull())
                .fetch(r -> new Offer(
                        toListing(r.into(LISTING)),
                        SellerStatus.valueOf(r.get(SELLER_STATUS)),
                        r.get(SELLER_PROFILE.VACATION_MODE),
                        r.get(ACCOUNT_STATUS) == UserStatus.ACTIVE && r.get(ACCOUNT_DELETED_AT) == null));
    }

    // ---------- the nightly job ----------

    /** Every market that has at least one AUTO_MEDIAN listing to move. */
    public List<MarketKey> autoPricedMarkets(Collection<ListingStatus> statuses) {
        return dsl.selectDistinct(LISTING.CATALOG_VARIANT_ID, LISTING.CONDITION_CODE)
                .from(LISTING)
                .where(LISTING.PRICING_MODE.eq(PricingMode.AUTO_MEDIAN.name()))
                .and(LISTING.STATUS.in(names(statuses)))
                .and(LISTING.DELETED_AT.isNull())
                .orderBy(LISTING.CATALOG_VARIANT_ID, LISTING.CONDITION_CODE)
                .fetch(r -> new MarketKey(r.value1(), CardCondition.valueOf(r.value2())));
    }

    /**
     * The AUTO_MEDIAN listings of one market, locked in id order so that a seller
     * switching one to MANUAL at 03:00 either lands before the job reads it or
     * waits until the job is done with it. MANUAL listings are never selected.
     */
    public List<Listing> lockAutoPriced(MarketKey key, Collection<ListingStatus> statuses) {
        return dsl.selectFrom(LISTING)
                .where(LISTING.CATALOG_VARIANT_ID.eq(key.catalogVariantId()))
                .and(LISTING.CONDITION_CODE.eq(key.condition().name()))
                .and(LISTING.PRICING_MODE.eq(PricingMode.AUTO_MEDIAN.name()))
                .and(LISTING.STATUS.in(names(statuses)))
                .and(LISTING.DELETED_AT.isNull())
                .orderBy(LISTING.ID)
                .forNoKeyUpdate()
                .fetch(ListingRepository::toListing);
    }

    /** Refuses a listing that is no longer AUTO_MEDIAN, whatever the caller read earlier. */
    public boolean applyAutoPrice(long listingId, BigDecimal price, OffsetDateTime at) {
        return dsl.update(LISTING)
                .set(LISTING.PRICE, price)
                .set(LISTING.LAST_AUTO_PRICED_AT, at)
                .set(LISTING.VERSION, LISTING.VERSION.plus(1))
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.PRICING_MODE.eq(PricingMode.AUTO_MEDIAN.name()))
                .execute() > 0;
    }

    // ---------- helpers ----------

    private SelectOnConditionStep<Record> publicSelect() {
        List<SelectField<?>> fields = new ArrayList<>(List.of(LISTING.fields()));
        fields.add(USER_ACCOUNT.USERNAME);
        fields.add(USER_ACCOUNT.DISPLAY_NAME);
        fields.add(USER_ACCOUNT.AVATAR_URL);

        return dsl.select(fields)
                .from(LISTING)
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID));
    }

    /**
     * A seller buyers can see: verified, not on vacation, and an account that is
     * still active. Vacation hides every listing at once without touching their
     * statuses. {@link ProductMarketRepository} prices the catalogue by the same rule.
     */
    static Condition sellerOpenForBusiness() {
        return LISTING.DELETED_AT.isNull()
                .and(SELLER_PROFILE.STATUS.eq(SellerStatus.VERIFIED.name()))
                .and(SELLER_PROFILE.VACATION_MODE.isFalse())
                .and(USER_ACCOUNT.STATUS.eq(UserStatus.ACTIVE))
                .and(USER_ACCOUNT.DELETED_AT.isNull());
    }

    private static Condition publicConditions(PublicListingQuery query) {
        Condition condition = sellerOpenForBusiness()
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
        if (query.minPrice() != null) {
            condition = condition.and(LISTING.PRICE.ge(query.minPrice()));
        }
        if (query.maxPrice() != null) {
            condition = condition.and(LISTING.PRICE.le(query.maxPrice()));
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
        return condition;
    }

    private static List<SortField<?>> order(PublicListingQuery.Sort sort) {
        return switch (sort) {
            case PRICE_ASC -> List.of(LISTING.PRICE.asc(), LISTING.ID.asc());
            case PRICE_DESC -> List.of(LISTING.PRICE.desc(), LISTING.ID.desc());
            case NEWEST -> List.of(LISTING.PUBLISHED_AT.desc().nullsLast(), LISTING.ID.desc());
        };
    }

    private static List<String> names(Collection<ListingStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }

    private static PublicListing toPublic(Record r) {
        return new PublicListing(
                toListing(r.into(LISTING)),
                r.get(USER_ACCOUNT.USERNAME),
                r.get(USER_ACCOUNT.DISPLAY_NAME),
                r.get(USER_ACCOUNT.AVATAR_URL));
    }

    static Listing toListing(ListingRecord r) {
        return new Listing(
                r.getId(),
                r.getSellerProfileId(),
                r.getCatalogVariantId(),
                CardCondition.valueOf(r.getConditionCode()),
                r.getGradingCompany(),
                r.getGradeValue(),
                r.getPrice(),
                r.getCurrency(),
                PricingMode.valueOf(r.getPricingMode()),
                r.getAutoPriceOffsetPercent(),
                r.getAutoPriceFloor(),
                r.getAutoPriceCeiling(),
                r.getLastAutoPricedAt(),
                r.getQuantityTotal(),
                r.getQuantityReserved(),
                r.getQuantityAvailable(),
                ListingStatus.valueOf(r.getStatus()),
                r.getLotLabel(),
                r.getPublicNote(),
                r.getVersion(),
                r.getPublishedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    // ---------- shapes ----------

    /** What describes the group of cards, as opposed to what it costs. */
    public record Details(String gradingCompany, BigDecimal gradeValue, String lotLabel, String publicNote) {
    }

    /**
     * @param price         for AUTO_MEDIAN, the price until the nightly job first moves it
     * @param offsetPercent AUTO_MEDIAN only, e.g. −5 for five percent under the market
     */
    public record Pricing(
            PricingMode mode,
            BigDecimal price,
            BigDecimal offsetPercent,
            BigDecimal floor,
            BigDecimal ceiling) {
    }

    public record SellerListingQuery(
            long sellerProfileId,
            ListingStatus status,
            Long variantId,
            CardCondition condition,
            int limit,
            int offset) {
    }

    /** @param sellerUserId narrows the market to one storefront */
    public record PublicListingQuery(
            Long sellerUserId,
            Long variantId,
            Long productId,
            Short gameId,
            CardCondition condition,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Sort sort,
            int limit,
            int offset) {

        /** Cheapest first is what a buyer comparing sellers of one card wants. */
        public enum Sort {
            PRICE_ASC,
            PRICE_DESC,
            NEWEST
        }
    }

    /** A listing with the name buyers see it under — the seller's own profile, there is no shop name. */
    public record PublicListing(
            Listing listing,
            String sellerUsername,
            String sellerDisplayName,
            String sellerAvatarUrl) {
    }

    public record Offer(
            Listing listing,
            SellerStatus sellerStatus,
            boolean sellerOnVacation,
            boolean sellerAccountActive) {
    }
}
