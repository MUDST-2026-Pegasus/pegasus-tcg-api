package com.pegasus.pegasustcgapi.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.DailyAmounts;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameProfit;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameStock;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.Totals;
import java.math.BigDecimal;
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
    void zeroPreviousWeekHasNoInfiniteOrInvalidChangePercent() {
        assertThat(AdminWeeklyStockProfitService.percentChange(new BigDecimal("20"), BigDecimal.ZERO)).isNull();
        assertThat(AdminWeeklyStockProfitService.percentChange(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }

    @Test
    void convertsIsoInstantToBangkokWeekAndMapsProductTypes() {
        OffsetDateTime from = OffsetDateTime.parse("2026-10-25T17:00:00Z");
        when(repository.completedTotals(from, from.plusWeeks(1), null, "SINGLE_CARD"))
                .thenReturn(new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.completedTotals(from.minusWeeks(1), from, null, "SINGLE_CARD"))
                .thenReturn(new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.dailyAmounts(from, from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(List.of());
        when(repository.activeStock(from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(0L);
        when(repository.activeStockByGame(from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(List.of());
        when(repository.profitByGame(from, from.plusWeeks(1), null, "SINGLE_CARD")).thenReturn(List.of());

        assertThat(service().report("2026-10-25T17:00:00Z", "2026-11-01T16:59:59Z", null, "single_card")
                .startDate()).isEqualTo(LocalDate.parse("2026-10-26"));
        verify(repository).activeStock(from.plusWeeks(1), null, "SINGLE_CARD");
    }

    @Test
    void rejectsAWeekThatDoesNotStartMondayAndUnknownFilters() {
        assertThatThrownBy(() -> service().report("2026-10-27", "2026-11-02", null, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service().report("2026-10-26", "2026-11-01", null, "accessory"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void exportsCsvAndAValidXlsxPackage() {
        var report = new com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report(
                LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-01"),
                new com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Summary(
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, 3, 2, null), List.of(), List.of());
        var csv = service().export(report, "csv");
        var xlsx = service().export(report, "xlsx");
        assertThat(csv.filename()).isEqualTo("pegasus_weekly_report_2026_44.csv");
        assertThat(new String(csv.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"summary\",\"\",\"\",\"\",\"\",\"10\",\"1\",\"1\",\"3\"");
        assertThat(xlsx.filename()).endsWith(".xlsx");
        assertThat(xlsx.bytes()).startsWith(new byte[] { 0x50, 0x4b });
    }
}
