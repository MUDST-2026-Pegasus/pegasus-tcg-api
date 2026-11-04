package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.finance.AdminFinanceViews.Party;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What the admin sales desk reads (SAL-A1/A2/M1). */
public final class AdminSaleViews {

    private AdminSaleViews() {
    }

    /**
     * One sale, Pegasus's or a shop's, with its status worked out at read time.
     *
     * @param owner        the shop for a SELLER sale (id is the seller profile); null for PLATFORM
     * @param endsAt       the planned end; {@code endedAt} is set when it was ended early
     * @param soldUnits    cards sold at this sale's price
     * @param listingCount listings selling at this sale's price right now
     */
    public record AdminSale(
            long id,
            String name,
            String slug,
            OwnerType ownerType,
            Party owner,
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
            OffsetDateTime endedAt,
            SaleStatus status,
            OffsetDateTime pausedAt,
            String pauseReason,
            Party pausedBy,
            Party createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long soldUnits,
            long listingCount) {
    }

    /** @param statusCounts every status, zero included, for the tabs; ignores the status filter */
    public record AdminSalePage(PageResponse<AdminSale> page, Map<SaleStatus, Long> statusCounts) {
    }

    /**
     * The KPI row (SAL-A1).
     *
     * @param listingsOnSale  listings selling at any sale price right now
     * @param saleSales30d    what buyers paid for cards sold at a sale price in orders paid
     *                        in the last 30 days
     * @param saleSubsidy30d  SALE_SUBSIDY Pegasus paid shops in the last 30 days
     */
    public record Summary(
            long activePlatformSales,
            long activeSellerSales,
            long listingsOnSale,
            BigDecimal saleSales30d,
            BigDecimal saleSubsidy30d) {
    }

    /**
     * A listing a sale reaches: selling at its price now, or one whose flash quota ran out.
     *
     * @param salePrice null when the listing is not selling at this sale's price now
     * @param quota     cards at the sale price for this listing; null when the sale has no quota
     * @param quotaLeft null when the sale has no quota
     */
    public record SaleListingRow(
            long listingId,
            String productName,
            CardCondition condition,
            Party seller,
            BigDecimal listPrice,
            BigDecimal salePrice,
            int soldCount,
            Integer quota,
            Integer quotaLeft) {
    }

    /**
     * What a scope would reach if the sale opened now (SAL-A2).
     *
     * @param listTotal stock in reach at its list price, every card counted
     */
    public record ScopePreview(long listingCount, long shopCount, BigDecimal listTotal) {
    }
}
