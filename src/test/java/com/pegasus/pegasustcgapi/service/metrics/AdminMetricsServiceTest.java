package com.pegasus.pegasustcgapi.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.dto.metrics.AdminMetricsViews.GameShare;
import com.pegasus.pegasustcgapi.repository.metrics.AdminMetricsRepository.GameSales;
import com.pegasus.pegasustcgapi.repository.metrics.AdminMetricsRepository;
import com.pegasus.pegasustcgapi.repository.metrics.AdminMetricsRepository.ActiveListings;
import com.pegasus.pegasustcgapi.repository.metrics.AdminMetricsRepository.CommissionTotals;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminMetricsServiceTest {

    @Test
    void overviewIncludesLiveAuctionsAndRollingAuctionSales() {
        AdminMetricsRepository repository = mock(AdminMetricsRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
        when(repository.activeListings()).thenReturn(new ActiveListings(0, 0));
        when(repository.commissionBetween(any(), any()))
                .thenReturn(new CommissionTotals(BigDecimal.ZERO, BigDecimal.ZERO));
        when(repository.liveAuctionCount(any())).thenReturn(3L);
        when(repository.auctionSalesLast30Days(any())).thenReturn(new BigDecimal("8450.00"));

        var overview = new AdminMetricsService(repository, clock).overview("2026-11");

        assertThat(overview.stats().liveAuctions()).isEqualTo(3);
        assertThat(overview.stats().auctionSalesLast30Days()).isEqualByComparingTo("8450.00");
    }

    private static GameSales game(int id, String amount) {
        return new GameSales((short) id, "Game " + id, new BigDecimal(amount));
    }

    @Test
    @DisplayName("games past the top four fold into one others slice")
    void foldsTheTailIntoOthers() {
        List<GameShare> slices = AdminMetricsService.gameShare(List.of(
                game(1, "460"), game(2, "220"), game(3, "140"), game(4, "110"),
                game(5, "40"), game(6, "30")));

        assertThat(slices).hasSize(5);
        assertThat(slices.getLast().others()).isTrue();
        assertThat(slices.getLast().gameId()).isNull();
        assertThat(slices.getLast().amount()).isEqualByComparingTo("70");
        assertThat(slices).extracting(GameShare::percent).containsExactly(
                new BigDecimal("46.0"), new BigDecimal("22.0"), new BigDecimal("14.0"),
                new BigDecimal("11.0"), new BigDecimal("7.0"));
    }

    @Test
    @DisplayName("four games or fewer need no others slice")
    void noOthersWhenFewGames() {
        assertThat(AdminMetricsService.gameShare(List.of(game(1, "10"), game(2, "30"))))
                .noneMatch(GameShare::others)
                .hasSize(2);
    }

    @Test
    @DisplayName("no sales means no slices rather than a division by zero")
    void emptyMonth() {
        assertThat(AdminMetricsService.gameShare(List.of())).isEmpty();
    }
}
