package com.pegasus.pegasustcgapi.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs the report's aggregate SQL against the real migrated Postgres schema. */
class AdminWeeklyStockProfitIntegrationTest extends PostgresIntegrationTest {

    @Autowired AdminWeeklyStockProfitService reportService;

    @Test
    void emptyFutureWeekReturnsZeroTotalsAndSevenDailyPoints() {
        var report = reportService.report("2100-01-04", "2100-01-10", null, null);

        assertThat(report.summary().gmv()).isZero();
        assertThat(report.summary().commission()).isZero();
        assertThat(report.summary().netProfit()).isZero();
        assertThat(report.summary().stockDepleted()).isZero();
        assertThat(report.summary().wowChangePercent()).isNull();
        assertThat(report.chartData()).hasSize(7);
        assertThat(report.chartData()).allSatisfy(day -> {
            assertThat(day.singles()).isZero();
            assertThat(day.sealed()).isZero();
        });
    }
}
