package com.pegasus.pegasustcgapi.dto.promotion;

import com.pegasus.pegasustcgapi.model.promotion.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.OwnerType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What the cart and checkout pages read about discount codes (PRM-B1/B2, C1/C2). */
public final class CheckoutPromotionViews {

    private CheckoutPromotionViews() {
    }

    /**
     * One shop's part of the preview.
     *
     * @param shippingFee null when shipping is still an estimate (no delivery chosen yet)
     */
    public record SellerPreview(
            long sellerProfileId,
            String sellerName,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal sellerDiscount,
            BigDecimal platformDiscount,
            BigDecimal grandTotal) {
    }

    /**
     * How one code fared.
     *
     * @param status          APPLIED, or the error code saying why not (PROMO_MIN_SPEND_NOT_MET, ...)
     * @param discount        what it takes off; null while a FREE_SHIPPING code waits for shipping
     * @param shortfall       how much more to buy, for PROMO_MIN_SPEND_NOT_MET
     * @param pendingShipping a FREE_SHIPPING code that will apply once delivery is chosen
     * @param ownerType       whose code it is; null when no such code is shown
     * @param sellerProfileId the shop that owns the code, or the slot's shop when the code is not shown
     * @param slotOwnerType   the slot it was typed in: PLATFORM, or SELLER for a shop's slot [TCG-769]
     * @param slotSellerProfileId the shop of that slot; null for the Pegasus slot
     */
    public record CodeOutcome(
            String code,
            OwnerType ownerType,
            Long sellerProfileId,
            String sellerName,
            String status,
            BigDecimal discount,
            BigDecimal shortfall,
            boolean pendingShipping,
            OwnerType slotOwnerType,
            Long slotSellerProfileId) {
    }

    /** The codes autoPick chose: one Pegasus code and one per shop. */
    public record BestPick(String platformCode, Map<Long, String> sellerCodes) {
    }

    /**
     * @param shippingEstimated no delivery was chosen: shippingTotal is null and the
     *                          grand total leaves shipping out ("ยังไม่รวมค่าส่ง")
     * @param bestPick          set only when autoPick was asked for
     */
    public record PromoPreview(
            List<SellerPreview> sellers,
            BigDecimal itemsSubtotal,
            BigDecimal shippingTotal,
            BigDecimal sellerDiscountTotal,
            BigDecimal platformDiscountTotal,
            BigDecimal grandTotal,
            boolean shippingEstimated,
            List<CodeOutcome> codes,
            BestPick bestPick) {
    }

    /** Which group the picker shows a code in. */
    public enum Source { CLAIMED, CLAIMABLE, PUBLIC }

    /**
     * A code the buyer could use on this basket, alone.
     *
     * @param remainingClaims copies left to collect, for CLAIMABLE; null when unlimited
     * @param reason          why it cannot apply (an error code); null when applicable
     */
    public record AvailablePromotion(
            long id,
            String code,
            String name,
            OwnerType ownerType,
            Long sellerProfileId,
            String sellerName,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minSpend,
            OffsetDateTime endsAt,
            Source source,
            Integer remainingClaims,
            boolean applicable,
            BigDecimal estimatedDiscount,
            String reason,
            BigDecimal shortfall) {
    }
}
