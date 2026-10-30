package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionClaim.PROMOTION_CLAIM;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;

import com.pegasus.pegasustcgapi.jooq.tables.records.PromotionRecord;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
