package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Dashboard;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Listings;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.LowStockListing;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.MonthSummary;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.MonthlySales;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Period;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.RecentOrder;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Reviews;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Sales;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Wallet;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.SellerDashboardRepository;
import com.pegasus.pegasustcgapi.repository.SellerDashboardRepository.ReviewTotals;
import com.pegasus.pegasustcgapi.repository.SellerDashboardRepository.SalesTotals;
import com.pegasus.pegasustcgapi.repository.WalletRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seller's landing page: the month's sales against last month with a
 * twelve-month trend, what that month earned after commission and card cost,
 * orders per status, listings and low stock, reviews, the wallet and the newest
 * orders.
 *
 * <p>Sales follow the admin overview ({@link AdminMetricsService}): items only, of
 * sub-orders paid for in the month and still PAID through COMPLETED. Months are
 * cut in Bangkok time.
 */
@Service
public class SellerDashboardService {

    /**
     * Low stock is an ACTIVE listing with 1 to this many cards left. The web's
     * products page uses the same number ({@code LOW_STOCK_THRESHOLD} in
     * {@code products.rules.ts}); change both together.
     */
    public static final int LOW_STOCK_THRESHOLD = 2;

    static final int TREND_MONTHS = 12;
    static final int LOW_STOCK_ROWS = 5;
    static final int RECENT_ORDERS = 5;

    private final SellerDashboardRepository dashboard;
    private final WalletRepository wallets;
    private final SellerPort sellers;
    private final Clock clock;

    public SellerDashboardService(
            SellerDashboardRepository dashboard,
            WalletRepository wallets,
            SellerPort sellers,
            Clock clock) {

        this.dashboard = dashboard;
        this.wallets = wallets;
        this.sellers = sellers;
        this.clock = clock;
    }

    /**
     * @param month {@code YYYY-MM}; null or blank for the current month in Bangkok
     * @throws com.pegasus.pegasustcgapi.exception.NotFoundException when the account never started selling
     */
    @Transactional(readOnly = true)
    public Dashboard dashboard(AuthPrincipal principal, String month) {
        SellerProfile shop = sellers.requireProfile(principal.userId());
        long shopId = shop.id();

        YearMonth selected = parseMonth(month);
        OffsetDateTime from = startOf(selected);
        OffsetDateTime to = startOf(selected.plusMonths(1));
        OffsetDateTime previousFrom = startOf(selected.minusMonths(1));

        SalesTotals current = dashboard.salesBetween(shopId, from, to);
        SalesTotals previous = dashboard.salesBetween(shopId, previousFrom, from);
        BigDecimal cost = dashboard.costBetween(shopId, from, to).setScale(2, RoundingMode.HALF_UP);

        Sales sales = new Sales(current.items(), previous.items(), current.orders(), previous.orders(),
                monthlySales(shopId, selected, to));
        MonthSummary summary = new MonthSummary(current.grandTotal(), cost, current.commission(),
                current.grandTotal().subtract(current.commission()).subtract(cost));

        Listings listings = new Listings(
                dashboard.activeListings(shopId),
                dashboard.listingsPublishedBetween(shopId, from, to),
                LOW_STOCK_THRESHOLD,
                dashboard.lowStockCount(shopId, LOW_STOCK_THRESHOLD),
                dashboard.lowStockListings(shopId, LOW_STOCK_THRESHOLD, LOW_STOCK_ROWS).stream()
                        .map(row -> new LowStockListing(row.listingId(), row.productName(), row.lotLabel(),
                                row.conditionCode(), row.quantityAvailable(), row.quantityTotal()))
                        .toList());

        ReviewTotals reviewTotals = dashboard.reviews(shopId, from, to);
        Reviews reviews = new Reviews(
                reviewTotals.average() == null ? null : reviewTotals.average().setScale(1, RoundingMode.HALF_UP),
                reviewTotals.count(),
                reviewTotals.thisMonth(),
                reviewTotals.unreplied());

        List<RecentOrder> recent = dashboard.recentOrders(shopId, RECENT_ORDERS).stream()
                .map(row -> new RecentOrder(row.sellerOrderId(), row.sellerOrderNumber(), row.buyerUsername(),
                        row.buyerDisplayName(), row.firstItemName(), row.itemCount(), row.grandTotal(),
                        row.status(), row.createdAt()))
                .toList();

        return new Dashboard(
                new Period(selected.toString(), from, to),
                sales,
                summary,
                dashboard.orderCountsByStatus(shopId),
                listings,
                reviews,
                wallet(shop),
                recent);
    }

    /**
     * Withdrawable is the wallet's available balance less payouts already asked
     * for. Pending comes from escrow, not the wallet: held money sits in the
     * PLATFORM wallet until release, so the seller wallet's own pending bucket is
     * always zero (see {@link SellerDashboardRepository#heldInEscrow}).
     */
    private Wallet wallet(SellerProfile shop) {
        BigDecimal reserved = wallets.reservedForPayout(shop.id());
        BigDecimal withdrawable = wallets.findByUserId(shop.userId())
                .map(balance -> balance.available().subtract(reserved).max(BigDecimal.ZERO))
                .orElseGet(() -> BigDecimal.ZERO.setScale(2));
        return new Wallet(withdrawable, dashboard.heldInEscrow(shop.id()));
    }

    /** Twelve months ending with the selected one, months with no sales included as zero. */
    private List<MonthlySales> monthlySales(long shopId, YearMonth selected, OffsetDateTime to) {
        YearMonth first = selected.minusMonths(TREND_MONTHS - 1L);
        Map<LocalDate, BigDecimal> byMonth = dashboard.monthlySales(
                shopId, startOf(first), to, AdminMetricsService.ZONE);

        List<MonthlySales> trend = new ArrayList<>(TREND_MONTHS);
        for (YearMonth month = first; !month.isAfter(selected); month = month.plusMonths(1)) {
            trend.add(new MonthlySales(month.toString(),
                    byMonth.getOrDefault(month.atDay(1), BigDecimal.ZERO)));
        }
        return List.copyOf(trend);
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(clock.withZone(AdminMetricsService.ZONE));
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException invalid) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "month must look like 2026-10");
        }
    }

    private static OffsetDateTime startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(AdminMetricsService.ZONE).toOffsetDateTime();
    }
}
