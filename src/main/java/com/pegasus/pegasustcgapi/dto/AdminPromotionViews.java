package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.Party;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What the admin promotions desk reads (PRM-A1/A2/A3). */
public final class AdminPromotionViews {

    private AdminPromotionViews() {
    }

    /**
     * One code, Pegasus's or a shop's, with its status worked out at read time.
     *
     * @param owner the shop for a SELLER code (id is the seller profile); null for PLATFORM
     */
    public record AdminPromotion(
            long id,
            String code,
            String name,
            String description,
            OwnerType ownerType,
            Party owner,
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
            int usedCount,
            boolean claimable,
            boolean claimRequired,
            Integer claimLimit,
            int claimedCount,
            boolean featuredOnHome,
            Integer homeSort,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            PromotionStatus status,
            OffsetDateTime pausedAt,
            String pauseReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** @param statusCounts every status, zero included, for the tabs; ignores the status filter */
    public record AdminPromotionPage(PageResponse<AdminPromotion> page, Map<PromotionStatus, Long> statusCounts) {
    }

    /**
     * The KPI row over the last 30 days. Redemptions count codes held or spent on an
     * order (released ones do not); cost and sales count paid orders only.
     *
     * @param redemptionsChangePercent against the 30 days before; null when that was zero
     * @param platformDiscountCost30d  what Pegasus codes took off paid orders
     * @param salesWithCodes30d        grand total of paid orders that used any code
     */
    public record Summary(
            long activePlatformCodes,
            long activeSellerCodes,
            long redemptions30d,
            BigDecimal redemptionsChangePercent,
            long claims30d,
            BigDecimal platformDiscountCost30d,
            BigDecimal salesWithCodes30d) {
    }

    /** A code on one order. {@code status} is RESERVED, CONSUMED or RELEASED. */
    public record Redemption(
            long id,
            long salesOrderId,
            String orderNumber,
            Party buyer,
            BigDecimal amount,
            String status,
            OffsetDateTime createdAt) {
    }
}
