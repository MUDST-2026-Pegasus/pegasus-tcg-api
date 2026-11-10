package com.pegasus.pegasustcgapi.repository.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.pricing.MarketKey;
import com.pegasus.pegasustcgapi.model.pricing.PricingMode;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code listing}, and loads one listing by seller or under a row lock.
 *
 * <p>Nothing here writes {@code quantity_*}. Those belong to the trigger on
 * {@code listing_unit}, which also flips ACTIVE and SOLD_OUT as stock runs out
 * and comes back.
 *
 * <p>Lock order, everywhere in this module: a listing's cards first, then the
 * listing. A method here that locks a listing row is only for work that touches
 * that row alone. Work that also changes cards locks them first through
 * {@link ListingUnitRepository}, and lets the trigger take the listing.
 *
 * <p>Buyer pages live in {@link PublicListingQueryRepository}; the seller's own
 * dashboard page lives in {@link SellerListingQueryRepository}; the conditions they
 * share are in {@link ListingConditions}.
 */
@Repository
public class ListingRepository {

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

    private static List<String> names(Collection<ListingStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }

    public static Listing toListing(ListingRecord r) {
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
                r.getUpdatedAt(),
                r.getBlockedAt() == null ? null : new Listing.Block(
                        r.getBlockedReason(),
                        r.getBlockedBy(),
                        r.getBlockedAt(),
                        r.getBlockedFromStatus() == null ? null : ListingStatus.valueOf(r.getBlockedFromStatus())));
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

    /**
     * @param search matched, case-insensitively and anywhere in the text, against the
     *               card's name, local name and number, the printing's SKU and the
     *               seller's own lot label; null for no search
     */
    public record SellerListingQuery(
            long sellerProfileId,
            ListingStatus status,
            Long variantId,
            CardCondition condition,
            String search,
            Sort sort,
            int limit,
            int offset) {

        /** Most recently changed first is what the dashboard opens on. */
        public enum Sort {
            UPDATED,
            NEWEST,
            PRICE_ASC,
            PRICE_DESC,
            /** Fewest cards left first — where a seller looks for what needs restocking. */
            STOCK_ASC,
            STOCK_DESC,
            NAME
        }
    }

    /**
     * @param sellerUserId narrows the market to one storefront
     * @param minPrice     on the price a buyer pays now, sale included; so is maxPrice
     * @param onSale       true: only cards on sale; false: only cards off sale; null: both
     * @param saleId       only cards priced by this sale
     * @param minDiscount  only cards at least this many percent off
     * @param now          the instant a sale row has to still run at
     */
    public record PublicListingQuery(
            Long sellerUserId,
            Long variantId,
            Long productId,
            Short gameId,
            CardCondition condition,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean onSale,
            Long saleId,
            BigDecimal minDiscount,
            Sort sort,
            int limit,
            int offset,
            OffsetDateTime now) {

        public PublicListingQuery {
            if (now == null) {
                now = OffsetDateTime.now();
            }
        }

        /** No sale filter, at the current instant. */
        public PublicListingQuery(Long sellerUserId, Long variantId, Long productId, Short gameId,
                CardCondition condition, BigDecimal minPrice, BigDecimal maxPrice, Sort sort, int limit, int offset) {
            this(sellerUserId, variantId, productId, gameId, condition, minPrice, maxPrice, null, null, null,
                    sort, limit, offset, null);
        }

        /** Cheapest first is what a buyer comparing sellers of one card wants. */
        public enum Sort {
            PRICE_ASC,
            PRICE_DESC,
            NEWEST,
            /** Largest share off first; cards off sale last. */
            DISCOUNT_DESC,
            /** The sale that ends first first; cards off sale last. */
            ENDING_SOON
        }
    }

    /** A listing with the name buyers see it under — the seller's own profile, there is no shop name. */
    public record PublicListing(
            Listing listing,
            String sellerUsername,
            String sellerDisplayName,
            String sellerAvatarUrl,
            boolean verified,
            BigDecimal ratingAvg,
            int ratingCount,
            int salesCount,
            Delivery delivery) {
    }

    public record Delivery(Short fastestEstDaysMin, Short fastestEstDaysMax,
                           boolean freeShipping, int optionCount) {}

    public record Offer(
            Listing listing,
            SellerStatus sellerStatus,
            boolean sellerOnVacation,
            boolean sellerAccountActive,
            String sellerDisplayName,
            String sellerUsername) {

        /** Whether a buyer can take it now: exactly what the reservation query accepts, plus at least one card. */
        public boolean purchasable() {
            return listing.status() == ListingStatus.ACTIVE
                    && listing.quantityAvailable() > 0
                    && sellerStatus == SellerStatus.VERIFIED
                    && !sellerOnVacation
                    && sellerAccountActive;
        }
    }
}
