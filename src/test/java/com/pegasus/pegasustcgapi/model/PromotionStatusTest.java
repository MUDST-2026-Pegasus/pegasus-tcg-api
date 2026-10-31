package com.pegasus.pegasustcgapi.model;

import static com.pegasus.pegasustcgapi.support.PromotionFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.PromotionFixture;
import org.junit.jupiter.api.Test;

class PromotionStatusTest {

    @Test
    void activeInsideItsWindowWithUsesLeft() {
        assertThat(PromotionFixture.platform("OPEN").usage(10, 9).build().statusAt(NOW))
                .isEqualTo(PromotionStatus.ACTIVE);
        assertThat(PromotionFixture.platform("UNLIMITED").build().statusAt(NOW))
                .isEqualTo(PromotionStatus.ACTIVE);
    }

    @Test
    void scheduledBeforeStartAndActiveFromTheStartInstant() {
        Promotion p = PromotionFixture.platform("SOON").window(NOW.plusHours(1), NOW.plusDays(1)).build();

        assertThat(p.statusAt(NOW)).isEqualTo(PromotionStatus.SCHEDULED);
        assertThat(p.statusAt(NOW.plusHours(1))).isEqualTo(PromotionStatus.ACTIVE);
    }

    @Test
    void expiredFromTheEndInstant() {
        Promotion p = PromotionFixture.platform("LATE").window(NOW.minusDays(2), NOW).build();

        assertThat(p.statusAt(NOW.minusSeconds(1))).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(p.statusAt(NOW)).isEqualTo(PromotionStatus.EXPIRED);
    }

    @Test
    void usedUpOnceEveryUseIsTaken() {
        assertThat(PromotionFixture.platform("GONE").usage(5, 5).build().statusAt(NOW))
                .isEqualTo(PromotionStatus.USED_UP);
    }

    @Test
    void expiredBeatsPausedAndPausedBeatsScheduledOrUsedUp() {
        assertThat(PromotionFixture.platform("OLDPAUSE").window(NOW.minusDays(2), NOW.minusDays(1))
                .pausedAt(NOW.minusDays(2)).build().statusAt(NOW))
                .isEqualTo(PromotionStatus.EXPIRED);
        assertThat(PromotionFixture.platform("EARLYSTOP").window(NOW.plusDays(1), NOW.plusDays(2))
                .pausedAt(NOW).build().statusAt(NOW))
                .isEqualTo(PromotionStatus.PAUSED);
        assertThat(PromotionFixture.platform("FULLSTOP").usage(1, 1).pausedAt(NOW).build().statusAt(NOW))
                .isEqualTo(PromotionStatus.PAUSED);
    }

    @Test
    void coversByScope() {
        assertThat(PromotionFixture.platform("ALLCARDS").build().covers(1, 7)).isTrue();
        assertThat(PromotionFixture.platform("POKEMON").games(1L).build().covers(1, 7)).isTrue();
        assertThat(PromotionFixture.platform("POKEMON2").games(1L).build().covers(2, 7)).isFalse();
        assertThat(PromotionFixture.platform("SEALED").categories(7L, 8L).build().covers(2, 8)).isTrue();
        assertThat(PromotionFixture.platform("SEALED2").categories(7L).build().covers(1, 9)).isFalse();
    }
}
