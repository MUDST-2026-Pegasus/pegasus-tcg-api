package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionClaim.PROMOTION_CLAIM;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;

import com.pegasus.pegasustcgapi.jooq.tables.records.PromotionRecord;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code promotion} — Pegasus and shop discount codes [TCG-566]. */
@Repository
public class PromotionRepository {

    private final DSLContext dsl;

    public PromotionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** What a new code is created with; counters, pause and version start empty. */
    public record NewPromotion(
            String code,
            String name,
            String description,
            OwnerType ownerType,
            Long sellerProfileId,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minSpend,
            ScopeType scopeType,
            List<Long> scopeIds,
            Visibility visibility,
            boolean newBuyerOnly,
            Integer usageLimitTotal,
            int usageLimitPerUser,
            boolean claimable,
            boolean claimRequired,
            Integer claimLimit,
            boolean featuredOnHome,
            Integer homeSort,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Long createdBy) {
    }

    /** Throws {@code DuplicateKeyException} when the code is taken, by any shop or by Pegasus. */
    public long insert(NewPromotion p) {
        PromotionRecord r = apply(dsl.newRecord(PROMOTION), p);
        r.setCreatedBy(p.createdBy());
        return dsl.insertInto(PROMOTION)
                .set(r)
                .returningResult(PROMOTION.ID)
                .fetchSingle(PROMOTION.ID);
    }

    /**
     * Rewrites every editable column and bumps {@code version}. Counters, pause and
     * owner are not touched. Throws {@code DuplicateKeyException} on a taken code.
     */
    public void update(long id, NewPromotion p) {
        dsl.update(PROMOTION)
                .set(apply(dsl.newRecord(PROMOTION), p))
                .set(PROMOTION.VERSION, PROMOTION.VERSION.plus(1))
                .set(PROMOTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION.ID.eq(id))
                .execute();
    }

    /** Only for a code nobody has used or claimed; the redemption FK refuses the rest. */
    public boolean delete(long id) {
        return dsl.deleteFrom(PROMOTION).where(PROMOTION.ID.eq(id)).execute() > 0;
    }

    /** The row locked until the transaction ends, so a checkout cannot take a use mid-edit. */
    public Optional<Promotion> lockById(long id) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(PromotionRepository::toPromotion);
    }

    /** Like {@link #lockById}, but only a code of this shop; another's is never locked. */
    public Optional<Promotion> lockShopCode(long id, long sellerProfileId) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.ID.eq(id))
                .and(PROMOTION.OWNER_TYPE.eq(OwnerType.SELLER.name()))
                .and(PROMOTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .forUpdate()
                .fetchOptional()
                .map(PromotionRepository::toPromotion);
    }

    /** Whether any order ever held this code, even one that released it again. */
    public boolean hasRedemptions(long id) {
        return dsl.fetchExists(PROMOTION_REDEMPTION, PROMOTION_REDEMPTION.PROMOTION_ID.eq(id));
    }

    public boolean hasClaims(long id) {
        return dsl.fetchExists(PROMOTION_CLAIM, PROMOTION_CLAIM.PROMOTION_ID.eq(id));
    }

    public void pause(long id, long pausedBy, String reason, OffsetDateTime at) {
        dsl.update(PROMOTION)
                .set(PROMOTION.PAUSED_AT, at)
                .set(PROMOTION.PAUSED_BY, pausedBy)
                .set(PROMOTION.PAUSE_REASON, reason)
                .set(PROMOTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION.ID.eq(id))
                .execute();
    }

    /** Ends the code at {@code at} and takes it off the home page; past orders keep their discount. */
    public void endAt(long id, OffsetDateTime at) {
        dsl.update(PROMOTION)
                .set(PROMOTION.ENDS_AT, at)
                .set(PROMOTION.FEATURED_ON_HOME, false)
                .setNull(PROMOTION.HOME_SORT)
                .set(PROMOTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION.ID.eq(id))
                .execute();
    }

    public void resume(long id) {
        dsl.update(PROMOTION)
                .setNull(PROMOTION.PAUSED_AT)
                .setNull(PROMOTION.PAUSED_BY)
                .setNull(PROMOTION.PAUSE_REASON)
                .set(PROMOTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION.ID.eq(id))
                .execute();
    }

    /** Whether every id names a game (GAME) or a category (CATEGORY); ALL has nothing to check. */
    public boolean scopeTargetsExist(ScopeType scope, List<Long> ids) {
        long distinct = ids.stream().distinct().count();
        return switch (scope) {
            case ALL -> ids.isEmpty();
            case GAME -> dsl.fetchCount(GAME, GAME.ID.cast(SQLDataType.BIGINT).in(ids)) == distinct;
            case CATEGORY -> dsl.fetchCount(CATALOG_CATEGORY,
                    CATALOG_CATEGORY.ID.cast(SQLDataType.BIGINT).in(ids)) == distinct;
        };
    }

    /** The game and category a card counts under for a GAME or CATEGORY code. */
    public record VariantScope(long gameId, long categoryId) {
    }

    public Map<Long, VariantScope> scopeOfVariants(Collection<Long> variantIds) {
        return dsl.select(CATALOG_VARIANT.ID, CATALOG_PRODUCT.GAME_ID, CATALOG_PRODUCT.CATEGORY_ID)
                .from(CATALOG_VARIANT)
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(CATALOG_VARIANT.ID.in(variantIds))
                .fetchMap(r -> r.value1(), r -> new VariantScope(r.value2(), r.value3()));
    }

    /** Whether the buyer has ever paid for an order; a new-buyer code is for those who have not. */
    public boolean hasPaidOrder(long buyerId) {
        return dsl.fetchExists(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyerId).and(SALES_ORDER.PAID_AT.isNotNull()));
    }

    /** Live PUBLIC codes: Pegasus's and those of the given shops, for the code picker. */
    public List<Promotion> findPublicLive(Collection<Long> sellerProfileIds, OffsetDateTime now) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.VISIBILITY.eq(Visibility.PUBLIC.name()))
                .and(PROMOTION.OWNER_TYPE.eq(OwnerType.PLATFORM.name())
                        .or(PROMOTION.SELLER_PROFILE_ID.in(sellerProfileIds)))
                .and(PROMOTION.STARTS_AT.le(now))
                .and(PROMOTION.ENDS_AT.gt(now))
                .and(PROMOTION.PAUSED_AT.isNull())
                .orderBy(PROMOTION.ENDS_AT.asc(), PROMOTION.ID.asc())
                .fetch(PromotionRepository::toPromotion);
    }

    /** Codes this user has collected that have not ended yet, whatever their visibility. */
    public List<Promotion> findClaimedLive(long userId, OffsetDateTime now) {
        return dsl.select(PROMOTION.fields())
                .from(PROMOTION)
                .join(PROMOTION_CLAIM).on(PROMOTION_CLAIM.PROMOTION_ID.eq(PROMOTION.ID))
                .where(PROMOTION_CLAIM.USER_ID.eq(userId))
                .and(PROMOTION.ENDS_AT.gt(now))
                .orderBy(PROMOTION.ENDS_AT.asc(), PROMOTION.ID.asc())
                .fetch(r -> toPromotion(r.into(PROMOTION)));
    }

    /** A code this user has collected, and when. */
    public record ClaimedPromotion(Promotion promotion, OffsetDateTime claimedAt) {
    }

    /** Every code this user has collected, ended or not, newest claim first. */
    public List<ClaimedPromotion> findClaimedBy(long userId) {
        return dsl.select(PROMOTION.fields())
                .select(PROMOTION_CLAIM.CLAIMED_AT)
                .from(PROMOTION)
                .join(PROMOTION_CLAIM).on(PROMOTION_CLAIM.PROMOTION_ID.eq(PROMOTION.ID))
                .where(PROMOTION_CLAIM.USER_ID.eq(userId))
                .orderBy(PROMOTION_CLAIM.CLAIMED_AT.desc(), PROMOTION.ID.desc())
                .fetch(r -> new ClaimedPromotion(toPromotion(r.into(PROMOTION)), r.get(PROMOTION_CLAIM.CLAIMED_AT)));
    }

    public List<Promotion> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.ID.in(ids))
                .fetch(PromotionRepository::toPromotion);
    }

    /** The home-page strip: live Pegasus codes ticked for it, in the admin's order. */
    public List<Promotion> findFeaturedLive(OffsetDateTime now, int limit) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.FEATURED_ON_HOME.isTrue())
                .and(PROMOTION.OWNER_TYPE.eq(OwnerType.PLATFORM.name()))
                .and(live(now))
                .orderBy(PROMOTION.HOME_SORT.asc().nullsLast(), PROMOTION.ID.asc())
                .limit(limit)
                .fetch(PromotionRepository::toPromotion);
    }

    /** A store page's strip: the shop's live PUBLIC codes that can be collected. */
    public List<Promotion> findShopClaimableLive(long sellerProfileId, OffsetDateTime now) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.OWNER_TYPE.eq(OwnerType.SELLER.name()))
                .and(PROMOTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(PROMOTION.VISIBILITY.eq(Visibility.PUBLIC.name()))
                .and(PROMOTION.CLAIMABLE.isTrue())
                .and(live(now))
                .orderBy(PROMOTION.ENDS_AT.asc(), PROMOTION.ID.asc())
                .fetch(PromotionRepository::toPromotion);
    }

    /** Names of a GAME or CATEGORY code's targets, for its terms; ALL has none. */
    public Map<Long, String> scopeNames(ScopeType scope, List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return switch (scope) {
            case ALL -> Map.of();
            case GAME -> dsl.select(GAME.ID.cast(SQLDataType.BIGINT), GAME.NAME)
                    .from(GAME)
                    .where(GAME.ID.cast(SQLDataType.BIGINT).in(ids))
                    .fetchMap(r -> r.value1(), r -> r.value2());
            case CATEGORY -> dsl.select(CATALOG_CATEGORY.ID.cast(SQLDataType.BIGINT), CATALOG_CATEGORY.NAME)
                    .from(CATALOG_CATEGORY)
                    .where(CATALOG_CATEGORY.ID.cast(SQLDataType.BIGINT).in(ids))
                    .fetchMap(r -> r.value1(), r -> r.value2());
        };
    }

    /** Same test as {@link PromotionStatus#ACTIVE}, in SQL. */
    private static Condition live(OffsetDateTime now) {
        return PROMOTION.STARTS_AT.le(now)
                .and(PROMOTION.ENDS_AT.gt(now))
                .and(PROMOTION.PAUSED_AT.isNull())
                .and(PROMOTION.USAGE_LIMIT_TOTAL.isNull().or(PROMOTION.USED_COUNT.lt(PROMOTION.USAGE_LIMIT_TOTAL)));
    }

    /** One past the last featured code, so a newly featured code goes to the end of the strip. */
    public int nextHomeSort() {
        Integer last = dsl.select(DSL.max(PROMOTION.HOME_SORT))
                .from(PROMOTION)
                .where(PROMOTION.FEATURED_ON_HOME.isTrue())
                .fetchOne(0, Integer.class);
        return last == null ? 0 : last + 1;
    }

    /**
     * Makes {@code orderedIds} the whole home-page strip: position i gets
     * {@code home_sort = i}, every other featured code is taken off.
     */
    public void replaceHomeOrder(List<Long> orderedIds) {
        dsl.update(PROMOTION)
                .set(PROMOTION.FEATURED_ON_HOME, false)
                .setNull(PROMOTION.HOME_SORT)
                .set(PROMOTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION.FEATURED_ON_HOME.isTrue())
                .and(PROMOTION.ID.notIn(orderedIds))
                .execute();
        for (int i = 0; i < orderedIds.size(); i++) {
            dsl.update(PROMOTION)
                    .set(PROMOTION.FEATURED_ON_HOME, true)
                    .set(PROMOTION.HOME_SORT, i)
                    .set(PROMOTION.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(PROMOTION.ID.eq(orderedIds.get(i)))
                    .execute();
        }
    }

    public int countFeatured() {
        return dsl.fetchCount(PROMOTION, PROMOTION.FEATURED_ON_HOME.isTrue());
    }

    /** Ids of the codes on the home-page strip, in display order. */
    public List<Long> findHomeOrder() {
        return dsl.select(PROMOTION.ID)
                .from(PROMOTION)
                .where(PROMOTION.FEATURED_ON_HOME.isTrue())
                .orderBy(PROMOTION.HOME_SORT.asc().nullsLast(), PROMOTION.ID.asc())
                .fetch(PROMOTION.ID);
    }

    public Optional<Promotion> findById(long id) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.ID.eq(id))
                .fetchOptional()
                .map(PromotionRepository::toPromotion);
    }

    /** Buyers type codes in any case; they are stored upper case. */
    public Optional<Promotion> findByCode(String code) {
        return dsl.selectFrom(PROMOTION)
                .where(PROMOTION.CODE.eq(normalize(code)))
                .fetchOptional()
                .map(PromotionRepository::toPromotion);
    }

    public boolean codeExists(String code) {
        return dsl.fetchExists(PROMOTION, PROMOTION.CODE.eq(normalize(code)));
    }

    /**
     * Takes one use for an order. False when every use is already held or spent,
     * decided in the same statement so two checkouts cannot both take the last one.
     */
    public boolean takeUse(long id) {
        return dsl.update(PROMOTION)
                .set(PROMOTION.USED_COUNT, PROMOTION.USED_COUNT.plus(1))
                .where(PROMOTION.ID.eq(id))
                .and(PROMOTION.USAGE_LIMIT_TOTAL.isNull()
                        .or(PROMOTION.USED_COUNT.lt(PROMOTION.USAGE_LIMIT_TOTAL)))
                .execute() > 0;
    }

    /** Gives a use back when an order times out or is cancelled before payment. */
    public boolean releaseUse(long id) {
        return dsl.update(PROMOTION)
                .set(PROMOTION.USED_COUNT, PROMOTION.USED_COUNT.minus(1))
                .where(PROMOTION.ID.eq(id))
                .and(PROMOTION.USED_COUNT.gt(0))
                .execute() > 0;
    }

    /** Counts one more collected copy; false once {@code claim_limit} copies are out. */
    public boolean takeClaimSlot(long id) {
        return dsl.update(PROMOTION)
                .set(PROMOTION.CLAIMED_COUNT, PROMOTION.CLAIMED_COUNT.plus(1))
                .where(PROMOTION.ID.eq(id))
                .and(PROMOTION.CLAIM_LIMIT.isNull()
                        .or(PROMOTION.CLAIMED_COUNT.lt(PROMOTION.CLAIM_LIMIT)))
                .execute() > 0;
    }

    private static PromotionRecord apply(PromotionRecord r, NewPromotion p) {
        r.setCode(normalize(p.code()));
        r.setName(p.name());
        r.setDescription(p.description());
        r.setOwnerType(p.ownerType().name());
        r.setSellerProfileId(p.sellerProfileId());
        r.setDiscountType(p.discountType().name());
        r.setDiscountValue(p.discountValue());
        r.setMaxDiscount(p.maxDiscount());
        r.setMinSpend(p.minSpend());
        r.setScopeType(p.scopeType().name());
        r.setScopeIds(p.scopeIds().toArray(Long[]::new));
        r.setVisibility(p.visibility().name());
        r.setNewBuyerOnly(p.newBuyerOnly());
        r.setUsageLimitTotal(p.usageLimitTotal());
        r.setUsageLimitPerUser(p.usageLimitPerUser());
        r.setClaimable(p.claimable());
        r.setClaimRequired(p.claimRequired());
        r.setClaimLimit(p.claimLimit());
        r.setFeaturedOnHome(p.featuredOnHome());
        r.setHomeSort(p.homeSort());
        r.setStartsAt(p.startsAt());
        r.setEndsAt(p.endsAt());
        return r;
    }

    private static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    static Promotion toPromotion(PromotionRecord r) {
        return new Promotion(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getDescription(),
                OwnerType.valueOf(r.getOwnerType()),
                r.getSellerProfileId(),
                DiscountType.valueOf(r.getDiscountType()),
                r.getDiscountValue(),
                r.getMaxDiscount(),
                r.getMinSpend(),
                ScopeType.valueOf(r.getScopeType()),
                List.of(r.getScopeIds()),
                Visibility.valueOf(r.getVisibility()),
                r.getNewBuyerOnly(),
                r.getUsageLimitTotal(),
                r.getUsageLimitPerUser(),
                r.getUsedCount(),
                r.getClaimable(),
                r.getClaimRequired(),
                r.getClaimLimit(),
                r.getClaimedCount(),
                r.getFeaturedOnHome(),
                r.getHomeSort(),
                r.getStartsAt(),
                r.getEndsAt(),
                r.getPausedAt(),
                r.getPausedBy(),
                r.getPauseReason(),
                r.getCreatedBy(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
