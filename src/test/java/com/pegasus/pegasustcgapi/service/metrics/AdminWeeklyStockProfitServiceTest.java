package com.pegasus.pegasustcgapi.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.BreakdownRow;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.GameDistributionItem;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.DailyAmounts;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameBreakdownStat;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameProfit;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameStock;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.Totals;
import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitService.OrderSample;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminWeeklyStockProfitServiceTest {

    @Mock AdminWeeklyStockProfitRepository repository;

    private AdminWeeklyStockProfitService service() {
        return new AdminWeeklyStockProfitService(repository);
    }

    @Test
    void calculatesCompletedProfitAndSevenBangkokDays() {
        OffsetDateTime from = OffsetDateTime.parse("2026-10-26T00:00:00+07:00");
        OffsetDateTime to = from.plusWeeks(1);
        when(repository.completedTotals(from, to, "pokemon", null))
                .thenReturn(new Totals(new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("350")));
        when(repository.completedTotals(from.minusWeeks(1), from, "pokemon", null))
                .thenReturn(new Totals(new BigDecimal("500"), new BigDecimal("50"), new BigDecimal("100")));
        when(repository.dailyAmounts(from, to, "pokemon", null)).thenReturn(List.of(
                new DailyAmounts(LocalDate.parse("2026-10-26"), new BigDecimal("100"), BigDecimal.ZERO)));
        when(repository.activeStock(to, "pokemon", null)).thenReturn(7L);
        when(repository.completedQuantity(from, to, "pokemon", null)).thenReturn(2L);
        when(repository.activeStockByGame(to, "pokemon", null)).thenReturn(List.of(
                new GameStock("pokemon", "Pokémon", 7)));
        when(repository.profitByGame(from, to, "pokemon", null)).thenReturn(List.of(
                new GameProfit("pokemon", "Pokémon", new BigDecimal("350"))));

        var report = service().report("2026-10-26", "2026-11-01", "pokemon", null);

        assertThat(report.hasData()).isTrue();
        assertThat(report.summary().gmv()).isEqualByComparingTo("1000");
        assertThat(report.summary().commission()).isEqualByComparingTo("100");
        assertThat(report.summary().netProfit()).isEqualByComparingTo("350");
        assertThat(report.summary().remainingStock()).isEqualTo(7);
        assertThat(report.summary().stockDepleted()).isEqualTo(2);
        assertThat(report.summary().wowChangePercent()).isEqualByComparingTo("100.00");
        assertThat(report.chartData()).hasSize(7);
        assertThat(report.chartData().getFirst().singles()).isEqualByComparingTo("100");
        assertThat(report.chartData().getLast().sealed()).isEqualByComparingTo("0");
        assertThat(report.breakdownByGame()).singleElement().satisfies(game -> {
            assertThat(game.game()).isEqualTo("Pokémon");
            assertThat(game.remainingStock()).isEqualTo(7);
        });
    }

    @Test
    void filtersOnlyCompletedOrdersAndCalculatesThreePercentCommissionAndNetProfit() {
        LocalDate monday = LocalDate.parse("2026-08-03");
        List<OrderSample> orders = List.of(
                new OrderSample("COMPLETED", new BigDecimal("239960.00"), OffsetDateTime.parse("2026-08-04T10:00:00+07:00")),
                new OrderSample("COMPLETED", new BigDecimal("102840.00"), OffsetDateTime.parse("2026-08-06T14:30:00+07:00")),
                new OrderSample("COMPLETED", new BigDecimal("85700.00"), OffsetDateTime.parse("2026-08-08T19:15:00+07:00")),
                new OrderSample("CANCELLED", new BigDecimal("50000.00"), OffsetDateTime.parse("2026-08-05T12:00:00+07:00")),
                new OrderSample("PENDING", new BigDecimal("75000.00"), OffsetDateTime.parse("2026-08-05T15:00:00+07:00")),
                new OrderSample("PENDING_PAYMENT", new BigDecimal("30000.00"), OffsetDateTime.parse("2026-08-05T16:00:00+07:00")),
                new OrderSample("DISPUTED", new BigDecimal("90000.00"), OffsetDateTime.parse("2026-08-07T11:00:00+07:00")),
                new OrderSample("RETURN_REQUESTED", new BigDecimal("40000.00"), OffsetDateTime.parse("2026-08-07T12:00:00+07:00")),
                new OrderSample("REFUNDED", new BigDecimal("20000.00"), OffsetDateTime.parse("2026-08-07T13:00:00+07:00"))
        );

        Totals metrics = AdminWeeklyStockProfitService.calculateCompletedMetrics(orders, monday);

        assertThat(metrics.gmv()).isEqualByComparingTo("428500.00");
        assertThat(metrics.commission()).isEqualByComparingTo("12855.00");
        assertThat(metrics.netProfit()).isEqualByComparingTo("12855.00");
        assertThat(AdminWeeklyStockProfitService.calculateCommission(new BigDecimal("428500")))
                .isEqualByComparingTo("12855.00");
        assertThat(AdminWeeklyStockProfitService.calculatePlatformNetProfit(new BigDecimal("428500")))
                .isEqualByComparingTo("12855.00");
    }

    @Test
    void verifiesStockEquationAndBreakdownTableAcrossGamesAndProductTypes() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-03T00:00:00+07:00");
        OffsetDateTime to = from.plusWeeks(1);

        when(repository.completedTotals(from, to, null, null))
                .thenReturn(new Totals(new BigDecimal("428500.00"), new BigDecimal("12855.00"), new BigDecimal("12855.00")));
        when(repository.completedTotals(from.minusWeeks(1), from, null, null))
                .thenReturn(new Totals(new BigDecimal("362500.00"), new BigDecimal("10875.00"), new BigDecimal("10875.00")));
        when(repository.dailyAmounts(from, to, null, null)).thenReturn(List.of(
                new DailyAmounts(LocalDate.parse("2026-08-03"), new BigDecimal("40000.00"), new BigDecimal("20000.00")),
                new DailyAmounts(LocalDate.parse("2026-08-09"), new BigDecimal("250000.00"), new BigDecimal("118500.00"))));
        when(repository.activeStock(to, null, null)).thenReturn(6184L);
        when(repository.completedQuantity(from, to, null, null)).thenReturn(1470L);
        when(repository.activeStockByGame(to, null, null)).thenReturn(List.of(
                new GameStock("pokemon", "Pokémon", 3464L),
                new GameStock("one-piece", "One Piece", 1484L),
                new GameStock("other-tcg", "Other TCG", 1236L)));
        when(repository.profitByGame(from, to, null, null)).thenReturn(List.of(
                new GameProfit("pokemon", "Pokémon", new BigDecimal("7198.80")),
                new GameProfit("one-piece", "One Piece", new BigDecimal("3085.20")),
                new GameProfit("other-tcg", "Other TCG", new BigDecimal("2571.00"))));
        when(repository.gameBreakdownRows(from, to, null, null)).thenReturn(List.of(
                new GameBreakdownStat("pokemon", "Pokémon", 3464L, 2464L, 1000L, 790L,
                        new BigDecimal("239960.00"), new BigDecimal("7198.80"), new BigDecimal("7198.80")),
                new GameBreakdownStat("one-piece", "One Piece", 1484L, 984L, 500L, 416L,
                        new BigDecimal("102840.00"), new BigDecimal("3085.20"), new BigDecimal("3085.20")),
                new GameBreakdownStat("other-tcg", "Other TCG", 1236L, 836L, 400L, 264L,
                        new BigDecimal("85700.00"), new BigDecimal("2571.00"), new BigDecimal("2571.00"))));

        var report = service().report("2026-08-03", "2026-08-09", null, null);

        assertThat(report.hasData()).isTrue();
        assertThat(report.breakdownTable()).hasSize(3);
        for (BreakdownRow row : report.breakdownTable()) {
            assertThat(AdminWeeklyStockProfitService.calculateRemainingStock(row.initialStock(), row.soldCount()))
                    .isEqualTo(row.remainingStock());
            assertThat(row.singleCards() + row.sealedBoxes()).isEqualTo(row.remainingStock());
            assertThat(row.netProfit()).isEqualByComparingTo(AdminWeeklyStockProfitService.calculateCommission(row.gmv()));
        }

        assertThat(report.breakdownTable()).extracting(BreakdownRow::game)
                .containsExactly("Pokémon", "One Piece", "Other TCG");
        assertThat(report.breakdownTable()).extracting(BreakdownRow::status)
                .containsExactly("NORMAL", "FAST_MOVING", "LOW_STOCK");

        assertThat(report.totals().initialStock()).isEqualTo(7654L);
        assertThat(report.totals().soldCount()).isEqualTo(1470L);
        assertThat(report.totals().remainingStock()).isEqualTo(6184L);
        assertThat(report.totals().weeklyGmv()).isEqualByComparingTo("428500.00");
        assertThat(report.totals().commissionCollected()).isEqualByComparingTo("12855.00");
        assertThat(report.totals().netProfit()).isEqualByComparingTo("12855.00");

        assertThat(report.summary().initialStock()).isEqualTo(report.totals().initialStock());
        assertThat(report.summary().soldCount()).isEqualTo(report.totals().soldCount());
        assertThat(report.summary().remainingStock()).isEqualTo(report.totals().remainingStock());
        assertThat(report.summary().weeklyGmv()).isEqualByComparingTo(report.totals().weeklyGmv());
        assertThat(report.summary().netProfit()).isEqualByComparingTo(report.totals().netProfit());
        assertThat(report.summary().wowChangePercent()).isEqualByComparingTo("18.2");

        assertThat(report.gameDistribution()).extracting(GameDistributionItem::game)
                .containsExactly("Pokémon", "One Piece", "Other TCG");
        assertThat(report.gameDistribution()).extracting(GameDistributionItem::percentage)
                .containsExactly(new BigDecimal("56.00"), new BigDecimal("24.00"), new BigDecimal("20.00"));
    }

    @Test
    void calculatesWowChangePercentAndHandlesZeroPreviousWeekWithoutError() {
        // Normal week-over-week growth: 10,875 -> 12,855 = +18.2%
        assertThat(AdminWeeklyStockProfitService.percentChange(
                new BigDecimal("12855"), new BigDecimal("10875")))
                .isEqualByComparingTo("18.2");

        // Edge case: previous week is 0 (divide by zero guard) -> 0.0% without NaN/Infinity
        assertThat(AdminWeeklyStockProfitService.percentChange(new BigDecimal("20"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.0");
        assertThat(AdminWeeklyStockProfitService.percentChange(BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo("0.0");
        assertThat(AdminWeeklyStockProfitService.percentChange(new BigDecimal("100"), null))
                .isEqualByComparingTo("0.0");
    }

    @Test
    void respectsBangkokTimezoneBoundaryBetweenSundayNightAndMondayMidnight() {
        LocalDate weekAug03 = LocalDate.parse("2026-08-03");
        LocalDate weekAug10 = LocalDate.parse("2026-08-10");

        // Sunday 2026-08-09 23:59:59+07:00 (2026-08-09T16:59:59Z) -> belongs to week 2026-08-03..09
        OffsetDateTime sundayLastSecond = OffsetDateTime.parse("2026-08-09T23:59:59+07:00");
        // Monday 2026-08-10 00:00:00+07:00 (2026-08-09T17:00:00Z) -> belongs to next week 2026-08-10..16
        OffsetDateTime mondayFirstSecond = OffsetDateTime.parse("2026-08-10T00:00:00+07:00");

        assertThat(AdminWeeklyStockProfitService.isWithinBangkokWeek(sundayLastSecond, weekAug03)).isTrue();
        assertThat(AdminWeeklyStockProfitService.isWithinBangkokWeek(mondayFirstSecond, weekAug03)).isFalse();
        assertThat(AdminWeeklyStockProfitService.isWithinBangkokWeek(mondayFirstSecond, weekAug10)).isTrue();

        List<OrderSample> boundaryOrders = List.of(
                new OrderSample("COMPLETED", new BigDecimal("1000.00"), sundayLastSecond),
                new OrderSample("COMPLETED", new BigDecimal("2000.00"), mondayFirstSecond));

        assertThat(AdminWeeklyStockProfitService.calculateCompletedMetrics(boundaryOrders, weekAug03).gmv())
                .isEqualByComparingTo("1000.00");
        assertThat(AdminWeeklyStockProfitService.calculateCompletedMetrics(boundaryOrders, weekAug10).gmv())
                .isEqualByComparingTo("2000.00");
    }

    @Test
    void convertsIsoInstantToBangkokWeekAndMapsProductTypes() {
        OffsetDateTime from = OffsetDateTime.parse("2026-10-26T00:00:00+07:00");
        when(repository.completedTotals(from, from.plusWeeks(1), null, "SINGLE_CARD"))
                .thenReturn(new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.completedTotals(from.minusWeeks(1), from, null, "SINGLE_CARD"))
                .thenReturn(new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.dailyAmounts(from, from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(List.of());
        when(repository.activeStock(from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(0L);
        when(repository.activeStockByGame(from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(List.of());
        when(repository.profitByGame(from, from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(List.of());

        var report = service().report("2026-10-25T17:00:00Z", "2026-11-01T16:59:59Z", null, "single_card");
        assertThat(report.startDate()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(report.hasData()).isFalse();
        assertThat(report.summary().wowChangePercent()).isEqualByComparingTo("0.0");
        verify(repository).activeStock(from.plusWeeks(1), null, "SINGLE_CARD");
    }

    @Test
    void rejectsAWeekThatDoesNotStartMondayAndUnknownFilters() {
        assertThatThrownBy(() -> service().report("2026-10-27", "2026-11-02", null, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().report("2026-08-10", "2026-08-03", null, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().report("invalid-date", "2026-08-09", null, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().report("2026-10-26", "2026-11-01", null, "accessory"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> AdminWeeklyStockProfitService.calculateRemainingStock(100, 150))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void exportsCsvAndAValidXlsxPackage() {
        var report = new com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report(
                LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-01"),
                new com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Summary(
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, 3, 2, new BigDecimal("0.0")),
                List.of(), List.of());
        var csv = service().export(report, "csv");
        var xlsx = service().export(report, "xlsx");
        assertThat(csv.filename()).isEqualTo("pegasus_weekly_report_2026_44.csv");
        assertThat(new String(csv.bytes(), StandardCharsets.UTF_8))
                .contains("\"summary\",\"\",\"\",\"\",\"\",\"10\",\"1\",\"1\",\"3\"");
        assertThat(xlsx.filename()).endsWith(".xlsx");
        assertThat(xlsx.bytes()).startsWith(new byte[] { 0x50, 0x4b });
    }
}
