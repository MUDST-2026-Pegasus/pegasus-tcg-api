package com.pegasus.pegasustcgapi.model.sale;

import static com.pegasus.pegasustcgapi.support.PriceSaleFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.PriceSaleFixture;
import org.junit.jupiter.api.Test;

class SaleStatusTest {

    @Test
    void activeInsideItsWindow() {
        assertThat(PriceSaleFixture.platform().build().statusAt(NOW, false)).isEqualTo(SaleStatus.ACTIVE);
    }

    @Test
    void scheduledBeforeStartAndActiveFromTheStartInstant() {
        PriceSale s = PriceSaleFixture.platform().window(NOW.plusHours(1), NOW.plusDays(1)).build();

        assertThat(s.statusAt(NOW, false)).isEqualTo(SaleStatus.SCHEDULED);
        assertThat(s.statusAt(NOW.plusHours(1), false)).isEqualTo(SaleStatus.ACTIVE);
    }

    @Test
    void endedFromThePlannedEndOrWhenEndedEarly() {
        PriceSale planned = PriceSaleFixture.platform().window(NOW.minusDays(1), NOW).build();
        PriceSale early = PriceSaleFixture.platform().endedAt(NOW.minusMinutes(1)).build();

        assertThat(planned.statusAt(NOW.minusSeconds(1), false)).isEqualTo(SaleStatus.ACTIVE);
        assertThat(planned.statusAt(NOW, false)).isEqualTo(SaleStatus.ENDED);
        assertThat(early.statusAt(NOW, false)).isEqualTo(SaleStatus.ENDED);
        assertThat(early.effectiveEnd()).isEqualTo(NOW.minusMinutes(1));
    }

    @Test
    void pausedWinsOverScheduledButNotOverEnded() {
        assertThat(PriceSaleFixture.platform().window(NOW.plusHours(1), NOW.plusDays(1)).pausedAt(NOW).build()
                .statusAt(NOW, false)).isEqualTo(SaleStatus.PAUSED);
        assertThat(PriceSaleFixture.platform().window(NOW.minusDays(1), NOW).pausedAt(NOW.minusHours(1)).build()
                .statusAt(NOW, false)).isEqualTo(SaleStatus.ENDED);
    }

    @Test
    void budgetExhaustedOnceTheSubsidyReachesTheCap() {
        assertThat(PriceSaleFixture.platform().budget("1000", "999.99").build().statusAt(NOW, false))
                .isEqualTo(SaleStatus.ACTIVE);
        assertThat(PriceSaleFixture.platform().budget("1000", "1000").build().statusAt(NOW, false))
                .isEqualTo(SaleStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void soldOutWhenTheCallerSaysEveryQuotaIsUsed() {
        assertThat(PriceSaleFixture.flash(5).build().statusAt(NOW, true)).isEqualTo(SaleStatus.SOLD_OUT);
    }

    @Test
    void onlyScheduledAndActiveLockPrices() {
        assertThat(SaleStatus.SCHEDULED.locksPrice()).isTrue();
        assertThat(SaleStatus.ACTIVE.locksPrice()).isTrue();
        assertThat(SaleStatus.PAUSED.locksPrice()).isFalse();
        assertThat(SaleStatus.ENDED.locksPrice()).isFalse();
        assertThat(SaleStatus.SOLD_OUT.locksPrice()).isFalse();
        assertThat(SaleStatus.BUDGET_EXHAUSTED.locksPrice()).isFalse();
    }
}
