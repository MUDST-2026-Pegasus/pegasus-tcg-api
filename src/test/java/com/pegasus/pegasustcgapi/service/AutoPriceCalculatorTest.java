package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.MarketStat;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.service.AutoPriceCalculator.Quote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AutoPriceCalculatorTest {

    private static final int MIN_SAMPLE = 3;

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Listing auto(String offset, String floor, String ceiling) {
        return new Listing(7001L, 11L, 902L, CardCondition.NM, null, null, dec("1350.00"), "THB",
                PricingMode.AUTO_MEDIAN, dec(offset), dec(floor), dec(ceiling), null, 3, 0, 3,
                ListingStatus.ACTIVE, null, null, 0, null, null, null);
    }

    private static MarketStat market(String median, int sampleSize) {
        return new MarketStat(902L, CardCondition.NM, LocalDate.of(2026, 8, 16), dec(median), null, null, null,
                null, null, sampleSize, 0, sampleSize, null);
    }

    @Test
    @DisplayName("the schema guide's own example: median 1,358 less 5% is 1,290.10")
    void guideExample() {
        Quote quote = AutoPriceCalculator.quote(auto("-5.00", null, null), market("1358.00", 37), MIN_SAMPLE)
                .orElseThrow();

        assertThat(quote.price()).isEqualByComparingTo("1290.10");
        assertThat(quote.reason()).isEqualTo("median 1358.00 × (1 − 5%)");
    }

    @Test
    @DisplayName("no offset follows the median exactly")
    void noOffset() {
        Quote quote = AutoPriceCalculator.quote(auto(null, null, null), market("1358.00", 37), MIN_SAMPLE)
                .orElseThrow();

        assertThat(quote.price()).isEqualByComparingTo("1358.00");
        assertThat(quote.reason()).isEqualTo("median 1358.00");
    }

    @Test
    @DisplayName("a fractional offset above the market rounds to the satang")
    void positiveFractionalOffset() {
        Quote quote = AutoPriceCalculator.quote(auto("2.50", null, null), market("1358.00", 37), MIN_SAMPLE)
                .orElseThrow();

        assertThat(quote.price()).isEqualByComparingTo("1391.95");
        assertThat(quote.reason()).isEqualTo("median 1358.00 × (1 + 2.5%)");
    }

    @Test
    @DisplayName("a falling market stops at the seller's floor, and the history says so")
    void heldAtFloor() {
        Quote quote = AutoPriceCalculator.quote(auto("-20", "1100.00", null), market("1000.00", 5), MIN_SAMPLE)
                .orElseThrow();

        assertThat(quote.price()).isEqualByComparingTo("1100.00");
        assertThat(quote.reason()).endsWith("held at floor 1100.00");
    }

    @Test
    @DisplayName("a rising market stops at the seller's ceiling")
    void heldAtCeiling() {
        Quote quote = AutoPriceCalculator.quote(auto("10", null, "1400.00"), market("1358.00", 5), MIN_SAMPLE)
                .orElseThrow();

        assertThat(quote.price()).isEqualByComparingTo("1400.00");
        assertThat(quote.reason()).endsWith("held at ceiling 1400.00");
    }

    @Test
    @DisplayName("too thin a market moves nothing")
    void sampleBelowMinimum() {
        Optional<Quote> quote = AutoPriceCalculator.quote(auto("-5", null, null), market("1358.00", 2), MIN_SAMPLE);

        assertThat(quote).isEmpty();
    }

    @Test
    @DisplayName("no market at all moves nothing")
    void noMarket() {
        assertThat(AutoPriceCalculator.quote(auto("-5", null, null), null, MIN_SAMPLE)).isEmpty();
    }
}
