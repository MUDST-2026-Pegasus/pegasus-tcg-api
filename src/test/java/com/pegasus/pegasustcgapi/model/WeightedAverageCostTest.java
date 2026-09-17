package com.pegasus.pegasustcgapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeightedAverageCostTest {

    @Test
    @DisplayName("cards bought at two costs average by quantity")
    void receivingAveragesByQuantity() {
        WeightedAverageCost cost = WeightedAverageCost.EMPTY
                .receive(3, new BigDecimal("900.00"))
                .receive(2, new BigDecimal("1050.00"));

        assertThat(cost.quantity()).isEqualTo(5);
        assertThat(cost.totalCost()).isEqualByComparingTo("4800.00");
        assertThat(cost.averageUnitCost()).isEqualByComparingTo("960.0000");
    }

    @Test
    @DisplayName("a card leaves at the average, and the average does not move")
    void issuingKeepsTheAverage() {
        WeightedAverageCost cost = WeightedAverageCost.EMPTY.receive(3, new BigDecimal("900.00"));

        WeightedAverageCost.Issued issued = cost.issue(1);

        assertThat(issued.unitCost()).isEqualByComparingTo("900.00");
        assertThat(issued.after().quantity()).isEqualTo(2);
        assertThat(issued.after().totalCost()).isEqualByComparingTo("1800.00");
        assertThat(issued.after().averageUnitCost()).isEqualByComparingTo("900.0000");
    }

    @Test
    @DisplayName("the last card out leaves the total at exactly zero, so the next purchase inherits no rounding")
    void lastCardOutResetsTheTotal() {
        WeightedAverageCost cost = WeightedAverageCost.EMPTY
                .receive(1, new BigDecimal("333.33"))
                .receive(1, new BigDecimal("333.33"))
                .receive(1, new BigDecimal("333.34"));
        assertThat(cost.averageUnitCost()).isEqualByComparingTo("333.3333");

        WeightedAverageCost empty = cost.issue(1).after().issue(1).after().issue(1).after();

        assertThat(empty.quantity()).isZero();
        assertThat(empty.totalCost()).isEqualByComparingTo("0.00");
        assertThat(empty.receive(1, new BigDecimal("500.00")).averageUnitCost()).isEqualByComparingTo("500.0000");
    }

    @Test
    @DisplayName("a card given for free is stock at cost zero, and pulls the average down")
    void freeCardCountsAtZero() {
        WeightedAverageCost cost = WeightedAverageCost.EMPTY
                .receive(1, new BigDecimal("1000.00"))
                .receive(1, BigDecimal.ZERO);

        assertThat(cost.averageUnitCost()).isEqualByComparingTo("500.0000");
    }

    @Test
    @DisplayName("more cards leaving than the books hold is refused rather than driven negative")
    void cannotIssueMoreThanHeld() {
        WeightedAverageCost one = WeightedAverageCost.EMPTY.receive(1, BigDecimal.TEN);

        assertThatThrownBy(() -> one.issue(2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("stock coming in without a cost is refused: profit could never be worked out for it")
    void inboundNeedsACost() {
        assertThatThrownBy(() -> WeightedAverageCost.EMPTY.receive(1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
