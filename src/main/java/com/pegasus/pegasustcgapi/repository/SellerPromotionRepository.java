package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.PausedBy;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.Redemption;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.SellerPromotion;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read side of a shop's own discount codes. Every query is scoped to one
 * {@code seller_profile_id}; a code of another shop or of Pegasus never comes back.
 * Writes stay in {@link PromotionRepository}.
 */
@Repository
public class SellerPromotionRepository {

    private final DSLContext dsl;

    public SellerPromotionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** What the code has taken off paid orders. */
    private static final Field<BigDecimal> DISCOUNT_TOTAL = DSL.field(DSL.select(
                    DSL.coalesce(DSL.sum(PROMOTION_REDEMPTION.AMOUNT), BigDecimal.ZERO))
            .from(PROMOTION_REDEMPTION)
            .where(PROMOTION_REDEMPTION.PROMOTION_ID.eq(PROMOTION.ID))
            .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED"))).as("discount_total");

    /**
     * Newest first.
     *
     * @param ownerUserId the shop owner's account, to tell their own pause from Pegasus's
     */
    public List<SellerPromotion> page(long shopId, long ownerUserId, PromotionStatus status, Paging paging,
            OffsetDateTime now) {
        return dsl.select(PROMOTION.fields()).select(DISCOUNT_TOTAL)
                .from(PROMOTION)
                .where(own(shopId))
                .and(status == null ? DSL.noCondition() : AdminPromotionRepository.status(now).eq(status.name()))
                .orderBy(PROMOTION.CREATED_AT.desc(), PROMOTION.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> toView(r, ownerUserId, now));
    }

    public long count(long shopId, PromotionStatus status, OffsetDateTime now) {
        return dsl.selectCount()
                .from(PROMOTION)
                .where(own(shopId))
                .and(status == null ? DSL.noCondition() : AdminPromotionRepository.status(now).eq(status.name()))
                .fetchSingle(0, Long.class);
    }

    public Map<PromotionStatus, Long> statusCounts(long shopId, OffsetDateTime now) {
        Map<PromotionStatus, Long> counts = new EnumMap<>(PromotionStatus.class);
        for (PromotionStatus s : PromotionStatus.values()) counts.put(s, 0L);
        Field<String> status = AdminPromotionRepository.status(now).as("promotion_status");
        dsl.select(status, DSL.count())
                .from(PROMOTION)
                .where(own(shopId))
                .groupBy(status)
                .fetch()
                .forEach(r -> counts.put(PromotionStatus.valueOf(r.value1()), r.value2().longValue()));
        return counts;
    }

    public Optional<SellerPromotion> find(long id, long shopId, long ownerUserId, OffsetDateTime now) {
        return dsl.select(PROMOTION.fields()).select(DISCOUNT_TOTAL)
                .from(PROMOTION)
                .where(PROMOTION.ID.eq(id))
                .and(own(shopId))
                .fetchOptional(r -> toView(r, ownerUserId, now));
    }

    public long countActive(long shopId, OffsetDateTime now) {
        return count(shopId, PromotionStatus.ACTIVE, now);
    }

    /** The shop's codes held or spent on orders since {@code from}; released ones do not count. */
    public long countRedemptions(long shopId, OffsetDateTime from) {
        return dsl.selectCount()
                .from(PROMOTION_REDEMPTION)
                .join(PROMOTION).on(PROMOTION.ID.eq(PROMOTION_REDEMPTION.PROMOTION_ID))
                .where(own(shopId))
                .and(PROMOTION_REDEMPTION.STATUS.ne("RELEASED"))
                .and(PROMOTION_REDEMPTION.CREATED_AT.ge(from))
                .fetchSingle(0, Long.class);
    }

    /** What the shop's codes took off paid orders since {@code from}. */
    public BigDecimal shopDiscount(long shopId, OffsetDateTime from) {
        return dsl.select(DSL.coalesce(DSL.sum(PROMOTION_REDEMPTION.AMOUNT), BigDecimal.ZERO))
                .from(PROMOTION_REDEMPTION)
                .join(PROMOTION).on(PROMOTION.ID.eq(PROMOTION_REDEMPTION.PROMOTION_ID))
                .where(own(shopId))
                .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED"))
                .and(PROMOTION_REDEMPTION.CREATED_AT.ge(from))
                .fetchSingle(0, BigDecimal.class);
    }

    /** The shop's part of paid orders since {@code from} that used one of its codes, each order once. */
    public BigDecimal salesWithCodes(long shopId, OffsetDateTime from) {
        return dsl.select(DSL.coalesce(DSL.sum(SELLER_ORDER.GRAND_TOTAL), BigDecimal.ZERO))
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(shopId))
                .and(DSL.exists(DSL.selectOne()
                        .from(PROMOTION_REDEMPTION)
                        .join(PROMOTION).on(PROMOTION.ID.eq(PROMOTION_REDEMPTION.PROMOTION_ID))
                        .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                        .and(own(shopId))
                        .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED"))
                        .and(PROMOTION_REDEMPTION.CREATED_AT.ge(from))))
                .fetchSingle(0, BigDecimal.class);
    }

    /**
     * Newest first. The buyer is their display name only — never username, email or address.
     * The order number is the shop's own part of the buyer's order.
     */
    public List<Redemption> redemptions(long promotionId, long shopId, Paging paging) {
        return dsl.select(PROMOTION_REDEMPTION.ID, SELLER_ORDER.SELLER_ORDER_NUMBER, USER_ACCOUNT.DISPLAY_NAME,
                        PROMOTION_REDEMPTION.AMOUNT, PROMOTION_REDEMPTION.STATUS, PROMOTION_REDEMPTION.CREATED_AT)
                .from(PROMOTION_REDEMPTION)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(PROMOTION_REDEMPTION.BUYER_ID))
                .leftJoin(SELLER_ORDER).on(SELLER_ORDER.SALES_ORDER_ID.eq(PROMOTION_REDEMPTION.SALES_ORDER_ID)
                        .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(shopId)))
                .where(PROMOTION_REDEMPTION.PROMOTION_ID.eq(promotionId))
                .orderBy(PROMOTION_REDEMPTION.CREATED_AT.desc(), PROMOTION_REDEMPTION.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> new Redemption(
                        r.get(PROMOTION_REDEMPTION.ID),
                        r.get(SELLER_ORDER.SELLER_ORDER_NUMBER),
                        r.get(USER_ACCOUNT.DISPLAY_NAME),
                        r.get(PROMOTION_REDEMPTION.AMOUNT),
                        r.get(PROMOTION_REDEMPTION.STATUS),
                        r.get(PROMOTION_REDEMPTION.CREATED_AT)));
    }

    public long countRedemptionsOf(long promotionId) {
        return dsl.fetchCount(PROMOTION_REDEMPTION, PROMOTION_REDEMPTION.PROMOTION_ID.eq(promotionId));
    }

    private static Condition own(long shopId) {
        return PROMOTION.SELLER_PROFILE_ID.eq(shopId);
    }

    private static SellerPromotion toView(Record r, long ownerUserId, OffsetDateTime now) {
        Promotion p = PromotionRepository.toPromotion(r.into(PROMOTION));
        PausedBy pausedBy = p.pausedAt() == null ? null
                : Objects.equals(p.pausedBy(), ownerUserId) ? PausedBy.SELLER : PausedBy.ADMIN;
        return new SellerPromotion(p.id(), p.code(), p.name(), p.description(), p.discountType(),
                p.discountValue(), p.maxDiscount(), p.minSpend(), p.visibility(), p.newBuyerOnly(),
                p.usageLimitTotal(), p.usageLimitPerUser(), p.usedCount(), p.claimable(), p.claimLimit(),
                p.claimedCount(), r.get(DISCOUNT_TOTAL), p.startsAt(), p.endsAt(), p.statusAt(now), p.pausedAt(),
                pausedBy, p.pauseReason(), p.createdAt(), p.updatedAt());
    }
}
