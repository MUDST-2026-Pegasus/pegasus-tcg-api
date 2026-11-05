package com.pegasus.pegasustcgapi.repository.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSaleListing.PRICE_SALE_LISTING;

import com.pegasus.pegasustcgapi.jooq.tables.records.PriceSaleRecord;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code price_sale} and {@code price_sale_listing} [TCG-676]. */
@Repository
public class PriceSaleRepository {

    private final DSLContext dsl;

    public PriceSaleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** What a new sale is created with; counters, pause, early end and version start empty. */
    public record NewPriceSale(
            String name,
            String slug,
            OwnerType ownerType,
            Long sellerProfileId,
            SaleType saleType,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscountPerUnit,
            ScopeType scopeType,
            List<Long> scopeIds,
            List<CardCondition> conditions,
            BigDecimal minUnitPrice,
            Integer perListingQuota,
            Integer perBuyerLimit,
            BigDecimal budgetCap,
            String bannerImageUrl,
            boolean featuredOnHome,
            Integer homeSort,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Long createdBy) {
    }

    /** Throws {@code DuplicateKeyException} when the slug is taken. */
    public long insert(NewPriceSale s) {
        PriceSaleRecord r = apply(dsl.newRecord(PRICE_SALE), s);
        r.setCreatedBy(s.createdBy());
        return dsl.insertInto(PRICE_SALE)
                .set(r)
                .returningResult(PRICE_SALE.ID)
                .fetchSingle(PRICE_SALE.ID);
    }

    /**
     * Rewrites every editable column and bumps {@code version}. Owner, counters,
     * pause, early end and creator are not touched. Throws {@code DuplicateKeyException}
     * on a taken slug.
     */
    public void update(long id, NewPriceSale s) {
        PriceSaleRecord r = apply(dsl.newRecord(PRICE_SALE), s);
        r.reset(PRICE_SALE.OWNER_TYPE);
        r.reset(PRICE_SALE.SELLER_PROFILE_ID);
        // A bigger budget, or none, gives a campaign that ran short its money back [TCG-680].
        var cap = DSL.val(s.budgetCap(), PRICE_SALE.BUDGET_CAP);
        dsl.update(PRICE_SALE)
                .set(r)
                .set(PRICE_SALE.VERSION, PRICE_SALE.VERSION.plus(1))
                .set(PRICE_SALE.BUDGET_EXHAUSTED_AT, DSL.when(cap.isNull().or(cap.gt(PRICE_SALE.BUDGET_CAP)),
                        DSL.castNull(PRICE_SALE.BUDGET_EXHAUSTED_AT)).otherwise(PRICE_SALE.BUDGET_EXHAUSTED_AT))
                .where(PRICE_SALE.ID.eq(id))
                .execute();
    }

    /** Only for a sale no order has used; the order_item FK refuses the rest. */
    public boolean delete(long id) {
        return dsl.deleteFrom(PRICE_SALE).where(PRICE_SALE.ID.eq(id)).execute() > 0;
    }

    /** The row locked until the transaction ends, so an edit cannot race another. */
    public Optional<PriceSale> lockById(long id) {
        return dsl.selectFrom(PRICE_SALE)
                .where(PRICE_SALE.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(PriceSaleRepository::toSale);
    }

    /** Whether any order line was ever sold at this sale's price. */
    public boolean hasOrders(long id) {
        return dsl.fetchExists(ORDER_ITEM, ORDER_ITEM.SALE_ID.eq(id));
    }

    public boolean slugExists(String slug) {
        return dsl.fetchExists(PRICE_SALE, PRICE_SALE.SLUG.eq(slug));
    }

    public int nextHomeSort() {
        Integer last = dsl.select(DSL.max(PRICE_SALE.HOME_SORT))
                .from(PRICE_SALE)
                .where(PRICE_SALE.FEATURED_ON_HOME.isTrue())
                .fetchOne(0, Integer.class);
        return last == null ? 0 : last + 1;
    }

    /**
     * Makes {@code orderedIds} the whole home page: position i gets
     * {@code home_sort = i}, every other featured sale is taken off.
     */
    public void replaceHomeOrder(List<Long> orderedIds) {
        dsl.update(PRICE_SALE)
                .set(PRICE_SALE.FEATURED_ON_HOME, false)
                .setNull(PRICE_SALE.HOME_SORT)
                .where(PRICE_SALE.FEATURED_ON_HOME.isTrue())
                .and(PRICE_SALE.ID.notIn(orderedIds))
                .execute();
        for (int i = 0; i < orderedIds.size(); i++) {
            dsl.update(PRICE_SALE)
                    .set(PRICE_SALE.FEATURED_ON_HOME, true)
                    .set(PRICE_SALE.HOME_SORT, i)
                    .where(PRICE_SALE.ID.eq(orderedIds.get(i)))
                    .execute();
        }
    }

    /** Ids of the sales on the home page, in display order. */
    public List<Long> findHomeOrder() {
        return dsl.select(PRICE_SALE.ID)
                .from(PRICE_SALE)
                .where(PRICE_SALE.FEATURED_ON_HOME.isTrue())
                .orderBy(PRICE_SALE.HOME_SORT.asc().nullsLast(), PRICE_SALE.ID.asc())
                .fetch(PRICE_SALE.ID);
    }

    private static PriceSaleRecord apply(PriceSaleRecord r, NewPriceSale s) {
        r.setName(s.name());
        r.setSlug(s.slug());
        r.setOwnerType(s.ownerType().name());
        r.setSellerProfileId(s.sellerProfileId());
        r.setSaleType(s.saleType().name());
        r.setDiscountType(s.discountType().name());
        r.setDiscountValue(s.discountValue());
        r.setMaxDiscountPerUnit(s.maxDiscountPerUnit());
        r.setScopeType(s.scopeType().name());
        r.setScopeIds(s.scopeIds().toArray(Long[]::new));
        r.setConditions(s.conditions().stream().map(Enum::name).toArray(String[]::new));
        r.setMinUnitPrice(s.minUnitPrice());
        r.setPerListingQuota(s.perListingQuota());
        r.setPerBuyerLimit(s.perBuyerLimit());
        r.setBudgetCap(s.budgetCap());
        r.setBannerImageUrl(s.bannerImageUrl());
        r.setFeaturedOnHome(s.featuredOnHome());
        r.setHomeSort(s.homeSort());
        r.setStartsAt(s.startsAt());
        r.setEndsAt(s.endsAt());
        return r;
    }

    public Optional<PriceSale> findById(long id) {
        return dsl.selectFrom(PRICE_SALE)
                .where(PRICE_SALE.ID.eq(id))
                .fetchOptional()
                .map(PriceSaleRepository::toSale);
    }

    /**
     * Sales running at {@code now} by their dates and pause. Budget and quota are
     * left to {@code SalePriceResolver}, which decides per listing anyway.
     */
    public List<PriceSale> findRunning(OffsetDateTime now) {
        return dsl.selectFrom(PRICE_SALE)
                .where(notOver(now))
                .and(PRICE_SALE.STARTS_AT.le(now))
                .and(PRICE_SALE.PAUSED_AT.isNull())
                .orderBy(PRICE_SALE.ID)
                .fetch(PriceSaleRepository::toSale);
    }

    /**
     * Sales that are SCHEDULED or ACTIVE at {@code now}: not over, not paused, budget
     * left. Every listing they cover has its price locked against going up.
     */
    public List<PriceSale> findPriceLocking(OffsetDateTime now) {
        return dsl.selectFrom(PRICE_SALE)
                .where(notOver(now))
                .and(PRICE_SALE.PAUSED_AT.isNull())
                .and(PRICE_SALE.BUDGET_CAP.isNull().or(PRICE_SALE.SUBSIDY_RESERVED.lt(PRICE_SALE.BUDGET_CAP)
                        .and(PRICE_SALE.BUDGET_EXHAUSTED_AT.isNull())))
                .orderBy(PRICE_SALE.ID)
                .fetch(PriceSaleRepository::toSale);
    }

    public void pause(long id, long pausedBy, String reason, OffsetDateTime at) {
        dsl.update(PRICE_SALE)
                .set(PRICE_SALE.PAUSED_AT, at)
                .set(PRICE_SALE.PAUSED_BY, pausedBy)
                .set(PRICE_SALE.PAUSE_REASON, reason)
                .where(PRICE_SALE.ID.eq(id))
                .execute();
    }

    public void resume(long id) {
        dsl.update(PRICE_SALE)
                .setNull(PRICE_SALE.PAUSED_AT)
                .setNull(PRICE_SALE.PAUSED_BY)
                .setNull(PRICE_SALE.PAUSE_REASON)
                .where(PRICE_SALE.ID.eq(id))
                .execute();
    }

    /** Ends the sale at {@code at}, keeping the planned end, and takes it off the home page. */
    public void endAt(long id, OffsetDateTime at) {
        dsl.update(PRICE_SALE)
                .set(PRICE_SALE.ENDED_AT, at)
                .set(PRICE_SALE.FEATURED_ON_HOME, false)
                .setNull(PRICE_SALE.HOME_SORT)
                .set(PRICE_SALE.VERSION, PRICE_SALE.VERSION.plus(1))
                .where(PRICE_SALE.ID.eq(id))
                .execute();
    }

    /**
     * Puts a listing into a LISTINGS sale, or sets its flash quota.
     *
     * @param salePrice the listing's price under FIXED_PRICE; null otherwise
     * @param quota     overrides the sale's per-listing quota; null keeps the sale's
     */
    public void putListing(long saleId, long listingId, BigDecimal salePrice, Integer quota) {
        dsl.insertInto(PRICE_SALE_LISTING)
                .set(PRICE_SALE_LISTING.SALE_ID, saleId)
                .set(PRICE_SALE_LISTING.LISTING_ID, listingId)
                .set(PRICE_SALE_LISTING.SALE_PRICE, salePrice)
                .set(PRICE_SALE_LISTING.QUOTA, quota)
                .onConflict(PRICE_SALE_LISTING.SALE_ID, PRICE_SALE_LISTING.LISTING_ID)
                .doUpdate()
                .set(PRICE_SALE_LISTING.SALE_PRICE, salePrice)
                .set(PRICE_SALE_LISTING.QUOTA, quota)
                .execute();
    }

    /** One sale per (sale id, listing id) key; listings with no row are absent. */
    public record SaleListingKey(long saleId, long listingId) {
    }

    public Map<SaleListingKey, SaleListing> findListings(Collection<Long> saleIds, Collection<Long> listingIds) {
        if (saleIds.isEmpty() || listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(PRICE_SALE_LISTING)
                .where(PRICE_SALE_LISTING.SALE_ID.in(saleIds))
                .and(PRICE_SALE_LISTING.LISTING_ID.in(listingIds))
                .fetchMap(
                        r -> new SaleListingKey(r.getSaleId(), r.getListingId()),
                        r -> new SaleListing(r.getSalePrice(), r.getQuota(), r.getSoldCount()));
    }

    /** Not past its planned end and not ended early. */
    private static Condition notOver(OffsetDateTime now) {
        return PRICE_SALE.ENDS_AT.gt(now)
                .and(PRICE_SALE.ENDED_AT.isNull().or(PRICE_SALE.ENDED_AT.gt(now)));
    }

    static PriceSale toSale(PriceSaleRecord r) {
        return new PriceSale(
                r.getId(),
                r.getName(),
                r.getSlug(),
                OwnerType.valueOf(r.getOwnerType()),
                r.getSellerProfileId(),
                SaleType.valueOf(r.getSaleType()),
                DiscountType.valueOf(r.getDiscountType()),
                r.getDiscountValue(),
                r.getMaxDiscountPerUnit(),
                ScopeType.valueOf(r.getScopeType()),
                List.of(r.getScopeIds()),
                Arrays.stream(r.getConditions()).map(CardCondition::valueOf).toList(),
                r.getMinUnitPrice(),
                r.getPerListingQuota(),
                r.getPerBuyerLimit(),
                r.getBudgetCap(),
                r.getSubsidyReserved(),
                r.getBannerImageUrl(),
                r.getFeaturedOnHome(),
                r.getHomeSort(),
                r.getStartsAt(),
                r.getEndsAt(),
                r.getPausedAt(),
                r.getPausedBy(),
                r.getPauseReason(),
                r.getEndedAt(),
                r.getCreatedBy(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getBudgetExhaustedAt());
    }
}
