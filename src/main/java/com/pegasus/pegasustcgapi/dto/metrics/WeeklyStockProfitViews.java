package com.pegasus.pegasustcgapi.dto.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Admin weekly sales, profit and current listing stock. */
public final class WeeklyStockProfitViews {

    private WeeklyStockProfitViews() {}

    public record ReportMeta(
            LocalDate startDate,
            LocalDate endDate,
            String timezone,
            String gameCategory,
            String productType,
            BigDecimal commissionRatePercent) {}

    public record Summary(
            BigDecimal gmv,
            BigDecimal weeklyGmv,
            BigDecimal commission,
            BigDecimal commissionCollected,
            BigDecimal netProfit,
            long initialStock,
            long soldCount,
            long remainingStock,
            long stockDepleted,
            BigDecimal wowChangePercent) {

        public Summary(BigDecimal gmv, BigDecimal commission, BigDecimal netProfit,
                long remainingStock, long stockDepleted, BigDecimal wowChangePercent) {
            this(gmv, gmv, commission, commission, netProfit,
                    remainingStock + stockDepleted, stockDepleted,
                    remainingStock, stockDepleted, wowChangePercent);
        }
    }

    public record DailySales(LocalDate date, BigDecimal singles, BigDecimal sealed, BigDecimal other) {}

    public record GameBreakdown(String game, long remainingStock, BigDecimal stockPercent,
            BigDecimal profit, BigDecimal profitPercent) {}

    public record GameDistributionItem(
            String game,
            String gameSlug,
            BigDecimal gmv,
            BigDecimal netProfit,
            long remainingStock,
            BigDecimal percentage) {}

    public record BreakdownRow(
            String game,
            String gameSlug,
            long initialStock,
            long soldCount,
            long remainingStock,
            long singleCards,
            long sealedBoxes,
            long otherItems,
            BigDecimal gmv,
            BigDecimal commission,
            BigDecimal netProfit,
            String status,
            String statusLabel) {}

    public record BreakdownTotals(
            long initialStock,
            long soldCount,
            long remainingStock,
            long singleCards,
            long sealedBoxes,
            long otherItems,
            BigDecimal gmv,
            BigDecimal weeklyGmv,
            BigDecimal commission,
            BigDecimal commissionCollected,
            BigDecimal netProfit) {

        public static BreakdownTotals zero() {
            return new BreakdownTotals(0, 0, 0, 0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO);
        }
    }

    public record Report(
            LocalDate startDate,
            LocalDate endDate,
            boolean hasData,
            ReportMeta meta,
            Summary summary,
            List<DailySales> chartData,
            List<GameBreakdown> breakdownByGame,
            List<GameDistributionItem> gameDistribution,
            List<BreakdownRow> breakdownTable,
            BreakdownTotals totals) {

        public Report(LocalDate startDate, LocalDate endDate, Summary summary,
                List<DailySales> chartData, List<GameBreakdown> breakdownByGame) {
            this(startDate, endDate,
                    summary != null && (summary.gmv().signum() > 0 || summary.stockDepleted() > 0),
                    new ReportMeta(startDate, endDate, "Asia/Bangkok", null, null, new BigDecimal("3.0")),
                    summary,
                    chartData,
                    breakdownByGame,
                    List.of(),
                    List.of(),
                    summary == null ? BreakdownTotals.zero() : new BreakdownTotals(
                            summary.initialStock(), summary.soldCount(), summary.remainingStock(),
                            summary.remainingStock(), 0, 0,
                            summary.gmv(), summary.weeklyGmv(),
                            summary.commission(), summary.commissionCollected(),
                            summary.netProfit()));
        }
    }
}
