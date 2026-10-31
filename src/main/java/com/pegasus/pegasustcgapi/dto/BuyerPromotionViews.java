package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.PromotionClaimSource;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What buyers read about collecting codes and their wallet [TCG-576] (PRM-H1/H2, W1, M3). */
public final class BuyerPromotionViews {

    private BuyerPromotionViews() {
    }

    /** The shop behind a shop code, for "ไปที่ร้าน". */
    public record Shop(long sellerProfileId, String name, String username) {
    }

    /**
     * A ticket on the home or store strip.
     *
     * @param shop            null for a Pegasus code
     * @param remainingClaims copies left to collect; null when unlimited, 0 shows "หมดแล้ว"
     * @param claimed         whether the caller has it already; always false for a guest
     */
    public record ClaimableCode(
            long id,
            String code,
            String name,
            String description,
            OwnerType ownerType,
            Shop shop,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minSpend,
            OffsetDateTime endsAt,
            boolean claimRequired,
            Integer remainingClaims,
            boolean claimed) {
    }

    public record Claim(long id, long promotionId, String code, PromotionClaimSource source,
            OffsetDateTime claimedAt) {
    }

    /** @param alreadyClaimed the caller had collected it before; nothing changed */
    public record ClaimResult(Claim claim, boolean alreadyClaimed) {
    }

    public record ScopeTarget(long id, String name) {
    }

    /**
     * A code's full terms, for the "เงื่อนไขโค้ด" modal.
     *
     * @param scopeTargets         the games or categories it covers; empty for ALL
     * @param stacksWithShopCodes  a Pegasus code goes alongside one code per shop; false for shop codes
     */
    public record Terms(
            long id,
            String code,
            String name,
            String description,
            OwnerType ownerType,
            Shop shop,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minSpend,
            ScopeType scopeType,
            List<ScopeTarget> scopeTargets,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int usageLimitPerUser,
            boolean newBuyerOnly,
            boolean stacksWithShopCodes,
            boolean claimable,
            boolean claimRequired,
            Integer remainingClaims,
            PromotionStatus status,
            boolean claimed) {
    }

    public enum WalletTab { AVAILABLE, USED, EXPIRED }

    /** An order a code went on; {@code status} is RESERVED until it is paid, then CONSUMED. */
    public record Use(long salesOrderId, String orderNumber, BigDecimal amount, String status,
            OffsetDateTime at) {
    }

    /**
     * A code in "My coupons".
     *
     * @param claimedAt null for a code typed straight in at checkout and never collected
     * @param usesLeft  uses this buyer has left under usage_limit_per_user
     * @param uses      paid orders it went on (USED tab); empty on the other tabs
     */
    public record WalletCode(
            long id,
            String code,
            String name,
            String description,
            OwnerType ownerType,
            Shop shop,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minSpend,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            PromotionStatus status,
            OffsetDateTime claimedAt,
            int usesLeft,
            List<Use> uses) {
    }

    /** @param counts every tab's size under the same owner filter, for the tab badges */
    public record Wallet(WalletTab tab, List<WalletCode> items, Map<WalletTab, Integer> counts) {
    }
}
