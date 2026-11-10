package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What the seller's sales page reads (SAL-S1/S2/M1) [TCG-678]. */
public final class SellerSaleViews {

    private SellerSaleViews() {
    }

    /**
     * One of the shop's sales, with its status worked out at read time.
     *
     * @param pausedByPegasus true when Pegasus paused it; the shop cannot resume it then
     *                        and {@code pauseReason} says why
     * @param soldUnits       cards sold at this sale's price
     * @param listingCount    listings selling at this sale's price right now
     * @param items           the chosen listings of a LISTINGS sale; empty for SHOP and CATEGORY
     */
    public record SellerSale(
            long id,
            String name,
            String slug,
            ScopeType scopeType,
            List<Long> scopeIds,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscountPerUnit,
            List<CardCondition> conditions,
            BigDecimal minUnitPrice,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime endedAt,
            SaleStatus status,
            OffsetDateTime pausedAt,
            boolean pausedByPegasus,
            String pauseReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long soldUnits,
            long listingCount,
            List<SaleItem> items) {
    }

    /** @param statusCounts every status, zero included, for the tabs; ignores the status filter */
    public record SellerSalePage(PageResponse<SellerSale> page, Map<SaleStatus, Long> statusCounts) {
    }

    /**
     * A listing of a LISTINGS sale, and whether a Pegasus campaign also covers it (SAL-S2 warning).
     *
     * @param salePrice the price under this sale; null when the listing is no longer live
     * @param platform  the cheapest scheduled or running Pegasus campaign covering it; null when none
     * @param winner    which price the buyer gets while both run: THIS_SALE or PLATFORM
     *                  (a tie goes to Pegasus); null when there is no Pegasus campaign
     */
    public record SaleItem(
            long listingId,
            String productName,
            CardCondition condition,
            BigDecimal listPrice,
            BigDecimal salePrice,
            PlatformPrice platform,
            Winner winner) {
    }

    public enum Winner { THIS_SALE, PLATFORM }

    /** A Pegasus campaign's price for one of the shop's listings. */
    public record PlatformPrice(
            long saleId,
            String name,
            String slug,
            BigDecimal salePrice,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
    }

    /**
     * The KPI row (SAL-S1).
     *
     * @param activeSales     the shop's sales running now
     * @param listingsOnSale  the shop's listings selling at one of its own sale prices now
     * @param saleSales30d    what buyers paid for cards sold at the shop's sale prices, in orders
     *                        paid in the last 30 days
     * @param shopDiscount30d what those discounts cost the shop over the same orders
     */
    public record Summary(
            long activeSales,
            long listingsOnSale,
            BigDecimal saleSales30d,
            BigDecimal shopDiscount30d) {
    }

    /**
     * One of the shop's listings selling at a Pegasus campaign's price now (SAL-S1 card).
     *
     * @param priceLocked its price cannot go up until the campaign ends
     */
    public record PlatformListing(
            long listingId,
            String productName,
            CardCondition condition,
            BigDecimal listPrice,
            BigDecimal salePrice,
            long saleId,
            String saleName,
            String saleSlug,
            OffsetDateTime endsAt,
            boolean priceLocked) {
    }
}
