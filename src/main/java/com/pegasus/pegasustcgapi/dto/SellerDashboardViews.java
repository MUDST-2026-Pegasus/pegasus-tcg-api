package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One seller's dashboard numbers, raw. Percent changes, month names and the
 * to-do list wording are left to the page: it has the values and the previous
 * month, and knows how it wants to say "nothing to compare with".
 */
public final class SellerDashboardViews {

    private SellerDashboardViews() {}

    /** @param month {@code YYYY-MM}; the range is half-open and cut in Bangkok time */
    public record Period(String month, OffsetDateTime from, OffsetDateTime to) {
    }

    /** @param month {@code YYYY-MM} */
    public record MonthlySales(String month, BigDecimal amount) {
    }

    /**
     * Items sold, shipping left out — the same base commission is worked out on and
     * the same definition as the admin's GMV.
     *
     * @param monthly twelve months ending with the selected one, oldest first
     */
    public record Sales(
            BigDecimal value,
            BigDecimal previous,
            long orderCount,
            long previousOrderCount,
            List<MonthlySales> monthly) {
    }

    /**
     * The month's result for the same sub-orders as {@link Sales}.
     *
     * @param revenue    grand total, shipping the buyers paid included
     * @param cost       the cards at the average cost frozen at checkout
     * @param commission at the rate frozen at checkout; charged once each order completes
     * @param net        revenue − commission − cost
     */
    public record MonthSummary(BigDecimal revenue, BigDecimal cost, BigDecimal commission, BigDecimal net) {
    }

    public record LowStockListing(
            long listingId,
            String productName,
            String lotLabel,
            String conditionCode,
            int quantityAvailable,
            int quantityTotal) {
    }

    /**
     * @param lowStockThreshold low stock is ACTIVE with 1 to this many cards left
     * @param lowStock          the fewest left first, at most five
     */
    public record Listings(
            long active,
            long publishedThisMonth,
            int lowStockThreshold,
            long lowStockCount,
            List<LowStockListing> lowStock) {
    }

    /** @param average one decimal place, null before the first review */
    public record Reviews(BigDecimal average, long count, long thisMonth, long unreplied) {
    }

    /**
     * @param withdrawable what can be paid out now, payouts already requested taken off
     * @param pending      still in escrow, waiting for buyers to confirm receipt
     */
    public record Wallet(BigDecimal withdrawable, BigDecimal pending) {
    }

    public record RecentOrder(
            long sellerOrderId,
            String sellerOrderNumber,
            String buyerUsername,
            String buyerDisplayName,
            String firstItemName,
            int itemCount,
            BigDecimal grandTotal,
            String status,
            OffsetDateTime createdAt) {
    }

    /** @param orders sub-orders of every age per status; statuses with none are left out */
    public record Dashboard(
            Period period,
            Sales sales,
            MonthSummary month,
            Map<String, Long> orders,
            Listings listings,
            Reviews reviews,
            Wallet wallet,
            List<RecentOrder> recentOrders) {
    }
}
