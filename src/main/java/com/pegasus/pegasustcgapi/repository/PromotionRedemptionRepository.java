package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.PromotionRedemption.PROMOTION_REDEMPTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code promotion_redemption} — a code on an order [TCG-569].
 * RESERVED at checkout, CONSUMED once the order is paid, RELEASED when it is
 * cancelled or times out unpaid. Only RESERVED rows ever move, so every
 * transition here is safe to run twice.
 */
@Repository
public class PromotionRedemptionRepository {

    private static final String RESERVED = "RESERVED";

    private final DSLContext dsl;

    public PromotionRedemptionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** @param sellerOrderId the shop's part of the order for a shop code; null for a Pegasus code */
    public long insertReserved(long promotionId, long buyerId, long salesOrderId, Long sellerOrderId,
            BigDecimal amount) {
        return dsl.insertInto(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.PROMOTION_ID, promotionId)
                .set(PROMOTION_REDEMPTION.BUYER_ID, buyerId)
                .set(PROMOTION_REDEMPTION.SALES_ORDER_ID, salesOrderId)
                .set(PROMOTION_REDEMPTION.SELLER_ORDER_ID, sellerOrderId)
                .set(PROMOTION_REDEMPTION.AMOUNT, amount)
                .set(PROMOTION_REDEMPTION.STATUS, RESERVED)
                .returningResult(PROMOTION_REDEMPTION.ID)
                .fetchSingle(PROMOTION_REDEMPTION.ID);
    }

    /** How many times this buyer holds or has spent the code; released uses do not count. */
    public int countHeld(long promotionId, long buyerId) {
        return dsl.fetchCount(PROMOTION_REDEMPTION, PROMOTION_REDEMPTION.PROMOTION_ID.eq(promotionId)
                .and(PROMOTION_REDEMPTION.BUYER_ID.eq(buyerId))
                .and(PROMOTION_REDEMPTION.STATUS.ne("RELEASED")));
    }

    /** The order was paid: its held codes are spent for good. */
    public int consumeForSalesOrder(long salesOrderId) {
        return dsl.update(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.STATUS, "CONSUMED")
                .set(PROMOTION_REDEMPTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(salesOrderId))
                .and(PROMOTION_REDEMPTION.STATUS.eq(RESERVED))
                .execute();
    }

    /** Releases every code still held by the order; returns the promotions whose use comes back. */
    public List<Long> releaseForSalesOrder(long salesOrderId) {
        return dsl.update(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.STATUS, "RELEASED")
                .set(PROMOTION_REDEMPTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.eq(salesOrderId))
                .and(PROMOTION_REDEMPTION.STATUS.eq(RESERVED))
                .returningResult(PROMOTION_REDEMPTION.PROMOTION_ID)
                .fetch(PROMOTION_REDEMPTION.PROMOTION_ID);
    }

    /** Releases a shop code held by one shop's part of the order. */
    public List<Long> releaseForSellerOrder(long sellerOrderId) {
        return dsl.update(PROMOTION_REDEMPTION)
                .set(PROMOTION_REDEMPTION.STATUS, "RELEASED")
                .set(PROMOTION_REDEMPTION.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PROMOTION_REDEMPTION.SELLER_ORDER_ID.eq(sellerOrderId))
                .and(PROMOTION_REDEMPTION.STATUS.eq(RESERVED))
                .returningResult(PROMOTION_REDEMPTION.PROMOTION_ID)
                .fetch(PROMOTION_REDEMPTION.PROMOTION_ID);
    }

    /** A code still on an order (not released), for the order's pages. */
    public record Applied(long salesOrderId, Long sellerOrderId, String code, String ownerType,
            Long sellerProfileId, BigDecimal amount, String status) {
    }

    public List<Applied> appliedFor(Collection<Long> salesOrderIds) {
        if (salesOrderIds.isEmpty()) return List.of();
        return dsl.select(PROMOTION_REDEMPTION.SALES_ORDER_ID, PROMOTION_REDEMPTION.SELLER_ORDER_ID, PROMOTION.CODE,
                        PROMOTION.OWNER_TYPE, PROMOTION.SELLER_PROFILE_ID, PROMOTION_REDEMPTION.AMOUNT,
                        PROMOTION_REDEMPTION.STATUS)
                .from(PROMOTION_REDEMPTION)
                .join(PROMOTION).on(PROMOTION.ID.eq(PROMOTION_REDEMPTION.PROMOTION_ID))
                .where(PROMOTION_REDEMPTION.SALES_ORDER_ID.in(salesOrderIds))
                .and(PROMOTION_REDEMPTION.STATUS.ne("RELEASED"))
                .orderBy(PROMOTION_REDEMPTION.ID.asc())
                .fetch(r -> new Applied(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(),
                        r.value7()));
    }

    /** A code this buyer holds or has spent on an order, for "My coupons". */
    public record BuyerUse(long promotionId, long salesOrderId, String orderNumber, BigDecimal amount,
            String status, OffsetDateTime at) {
    }

    /** Every use this buyer holds or has spent, newest first; released ones are left out. */
    public List<BuyerUse> heldBy(long buyerId) {
        return dsl.select(PROMOTION_REDEMPTION.PROMOTION_ID, PROMOTION_REDEMPTION.SALES_ORDER_ID,
                        SALES_ORDER.ORDER_NUMBER, PROMOTION_REDEMPTION.AMOUNT, PROMOTION_REDEMPTION.STATUS,
                        PROMOTION_REDEMPTION.UPDATED_AT)
                .from(PROMOTION_REDEMPTION)
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(PROMOTION_REDEMPTION.SALES_ORDER_ID))
                .where(PROMOTION_REDEMPTION.BUYER_ID.eq(buyerId))
                .and(PROMOTION_REDEMPTION.STATUS.ne("RELEASED"))
                .orderBy(PROMOTION_REDEMPTION.UPDATED_AT.desc(), PROMOTION_REDEMPTION.ID.desc())
                .fetch(r -> new BuyerUse(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }
}
