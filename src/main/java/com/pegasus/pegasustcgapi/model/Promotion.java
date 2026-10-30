package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A discount code [TCG-566]. Pegasus pays for PLATFORM codes; the shop pays for
 * its own SELLER codes. An order takes one Pegasus code and one code per shop.
 *
 * @param sellerProfileId the shop for a SELLER code; null for PLATFORM
 * @param discountValue   percent (1–90) for PERCENT, baht for FIXED, zero for FREE_SHIPPING
 * @param maxDiscount     cap on the whole discount for PERCENT, per shop on shipping for
 *                        FREE_SHIPPING; null means no cap
 * @param scopeIds        game ids for GAME, category ids for CATEGORY, empty for ALL
 * @param usageLimitTotal null means unlimited
 * @param usedCount       redemptions held or consumed; claiming does not count
 * @param claimLimit      how many may be collected; null means unlimited
 */
public record Promotion(
        long id,
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
        int usedCount,
        boolean claimable,
        boolean claimRequired,
        Integer claimLimit,
        int claimedCount,
        boolean featuredOnHome,
        Integer homeSort,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime pausedAt,
        Long pausedBy,
        String pauseReason,
        Long createdBy,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Promotion {
        scopeIds = List.copyOf(scopeIds);
    }

    public enum OwnerType { PLATFORM, SELLER }

    public enum DiscountType { PERCENT, FIXED, FREE_SHIPPING }

    public enum ScopeType { ALL, GAME, CATEGORY }

    /** PUBLIC codes are listed to buyers; PRIVATE ones work only when typed in. */
    public enum Visibility { PUBLIC, PRIVATE }

    public PromotionStatus statusAt(OffsetDateTime now) {
        return PromotionStatus.of(this, now);
    }

    /** Whether a card of this game and category counts towards the code. */
    public boolean covers(long gameId, long categoryId) {
        return switch (scopeType) {
            case ALL -> true;
            case GAME -> scopeIds.contains(gameId);
            case CATEGORY -> scopeIds.contains(categoryId);
        };
    }
}
