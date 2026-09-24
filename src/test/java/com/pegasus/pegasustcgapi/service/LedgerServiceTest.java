package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.port.LedgerPort.CommissionQuote;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Platform commission [CR-6 US-36..38, CR-7]: the rate comes from
 * {@code commission.default_rate}, is applied to the items subtotal only, and the
 * amount is rounded to satang (two places, HALF_UP).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService — commission quote")
class LedgerServiceTest {

    private static final long SELLER_PROFILE_ID = 99L;

    @Mock
    private PlatformSettingService settings;

    @InjectMocks
    private LedgerService ledgerService;

    private void givenRate(String ratePercent) {
        given(settings.getDecimal(PlatformSettingService.COMMISSION_DEFAULT_RATE))
                .willReturn(new BigDecimal(ratePercent));
    }

    @Nested
    @DisplayName("Rounding to two decimal places")
    class Rounding {

        @ParameterizedTest(name = "{1}% of {0} = {2}")
        @CsvSource({
                // the example from the requirement: 5.265 rounds half up
                "175.50, 3,   5.27",
                "100.00, 5.0, 5.00",
                // 0.0005 is below half a satang, 0.005 is exactly half
                "0.01,   5,   0.00",
                "0.10,   5,   0.01",
                "33.33,  3,   1.00",
                "12.34,  7.5, 0.93",
                "999999.99, 3.5, 35000.00"
        })
        void amountIsRoundedHalfUpToSatang(String subtotal, String rate, String expected) {
            givenRate(rate);

            CommissionQuote quote = ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal(subtotal));

            assertThat(quote.amount()).isEqualByComparingTo(expected);
            assertThat(quote.amount().scale()).as("always carried as satang").isEqualTo(2);
        }

        @Test
        @DisplayName("the quote freezes the rate that was applied, so a later rate change cannot rewrite the order")
        void quoteCarriesTheAppliedRate() {
            givenRate("3");

            CommissionQuote quote = ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("175.50"));

            assertThat(quote.ratePercent()).isEqualByComparingTo("3");
        }
    }

    /**
     * Equivalence classes of the rate: below 0, 0..100, above 100. The service reads
     * the rate as configured and has no guard of its own, so the invalid partitions
     * pin down what happens today rather than a rejection that does not exist.
     */
    @Nested
    @DisplayName("ECC: commission rate partitions")
    class RatePartitions {

        @Test
        @DisplayName("valid partition, lower edge: 0% charges nothing")
        void zeroRateChargesNothing() {
            givenRate("0");

            assertThat(ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("175.50")).amount())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("valid partition, middle: 3.5% of 200.00 = 7.00")
        void midRangeRate() {
            givenRate("3.5");

            assertThat(ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("200.00")).amount())
                    .isEqualByComparingTo("7.00");
        }

        @Test
        @DisplayName("valid partition, upper edge: 100% takes the whole items subtotal")
        void fullRateTakesTheWholeSubtotal() {
            givenRate("100");

            assertThat(ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("175.50")).amount())
                    .isEqualByComparingTo("175.50");
        }

        @Test
        @DisplayName("invalid partition < 0 (current behaviour): -5% is not rejected and quotes a negative amount")
        void negativeRateIsNotRejected() {
            givenRate("-5");

            CommissionQuote quote = ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("175.50"));

            // Only seller_order's CHECK (commission_amount >= 0) stands in the way of this
            // quote; see CheckoutServiceIntegrationTest for what that does to a checkout.
            assertThat(quote.amount()).isEqualByComparingTo("-8.78");
        }

        @Test
        @DisplayName("invalid partition > 100 (current behaviour): 105% is not rejected and exceeds the subtotal")
        void rateAboveHundredIsNotRejected() {
            givenRate("105");

            CommissionQuote quote = ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("175.50"));

            assertThat(quote.amount()).isEqualByComparingTo("184.28")
                    .isGreaterThan(new BigDecimal("175.50"));
        }
    }

    @Nested
    @DisplayName("Nothing to charge")
    class NothingToCharge {

        @ParameterizedTest(name = "subtotal {0} -> CommissionQuote.NONE without reading the rate")
        @ValueSource(strings = {"0", "0.00", "-1.00"})
        @NullSource
        void nonPositiveSubtotalQuotesNone(String subtotal) {
            BigDecimal itemsSubtotal = subtotal == null ? null : new BigDecimal(subtotal);

            CommissionQuote quote = ledgerService.quoteCommission(SELLER_PROFILE_ID, itemsSubtotal);

            assertThat(quote).isEqualTo(CommissionQuote.NONE);
            verifyNoInteractions(settings);
        }
    }

    @Test
    @DisplayName("escrow release is a logged no-op until payouts exist: it must not fail a completion")
    void releaseDoesNotFail() {
        assertThatCode(() -> ledgerService.release(123L)).doesNotThrowAnyException();
        verifyNoInteractions(settings);
    }
}
