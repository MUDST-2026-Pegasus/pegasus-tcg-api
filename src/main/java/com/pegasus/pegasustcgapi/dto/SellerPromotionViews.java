package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/** What a shop reads about its own discount codes (PRM-S1/S2). */
public final class SellerPromotionViews {

    private SellerPromotionViews() {
    }

    /** Who stopped a paused code. A shop can only resume its own pause. */
    public enum PausedBy { SELLER, ADMIN }

    /**
     * @param discountTotal what this code has taken off the shop's paid orders
     * @param pausedBy      null unless paused
     * @param pauseReason   set when Pegasus paused it, so the shop knows why
     */
    public record SellerPromotion(
            long id,
            String code,
            String name,
            String description,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minSpend,
            Visibility visibility,
            boolean newBuyerOnly,
            Integer usageLimitTotal,
            int usageLimitPerUser,
            int usedCount,
            boolean claimable,
            Integer claimLimit,
            int claimedCount,
            BigDecimal discountTotal,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            PromotionStatus status,
            OffsetDateTime pausedAt,
            PausedBy pausedBy,
            String pauseReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** @param statusCounts every status, zero included, for the tabs; ignores the status filter */
    public record SellerPromotionPage(PageResponse<SellerPromotion> page, Map<PromotionStatus, Long> statusCounts) {
    }

    /**
     * The shop's KPI row over the last 30 days, for its own codes only.
     *
     * @param shopDiscount30d   what the shop's codes took off paid orders
     * @param salesWithCodes30d the shop's part of paid orders that used one of its codes
     */
    public record Summary(
            long activeCodes,
            long redemptions30d,
            BigDecimal shopDiscount30d,
            BigDecimal salesWithCodes30d) {
    }

    /**
     * A shop code on one order. The buyer is shown by display name only.
     *
     * @param sellerOrderNumber the shop's own order number; null if the order has no part for this shop
     */
    public record Redemption(
            long id,
            String sellerOrderNumber,
            String buyerDisplayName,
            BigDecimal amount,
            String status,
            OffsetDateTime createdAt) {
    }
}
