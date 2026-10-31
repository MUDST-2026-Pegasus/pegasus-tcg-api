package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionClaim.PROMOTION_CLAIM;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.Party;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.AdminPromotion;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.Redemption;
import com.pegasus.pegasustcgapi.jooq.tables.UserAccount;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;
import org.jooq.SelectSelectStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read side of the admin promotions desk. Writes stay in {@link PromotionRepository}.
 *
 * <p>Status is not a column, so filtering and the tab counts use {@link #status},
 * a CASE written to match {@link PromotionStatus#of} rule for rule.
 */
@Repository
public class AdminPromotionRepository {

    private static final UserAccount OWNER = USER_ACCOUNT.as("owner");
    private static final UserAccount BUYER = USER_ACCOUNT.as("buyer");
    private static final UserAccount CREATOR = USER_ACCOUNT.as("creator");
    private static final UserAccount PAUSER = USER_ACCOUNT.as("pauser");

    /** Orders that count as paid for the KPI row; refunded and cancelled ones do not. */
    private static final List<String> PAID = List.of("PAID", "PARTIALLY_COMPLETED", "COMPLETED");

    private static final Field<BigDecimal> DISCOUNT_TOTAL = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(PROMOTION_REDEMPTION.AMOUNT), BigDecimal.ZERO))
                    .from(PROMOTION_REDEMPTION)
                    .where(PROMOTION_REDEMPTION.PROMOTION_ID.eq(PROMOTION.ID))
                    .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED")))
            .as("discount_total");

    private static final Field<BigDecimal> SALES_TOTAL = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(SALES_ORDER.GRAND_TOTAL), BigDecimal.ZERO))
                    .from(SALES_ORDER)
                    .where(DSL.exists(DSL.selectOne()
                            .from(PROMOTION_REDEMPTION)
                            .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(SALES_ORDER.ID))
                            .and(PROMOTION_REDEMPTION.PROMOTION_ID.eq(PROMOTION.ID))
                            .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED")))))
            .as("sales_total");

    private static final Field<Integer> RESERVED_ORDERS = DSL.field(
            DSL.selectCount()
                    .from(PROMOTION_REDEMPTION)
                    .where(PROMOTION_REDEMPTION.PROMOTION_ID.eq(PROMOTION.ID))
                    .and(PROMOTION_REDEMPTION.STATUS.eq("RESERVED")))
            .as("reserved_orders");

    private final DSLContext dsl;
    private final StorageService storage;

    public AdminPromotionRepository(DSLContext dsl, StorageService storage) {
        this.dsl = dsl;
        this.storage = storage;
    }

    /** Newest first. {@code owner}, {@code status} and {@code q} are optional. */
    public List<AdminPromotion> page(OwnerType owner, PromotionStatus status, String q, Paging paging,
            OffsetDateTime now) {
        return withOwner(viewSelect())
                .where(filters(owner, q))
                .and(status == null ? DSL.noCondition() : status(now).eq(status.name()))
                .orderBy(PROMOTION.CREATED_AT.desc(), PROMOTION.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> toView(r, now));
    }

    public long count(OwnerType owner, PromotionStatus status, String q, OffsetDateTime now) {
        return withOwner(dsl.selectCount())
                .where(filters(owner, q))
                .and(status == null ? DSL.noCondition() : status(now).eq(status.name()))
                .fetchSingle(0, Long.class);
    }

    /** Every status, zero included, under the same owner and search filters. */
    public Map<PromotionStatus, Long> statusCounts(OwnerType owner, String q, OffsetDateTime now) {
        Map<PromotionStatus, Long> counts = new EnumMap<>(PromotionStatus.class);
        for (PromotionStatus s : PromotionStatus.values()) counts.put(s, 0L);
        // Not "status": seller_profile has that column, and GROUP BY would read it first.
        Field<String> status = status(now).as("promotion_status");
        withOwner(dsl.select(status, DSL.count()))
                .where(filters(owner, q))
                .groupBy(status)
                .fetch()
                .forEach(r -> counts.put(PromotionStatus.valueOf(r.value1()), r.value2().longValue()));
        return counts;
    }

    public Optional<AdminPromotion> find(long id, OffsetDateTime now) {
        return withOwner(viewSelect())
                .where(PROMOTION.ID.eq(id))
                .fetchOptional(r -> toView(r, now));
    }

    public long countActive(OwnerType owner, OffsetDateTime now) {
        return dsl.selectCount()
                .from(PROMOTION)
                .where(PROMOTION.OWNER_TYPE.eq(owner.name()))
                .and(status(now).eq(PromotionStatus.ACTIVE.name()))
                .fetchSingle(0, Long.class);
    }

    /**
     * Codes held or spent on an order created in [from, to); released ones do not count.
     * A null {@code to} counts up to the latest row, which is how the current window is read.
     */
    public long countRedemptions(OffsetDateTime from, OffsetDateTime to) {
        return dsl.selectCount()
                .from(PROMOTION_REDEMPTION)
                .where(PROMOTION_REDEMPTION.STATUS.ne("RELEASED"))
                .and(PROMOTION_REDEMPTION.CREATED_AT.ge(from))
                .and(to == null ? DSL.noCondition() : PROMOTION_REDEMPTION.CREATED_AT.lt(to))
                .fetchSingle(0, Long.class);
    }

    public long countClaims(OffsetDateTime from, OffsetDateTime to) {
        return dsl.selectCount()
                .from(PROMOTION_CLAIM)
                .where(PROMOTION_CLAIM.CLAIMED_AT.ge(from))
                .and(to == null ? DSL.noCondition() : PROMOTION_CLAIM.CLAIMED_AT.lt(to))
                .fetchSingle(0, Long.class);
    }

    /** What Pegasus codes took off paid orders in [from, to). */
    public BigDecimal platformDiscountCost(OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(DSL.coalesce(DSL.sum(PROMOTION_REDEMPTION.AMOUNT), BigDecimal.ZERO))
                .from(PROMOTION_REDEMPTION)
                .join(PROMOTION).on(PROMOTION.ID.eq(PROMOTION_REDEMPTION.PROMOTION_ID))
                .where(PROMOTION.OWNER_TYPE.eq(OwnerType.PLATFORM.name()))
                .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED"))
                .and(PROMOTION_REDEMPTION.CREATED_AT.ge(from))
                .and(to == null ? DSL.noCondition() : PROMOTION_REDEMPTION.CREATED_AT.lt(to))
                .fetchSingle(0, BigDecimal.class);
    }

    /** Grand total of paid orders in [from, to) that used any code, each order once. */
    public BigDecimal salesWithCodes(OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(DSL.coalesce(DSL.sum(SALES_ORDER.GRAND_TOTAL), BigDecimal.ZERO))
                .from(SALES_ORDER)
                .where(DSL.exists(DSL.selectOne()
                        .from(PROMOTION_REDEMPTION)
                        .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(SALES_ORDER.ID))
                        .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED"))
                        .and(PROMOTION_REDEMPTION.CREATED_AT.ge(from))
                        .and(to == null ? DSL.noCondition() : PROMOTION_REDEMPTION.CREATED_AT.lt(to))))
                .fetchSingle(0, BigDecimal.class);
    }

    /** Grand total of orders paid in [from, to). */
    public BigDecimal gmv(OffsetDateTime from, OffsetDateTime to) {
        return dsl.select(DSL.coalesce(DSL.sum(SALES_ORDER.GRAND_TOTAL), BigDecimal.ZERO))
                .from(SALES_ORDER)
                .where(paidIn(from, to))
                .fetchSingle(0, BigDecimal.class);
    }

    public long countPaidOrders(OffsetDateTime from, OffsetDateTime to) {
        return dsl.fetchCount(SALES_ORDER, paidIn(from, to));
    }

    /** Orders paid in [from, to) that used any code — the same orders {@link #countPaidOrders} counts. */
    public long countPaidOrdersWithCodes(OffsetDateTime from, OffsetDateTime to) {
        return dsl.fetchCount(SALES_ORDER, paidIn(from, to).and(DSL.exists(DSL.selectOne()
                .from(PROMOTION_REDEMPTION)
                .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(SALES_ORDER.ID))
                .and(PROMOTION_REDEMPTION.STATUS.eq("CONSUMED")))));
    }

    private static Condition paidIn(OffsetDateTime from, OffsetDateTime to) {
        return SALES_ORDER.STATUS.in(PAID)
                .and(SALES_ORDER.PAID_AT.ge(from))
                .and(to == null ? DSL.noCondition() : SALES_ORDER.PAID_AT.lt(to));
    }

    /** Newest first. */
    public List<Redemption> redemptions(long promotionId, Paging paging) {
        return dsl.select(PROMOTION_REDEMPTION.ID, PROMOTION_REDEMPTION.SALES_ORDER_ID, SALES_ORDER.ORDER_NUMBER,
                        BUYER.ID, BUYER.USERNAME, BUYER.DISPLAY_NAME, BUYER.AVATAR_URL,
                        PROMOTION_REDEMPTION.AMOUNT, PROMOTION_REDEMPTION.STATUS, PROMOTION_REDEMPTION.CREATED_AT)
                .from(PROMOTION_REDEMPTION)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(PROMOTION_REDEMPTION.SALES_ORDER_ID))
                .join(BUYER).on(BUYER.ID.eq(PROMOTION_REDEMPTION.BUYER_ID))
                .where(PROMOTION_REDEMPTION.PROMOTION_ID.eq(promotionId))
                .orderBy(PROMOTION_REDEMPTION.CREATED_AT.desc(), PROMOTION_REDEMPTION.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> new Redemption(
                        r.get(PROMOTION_REDEMPTION.ID),
                        r.get(PROMOTION_REDEMPTION.SALES_ORDER_ID),
                        r.get(SALES_ORDER.ORDER_NUMBER),
                        new Party(r.get(BUYER.ID), r.get(BUYER.USERNAME), r.get(BUYER.DISPLAY_NAME),
                                avatar(r.get(BUYER.AVATAR_URL))),
                        r.get(PROMOTION_REDEMPTION.AMOUNT),
                        r.get(PROMOTION_REDEMPTION.STATUS),
                        r.get(PROMOTION_REDEMPTION.CREATED_AT)));
    }

    public long countRedemptionsOf(long promotionId) {
        return dsl.fetchCount(PROMOTION_REDEMPTION, PROMOTION_REDEMPTION.PROMOTION_ID.eq(promotionId));
    }

    /** {@link PromotionStatus#of} in SQL: expired, then paused, scheduled, used up, else active. */
    static Field<String> status(OffsetDateTime now) {
        Field<OffsetDateTime> at = DSL.val(now);
        return DSL.when(PROMOTION.ENDS_AT.le(at), PromotionStatus.EXPIRED.name())
                .when(PROMOTION.PAUSED_AT.isNotNull(), PromotionStatus.PAUSED.name())
                .when(PROMOTION.STARTS_AT.gt(at), PromotionStatus.SCHEDULED.name())
                .when(PROMOTION.USAGE_LIMIT_TOTAL.isNotNull()
                        .and(PROMOTION.USED_COUNT.ge(PROMOTION.USAGE_LIMIT_TOTAL)), PromotionStatus.USED_UP.name())
                .otherwise(PromotionStatus.ACTIVE.name());
    }

    /** Everything {@link #toView} reads: the code, its shop, who created and paused it, and its totals. */
    private SelectSelectStep<Record> viewSelect() {
        return dsl.select(PROMOTION.fields())
                .select(OWNER.USERNAME, OWNER.DISPLAY_NAME, OWNER.AVATAR_URL)
                .select(CREATOR.ID, CREATOR.USERNAME, CREATOR.DISPLAY_NAME, CREATOR.AVATAR_URL)
                .select(PAUSER.ID, PAUSER.USERNAME, PAUSER.DISPLAY_NAME, PAUSER.AVATAR_URL)
                .select(DISCOUNT_TOTAL, SALES_TOTAL, RESERVED_ORDERS);
    }

    private static <R extends Record> SelectOnConditionStep<R> withOwner(SelectSelectStep<R> select) {
        return select.from(PROMOTION)
                .leftJoin(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(PROMOTION.SELLER_PROFILE_ID))
                .leftJoin(OWNER).on(OWNER.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(CREATOR).on(CREATOR.ID.eq(PROMOTION.CREATED_BY))
                .leftJoin(PAUSER).on(PAUSER.ID.eq(PROMOTION.PAUSED_BY));
    }

    /** {@code q} matches the code, the name, or the shop's username or display name. */
    private static Condition filters(OwnerType owner, String q) {
        Condition c = owner == null ? DSL.noCondition() : PROMOTION.OWNER_TYPE.eq(owner.name());
        if (q != null && !q.isBlank()) {
            String like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            c = c.and(PROMOTION.CODE.likeIgnoreCase(like, '\\')
                    .or(PROMOTION.NAME.likeIgnoreCase(like, '\\'))
                    .or(OWNER.USERNAME.likeIgnoreCase(like, '\\'))
                    .or(OWNER.DISPLAY_NAME.likeIgnoreCase(like, '\\')));
        }
        return c;
    }

    private AdminPromotion toView(Record r, OffsetDateTime now) {
        Promotion p = PromotionRepository.toPromotion(r.into(PROMOTION));
        Party owner = p.sellerProfileId() == null ? null : new Party(p.sellerProfileId(),
                r.get(OWNER.USERNAME), r.get(OWNER.DISPLAY_NAME), avatar(r.get(OWNER.AVATAR_URL)));
        return new AdminPromotion(p.id(), p.code(), p.name(), p.description(), p.ownerType(), owner,
                p.discountType(), p.discountValue(), p.maxDiscount(), p.minSpend(), p.scopeType(), p.scopeIds(),
                p.visibility(), p.newBuyerOnly(), p.usageLimitTotal(), p.usageLimitPerUser(), p.usedCount(),
                p.claimable(), p.claimRequired(), p.claimLimit(), p.claimedCount(), p.featuredOnHome(),
                p.homeSort(), p.startsAt(), p.endsAt(), p.statusAt(now), p.pausedAt(), p.pauseReason(),
                p.createdAt(), p.updatedAt(),
                party(r, CREATOR), party(r, PAUSER),
                r.get(DISCOUNT_TOTAL), r.get(SALES_TOTAL), r.get(RESERVED_ORDERS));
    }

    private Party party(Record r, UserAccount user) {
        Long id = r.get(user.ID);
        return id == null ? null
                : new Party(id, r.get(user.USERNAME), r.get(user.DISPLAY_NAME), avatar(r.get(user.AVATAR_URL)));
    }

    /** {@code user_account.avatar_url} holds an object key; the page needs a short-lived link. */
    private String avatar(String key) {
        return key == null ? null : storage.presignDownload(key);
    }
}
