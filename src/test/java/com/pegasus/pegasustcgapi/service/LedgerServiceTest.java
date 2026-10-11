package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.port.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository.ProductScope;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository.Rule;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
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
import org.mockito.Spy;
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

    @Mock
    private CommissionRuleRepository rules;

    @Mock
    private EscrowReleaseService escrowRelease;

    @Mock
    private RefundService refunds;

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private LedgerService ledgerService;

    private void givenRate(String ratePercent) {
        given(settings.getDecimal(PlatformSettingService.COMMISSION_DEFAULT_RATE))
                .willReturn(new BigDecimal(ratePercent));
    }

    @Test
    void sellerRuleWinsOverCategoryAndGlobal() {
        given(rules.productScopes(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(List.of(new ProductScope(1L, (short) 2, 3)));
        given(rules.activeAt(org.mockito.ArgumentMatchers.any())).willReturn(List.of(
                new Rule(1L, "GLOBAL", null, null, null, new BigDecimal("5"), BigDecimal.ZERO, null, null, (short) 0),
                new Rule(2L, "CATEGORY", null, 3, null, new BigDecimal("4"), BigDecimal.ZERO, null, null, (short) 0),
                new Rule(3L, "SELLER", null, null, SELLER_PROFILE_ID, new BigDecimal("3"), BigDecimal.ZERO, null, null, (short) 0)));

        assertThat(ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("100.00"), List.of(1L)).amount())
                .isEqualByComparingTo("3.00");
    }

    @Test
    void mixedCategoriesSkipCategoryRule() {
        given(rules.productScopes(org.mockito.ArgumentMatchers.anyCollection())).willReturn(List.of(
                new ProductScope(1L, (short) 2, 3), new ProductScope(2L, (short) 2, 4)));
        given(rules.activeAt(org.mockito.ArgumentMatchers.any())).willReturn(List.of(
                new Rule(1L, "GAME", (short) 2, null, null, new BigDecimal("2"), BigDecimal.ZERO, null, null, (short) 0),
                new Rule(2L, "CATEGORY", null, 3, null, new BigDecimal("4"), BigDecimal.ZERO, null, null, (short) 0)));

        assertThat(ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("100.00"), List.of(1L, 2L)).amount())
                .isEqualByComparingTo("2.00");
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
     * Equivalence classes of the rate: below 0, 0..100, above 100. The invalid
     * partitions ensure a percentage outside 0..100 is never turned into a commission.
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

        @ParameterizedTest(name = "invalid partition: rate {0} is refused rather than quoted")
        @ValueSource(strings = {"-5", "-0.01", "100.01", "105"})
        void rateOutsideZeroToHundredIsRefused(String rate) {
            givenRate(rate);

            // Any refusal will do; what must not happen is a quote that is negative or
            // larger than the items it is charged on.
            assertThatThrownBy(() -> ledgerService.quoteCommission(SELLER_PROFILE_ID, new BigDecimal("175.50")))
                    .isInstanceOf(RuntimeException.class);
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
    @DisplayName("escrow release delegates to the money service")
    void releaseDoesNotFail() {
        assertThatCode(() -> ledgerService.release(123L)).doesNotThrowAnyException();
        org.mockito.Mockito.verify(escrowRelease).release(123L);
        verifyNoInteractions(settings);
    }
}
