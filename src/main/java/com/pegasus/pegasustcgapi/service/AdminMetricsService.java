package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.ActiveListingsStat;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.CommissionStat;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.CountStat;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.GameShare;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.MoneyStat;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.MonthlySales;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.Overview;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.Period;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.Stats;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.TopSeller;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.repository.AdminMetricsRepository;
import com.pegasus.pegasustcgapi.repository.AdminMetricsRepository.ActiveListings;
import com.pegasus.pegasustcgapi.repository.AdminMetricsRepository.CommissionTotals;
import com.pegasus.pegasustcgapi.repository.AdminMetricsRepository.GameSales;
import com.pegasus.pegasustcgapi.repository.AdminMetricsRepository.MonthlyKindSales;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin overview: a month's sales, new sellers, live listings and commission,
 * with the six-month trend, the month's best sellers and its split by game.
 *
 * <p>What each number means:
 * <ul>
 *   <li><b>Sales (GMV)</b> — {@code seller_order.items_subtotal}, shipping left out,
 *       of sub-orders paid for in the month ({@code sales_order.paid_at}) and still
 *       PAID, PREPARING, SHIPPED, DELIVERED or COMPLETED.</li>
 *   <li><b>New sellers</b> — profiles VERIFIED whose {@code verified_at} falls in the month.</li>
 *   <li><b>Active listings</b> — ACTIVE, not deleted, from sellers who are not
 *       suspended, as of now.</li>
 *   <li><b>Commission</b> — {@code seller_order.commission_amount} of sub-orders
 *       COMPLETED in the month; the rate is the one frozen at checkout.</li>
 *   <li><b>Singles / sealed</b> — order lines by {@code catalog_product.product_type}:
 *       SINGLE_CARD is a single; packs, boxes, ETBs, starter decks and bundles are
 *       sealed; accessories and other goods are in neither.</li>
 * </ul>
 * Months and weeks are cut in Bangkok time; weeks start on Monday.
 */
@Service
public class AdminMetricsService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    static final int SPARK_WEEKS = 8;
    static final int TREND_MONTHS = 6;
    static final int TOP_SELLERS = 5;
    /** Games past this many are folded into one "others" slice. */
    static final int GAME_SLICES = 4;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AdminMetricsRepository metrics;
    private final Clock clock;

    public AdminMetricsService(AdminMetricsRepository metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @param month {@code YYYY-MM}; null or blank for the current month in Bangkok */
    @Transactional(readOnly = true)
    public Overview overview(String month) {
        YearMonth selected = parseMonth(month);
        OffsetDateTime from = startOf(selected);
        OffsetDateTime to = startOf(selected.plusMonths(1));
        OffsetDateTime previousFrom = startOf(selected.minusMonths(1));

        List<LocalDate> weeks = sparkWeeks(to);
        OffsetDateTime sparkFrom = weeks.getFirst().atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime sparkTo = weeks.getLast().plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime();

        MoneyStat gmv = new MoneyStat(
                metrics.salesBetween(from, to),
                metrics.salesBetween(previousFrom, from),
                series(weeks, metrics.weeklySales(sparkFrom, sparkTo, ZONE), BigDecimal.ZERO));

        CountStat newSellers = new CountStat(
                metrics.sellersVerifiedBetween(from, to),
                metrics.sellersVerifiedBetween(previousFrom, from),
                series(weeks, metrics.weeklyVerifiedSellers(sparkFrom, sparkTo, ZONE), 0L));

        ActiveListings active = metrics.activeListings();

        CommissionTotals current = metrics.commissionBetween(from, to);
        CommissionStat commission = new CommissionStat(
                current.commission(),
                metrics.commissionBetween(previousFrom, from).commission(),
                series(weeks, metrics.weeklyCommission(sparkFrom, sparkTo, ZONE), BigDecimal.ZERO),
                percentOf(current.commission(), current.itemsSubtotal(), 2));

        MoneyStat promotionCost = new MoneyStat(
                metrics.promotionCostBetween(from, to),
                metrics.promotionCostBetween(previousFrom, from),
                series(weeks, metrics.weeklyPromotionCost(sparkFrom, sparkTo, ZONE), BigDecimal.ZERO));

        Stats stats = new Stats(gmv, newSellers,
                new ActiveListingsStat(active.listings(), active.sellers()), commission, promotionCost);

        return new Overview(
                new Period(selected.toString(), from, to, previousFrom, from),
                stats,
                monthlySales(selected, to),
                metrics.topSellers(from, to, TOP_SELLERS).stream()
                        .map(s -> new TopSeller(s.sellerProfileId(), s.username(), s.displayName(), s.amount()))
                        .toList(),
                gameShare(metrics.salesByGame(from, to)));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(clock.withZone(ZONE));
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException invalid) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "month must look like 2026-10");
        }
    }

    private static OffsetDateTime startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    /**
     * The Mondays of the eight weeks ending with the one the period ends in — or
     * with this week, when the period has not ended yet.
     */
    private List<LocalDate> sparkWeeks(OffsetDateTime periodEnd) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime end = periodEnd.isBefore(now) ? periodEnd.minusNanos(1) : now;
        LocalDate lastMonday = end.atZoneSameInstant(ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<LocalDate> weeks = new ArrayList<>(SPARK_WEEKS);
        for (int i = SPARK_WEEKS - 1; i >= 0; i--) {
            weeks.add(lastMonday.minusWeeks(i));
        }
        return weeks;
    }

    private static <T> List<T> series(List<LocalDate> weeks, Map<LocalDate, T> byWeek, T none) {
        return weeks.stream().map(week -> byWeek.getOrDefault(week, none)).toList();
    }

    /** Six months ending with the selected one, months with no sales included as zero. */
    private List<MonthlySales> monthlySales(YearMonth selected, OffsetDateTime to) {
        YearMonth first = selected.minusMonths(TREND_MONTHS - 1L);
        Map<YearMonth, Map<String, BigDecimal>> byMonth = new HashMap<>();
        for (MonthlyKindSales row : metrics.monthlySalesByKind(startOf(first), to, ZONE)) {
            byMonth.computeIfAbsent(YearMonth.from(row.month()), m -> new HashMap<>())
                    .merge(row.kind(), row.amount(), BigDecimal::add);
        }

        List<MonthlySales> trend = new ArrayList<>(TREND_MONTHS);
        for (YearMonth month = first; !month.isAfter(selected); month = month.plusMonths(1)) {
            Map<String, BigDecimal> kinds = byMonth.getOrDefault(month, Map.of());
            trend.add(new MonthlySales(month.toString(),
                    kinds.getOrDefault("SINGLES", BigDecimal.ZERO),
                    kinds.getOrDefault("SEALED", BigDecimal.ZERO)));
        }
        return trend;
    }

    /** The biggest games by name, the rest as one slice, so the chart stays readable. */
    static List<GameShare> gameShare(List<GameSales> games) {
        BigDecimal total = games.stream().map(GameSales::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return List.of();
        }

        Function<BigDecimal, BigDecimal> percent = amount -> percentOf(amount, total, 1);
        List<GameShare> slices = new ArrayList<>();
        games.stream().limit(GAME_SLICES).forEach(game -> slices.add(new GameShare(
                game.gameId(), game.gameName(), game.amount(), percent.apply(game.amount()), false)));

        BigDecimal rest = games.stream().skip(GAME_SLICES)
                .map(GameSales::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rest.signum() > 0) {
            slices.add(new GameShare(null, null, rest, percent.apply(rest), true));
        }
        return List.copyOf(slices);
    }

    private static BigDecimal percentOf(BigDecimal part, BigDecimal whole, int scale) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, scale, RoundingMode.HALF_UP);
    }
}
