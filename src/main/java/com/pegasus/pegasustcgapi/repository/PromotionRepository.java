package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;

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
        return dsl.insertInto(PROMOTION)
                .set(PROMOTION.CODE, normalize(p.code()))
                .set(PROMOTION.NAME, p.name())
                .set(PROMOTION.DESCRIPTION, p.description())
                .set(PROMOTION.OWNER_TYPE, p.ownerType().name())
                .set(PROMOTION.SELLER_PROFILE_ID, p.sellerProfileId())
                .set(PROMOTION.DISCOUNT_TYPE, p.discountType().name())
                .set(PROMOTION.DISCOUNT_VALUE, p.discountValue())
                .set(PROMOTION.MAX_DISCOUNT, p.maxDiscount())
                .set(PROMOTION.MIN_SPEND, p.minSpend())
                .set(PROMOTION.SCOPE_TYPE, p.scopeType().name())
                .set(PROMOTION.SCOPE_IDS, p.scopeIds().toArray(Long[]::new))
                .set(PROMOTION.VISIBILITY, p.visibility().name())
                .set(PROMOTION.NEW_BUYER_ONLY, p.newBuyerOnly())
                .set(PROMOTION.USAGE_LIMIT_TOTAL, p.usageLimitTotal())
                .set(PROMOTION.USAGE_LIMIT_PER_USER, p.usageLimitPerUser())
                .set(PROMOTION.CLAIMABLE, p.claimable())
                .set(PROMOTION.CLAIM_REQUIRED, p.claimRequired())
                .set(PROMOTION.CLAIM_LIMIT, p.claimLimit())
                .set(PROMOTION.FEATURED_ON_HOME, p.featuredOnHome())
                .set(PROMOTION.HOME_SORT, p.homeSort())
                .set(PROMOTION.STARTS_AT, p.startsAt())
                .set(PROMOTION.ENDS_AT, p.endsAt())
                .set(PROMOTION.CREATED_BY, p.createdBy())
                .returningResult(PROMOTION.ID)
                .fetchSingle(PROMOTION.ID);
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

    private static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    private static Promotion toPromotion(PromotionRecord r) {
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
