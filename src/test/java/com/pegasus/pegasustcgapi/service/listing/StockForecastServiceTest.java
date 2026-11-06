package com.pegasus.pegasustcgapi.service.listing;

import static org.assertj.core.api.Assertions.assertThat;
import com.pegasus.pegasustcgapi.model.listing.StockForecastStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StockForecastServiceTest {
    private static final OffsetDateTime END = OffsetDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime START = END.minusDays(30);

    @Test
    void forecastsFromCompletedUnitsAndAvailableStock() {
        var result = StockForecastService.calculate(7, "Card", "ACTIVE", 3, 10, START, END);
        assertThat(result.status()).isEqualTo(StockForecastStatus.FORECAST_AVAILABLE);
        assertThat(result.daysRemaining()).isEqualTo(9);
        assertThat(result.estimatedStockoutDate()).isEqualTo(END.toLocalDate().plusDays(9));
        assertThat(result.completedSales30Days()).isEqualTo(10);
    }

    @Test
    void usesBangkokDateAndRoundsUpAtMinimumSales() {
        var end = OffsetDateTime.of(2026, 11, 5, 18, 0, 0, 0, ZoneOffset.UTC);
        var result = StockForecastService.calculate(7, "Card", "ACTIVE", 1, 2, end.minusDays(30), end);
        assertThat(result.status()).isEqualTo(StockForecastStatus.FORECAST_AVAILABLE);
        assertThat(result.daysRemaining()).isEqualTo(15);
        assertThat(result.estimatedStockoutDate()).isEqualTo(end.toLocalDate().plusDays(16));
    }

    @Test
    void doesNotInventDateWithoutEnoughSalesOrStock() {
        for (var sample : new int[][] {{4, 0}, {4, 1}, {0, 10}}) {
            var result = StockForecastService.calculate(7, "Card", "ACTIVE", sample[0], sample[1], START, END);
            assertThat(result.daysRemaining()).isNull();
            assertThat(result.estimatedStockoutDate()).isNull();
        }
        assertThat(StockForecastService.calculate(7, "Card", "ACTIVE", 4, 0, START, END).status())
                .isEqualTo(StockForecastStatus.NO_SALES);
        assertThat(StockForecastService.calculate(7, "Card", "ACTIVE", 4, 1, START, END).status())
                .isEqualTo(StockForecastStatus.INSUFFICIENT_DATA);
        assertThat(StockForecastService.calculate(7, "Card", "SOLD_OUT", 0, 10, START, END).status())
                .isEqualTo(StockForecastStatus.OUT_OF_STOCK);
    }
}
