package com.pegasus.pegasustcgapi.model.sale;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A direct price discount [TCG-676]: listings show a lower price while it runs,
 * no code needed. Pegasus pays for PLATFORM campaigns, the shop for its own
 * SELLER sales. A listing takes one sale price at a time — see
 * {@code SalePriceResolver}.
 *
 * @param sellerProfileId    the shop for a SELLER sale; null for PLATFORM
 * @param discountValue      percent (1–90) for PERCENT, baht per card for FIXED_OFF,
 *                           zero for FIXED_PRICE (each listing row names its price)
 * @param maxDiscountPerUnit cap in baht per card on a PERCENT discount; null means no cap
 * @param scopeIds           game ids for GAME, category ids for CATEGORY, card set ids
 *                           for CARD_SET, empty otherwise
 * @param conditions         card conditions covered; empty covers all
 * @param minUnitPrice       listings priced below this stay out; null means no floor
 * @param perListingQuota    cards per listing sold at the sale price (flash); null means no quota
 * @param budgetCap          most subsidy a PLATFORM campaign may hold; null means no cap
 * @param subsidyReserved    subsidy held by checkouts so far
 * @param endedAt            set when ended early; {@code endsAt} keeps the planned end
 * @param budgetExhaustedAt  set when a checkout's subsidy did not fit the budget [TCG-680]
 * @param excludedSellerIds  shops a PLATFORM campaign leaves out; empty for every SELLER sale
 */
public record PriceSale(
        long id,
        String name,
        String slug,
        OwnerType ownerType,
        Long sellerProfileId,
        SaleType saleType,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountPerUnit,
        ScopeType scopeType,
        List<Long> scopeIds,
        List<CardCondition> conditions,
        BigDecimal minUnitPrice,
        Integer perListingQuota,
        Integer perBuyerLimit,
        BigDecimal budgetCap,
        BigDecimal subsidyReserved,
        String bannerImageUrl,
        boolean featuredOnHome,
        Integer homeSort,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime pausedAt,
        Long pausedBy,
        String pauseReason,
        OffsetDateTime endedAt,
        Long createdBy,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime budgetExhaustedAt,
        List<Long> excludedSellerIds) {

    public PriceSale {
        scopeIds = List.copyOf(scopeIds);
        conditions = List.copyOf(conditions);
        excludedSellerIds = excludedSellerIds == null ? List.of() : List.copyOf(excludedSellerIds);
    }

    /** A sale that leaves no shop out. */
    public PriceSale(long id, String name, String slug, OwnerType ownerType, Long sellerProfileId,
            SaleType saleType, DiscountType discountType, BigDecimal discountValue, BigDecimal maxDiscountPerUnit,
            ScopeType scopeType, List<Long> scopeIds, List<CardCondition> conditions, BigDecimal minUnitPrice,
            Integer perListingQuota, Integer perBuyerLimit, BigDecimal budgetCap, BigDecimal subsidyReserved,
            String bannerImageUrl, boolean featuredOnHome, Integer homeSort, OffsetDateTime startsAt,
            OffsetDateTime endsAt, OffsetDateTime pausedAt, Long pausedBy, String pauseReason,
            OffsetDateTime endedAt, Long createdBy, int version, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, OffsetDateTime budgetExhaustedAt) {
        this(id, name, slug, ownerType, sellerProfileId, saleType, discountType, discountValue, maxDiscountPerUnit,
                scopeType, scopeIds, conditions, minUnitPrice, perListingQuota, perBuyerLimit, budgetCap,
                subsidyReserved, bannerImageUrl, featuredOnHome, homeSort, startsAt, endsAt, pausedAt, pausedBy,
                pauseReason, endedAt, createdBy, version, createdAt, updatedAt, budgetExhaustedAt, List.of());
    }

    /** A sale whose budget was never found short. */
    public PriceSale(long id, String name, String slug, OwnerType ownerType, Long sellerProfileId,
            SaleType saleType, DiscountType discountType, BigDecimal discountValue, BigDecimal maxDiscountPerUnit,
            ScopeType scopeType, List<Long> scopeIds, List<CardCondition> conditions, BigDecimal minUnitPrice,
            Integer perListingQuota, Integer perBuyerLimit, BigDecimal budgetCap, BigDecimal subsidyReserved,
            String bannerImageUrl, boolean featuredOnHome, Integer homeSort, OffsetDateTime startsAt,
            OffsetDateTime endsAt, OffsetDateTime pausedAt, Long pausedBy, String pauseReason,
            OffsetDateTime endedAt, Long createdBy, int version, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(id, name, slug, ownerType, sellerProfileId, saleType, discountType, discountValue, maxDiscountPerUnit,
                scopeType, scopeIds, conditions, minUnitPrice, perListingQuota, perBuyerLimit, budgetCap,
                subsidyReserved, bannerImageUrl, featuredOnHome, homeSort, startsAt, endsAt, pausedAt, pausedBy,
                pauseReason, endedAt, createdBy, version, createdAt, updatedAt, null, List.of());
    }

    /** Who pays the difference: Pegasus for its campaigns, the shop for its own sales. */
    public enum OwnerType { PLATFORM, SELLER }

    public enum SaleType { FLASH_SALE, CATEGORY_SALE, SHOP_SALE }

    public enum DiscountType { PERCENT, FIXED_OFF, FIXED_PRICE }

    public enum ScopeType { ALL, GAME, CATEGORY, CARD_SET, SHOP, LISTINGS }

    /**
     * @param soldOut whether every listing of a flash sale has used up its quota;
     *                only the caller can know, since it lives in price_sale_listing
     */
    public SaleStatus statusAt(OffsetDateTime now, boolean soldOut) {
        return SaleStatus.of(this, now, soldOut);
    }

    /** When the sale stops: early if it was ended, otherwise as planned. */
    public OffsetDateTime effectiveEnd() {
        return endedAt != null && endedAt.isBefore(endsAt) ? endedAt : endsAt;
    }

    /** The subsidy held reached the cap, or a checkout found what was left too small. */
    public boolean budgetExhausted() {
        return budgetCap != null && (subsidyReserved.compareTo(budgetCap) >= 0 || budgetExhaustedAt != null);
    }
}
