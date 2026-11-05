package com.pegasus.pegasustcgapi.dto.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Admin weekly sales, profit and current listing stock. */
public final class WeeklyStockProfitViews {

    private WeeklyStockProfitViews() {}

    public record Summary(BigDecimal gmv, BigDecimal commission, BigDecimal netProfit,
            long remainingStock, long stockDepleted, BigDecimal wowChangePercent) {}

    public record DailySales(LocalDate date, BigDecimal singles, BigDecimal sealed) {}

    public record GameBreakdown(String game, long remainingStock, BigDecimal stockPercent,
            BigDecimal profit, BigDecimal profitPercent) {}

    public record Report(LocalDate startDate, LocalDate endDate, Summary summary,
            List<DailySales> chartData, List<GameBreakdown> breakdownByGame) {}
}
