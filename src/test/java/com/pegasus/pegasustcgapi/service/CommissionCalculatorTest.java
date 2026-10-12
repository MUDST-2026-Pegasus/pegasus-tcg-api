package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommissionCalculatorTest {

    @ParameterizedTest
    @CsvSource({
            "1234.57,5.5,67.90",
            "0.10,5,0.01",
            "19.90,2.5,0.50",
            "333.33,3.333,11.11"
    })
    void roundsOnlyTheFee(String base, String rate, String expected) {
        assertThat(CommissionCalculator.fee(new BigDecimal(base), new BigDecimal(rate),
                BigDecimal.ZERO, null, null)).isEqualByComparingTo(expected);
    }

    @Test
    void appliesFixedMinimumMaximumAndBaseCap() {
        assertThat(CommissionCalculator.fee(new BigDecimal("1000.00"),
                new BigDecimal("3"), new BigDecimal("5"), null, null))
                .isEqualByComparingTo("35.00");
        assertThat(CommissionCalculator.fee(new BigDecimal("10.00"),
                new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("20"), null))
                .isEqualByComparingTo("10.00");
        assertThat(CommissionCalculator.fee(new BigDecimal("50000.00"),
                new BigDecimal("5"), BigDecimal.ZERO, null, new BigDecimal("1000")))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void rejectsInvalidRate() {
        assertThatThrownBy(() -> CommissionCalculator.fee(new BigDecimal("100"),
                new BigDecimal("101"), BigDecimal.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
