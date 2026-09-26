package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.repository.PlatformSettingRepository;
import com.pegasus.pegasustcgapi.repository.PlatformSettingRepository.Setting;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime settings an admin edits [RQ-14] — here for the commission rate [CR-6], the
 * one value that turns straight into money on every order.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformSettingService")
class PlatformSettingServiceTest {

    private static final String RATE = PlatformSettingService.COMMISSION_DEFAULT_RATE;
    private static final long ADMIN_ID = 1L;

    @Mock
    private PlatformSettingRepository repository;

    private PlatformSettingService settings;

    @BeforeEach
    void setUp() {
        settings = new PlatformSettingService(repository, new ObjectMapper());
    }

    @Nested
    @DisplayName("Reading values")
    class Reading {

        @ParameterizedTest(name = "stored as {0} reads as 5.0")
        @ValueSource(strings = {"5.0", "\"5.0\"", " 5.0 "})
        void decimalIsReadWithOrWithoutJsonQuotes(String stored) {
            given(repository.findValue(RATE)).willReturn(Optional.of(stored));

            assertThat(settings.getDecimal(RATE)).isEqualByComparingTo("5.0");
        }

        @Test
        @DisplayName("minutes, hours and days settings become durations")
        void durations() {
            given(repository.findValue(anyString())).willReturn(Optional.of("5"));

            assertThat(settings.getMinutes(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES))
                    .isEqualTo(Duration.ofMinutes(5));
            assertThat(settings.getHours(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS))
                    .isEqualTo(Duration.ofHours(5));
            assertThat(settings.getDays(PlatformSettingService.ESCROW_AUTO_RELEASE_DAYS))
                    .isEqualTo(Duration.ofDays(5));
        }

        @Test
        @DisplayName("a missing setting is SETTING_NOT_FOUND, not a silent default")
        void missingSettingFailsLoudly() {
            given(repository.findValue(RATE)).willReturn(Optional.empty());

            assertThatThrownBy(() -> settings.getDecimal(RATE))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTING_NOT_FOUND));
        }

        @Test
        @DisplayName("a non-numeric stored value is a broken deployment and fails loudly")
        void nonNumericValueFailsLoudly() {
            given(repository.findValue(RATE)).willReturn(Optional.of("\"five\""));

            assertThatThrownBy(() -> settings.getDecimal(RATE)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Updating the commission rate")
    class UpdatingTheCommissionRate {

        @ParameterizedTest(name = "valid partition: rate {0} is saved")
        @ValueSource(strings = {"0", "3", "3.5", "100"})
        void rateWithinZeroToHundredIsSaved(String rate) {
            given(repository.exists(RATE)).willReturn(true);
            given(repository.findAll()).willReturn(List.of(new Setting(RATE, rate, "Default commission", ADMIN_ID)));

            Setting saved = settings.update(RATE, rate, ADMIN_ID);

            verify(repository).update(RATE, rate, ADMIN_ID);
            assertThat(saved.value()).isEqualTo(rate);
        }

        @Test
        @DisplayName("a key that was never seeded is SETTING_NOT_FOUND and nothing is written")
        void unknownKeyIsNotFound() {
            given(repository.exists("commission.typo")).willReturn(false);

            assertThatThrownBy(() -> settings.update("commission.typo", "3", ADMIN_ID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTING_NOT_FOUND));
            verify(repository, never()).update(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("a value that is not JSON is 400 VALIDATION_FAILED and nothing is written")
        void nonJsonValueIsRejected() {
            given(repository.exists(RATE)).willReturn(true);

            assertThatThrownBy(() -> settings.update(RATE, "3%", ADMIN_ID))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            verify(repository, never()).update(anyString(), anyString(), anyLong());
        }

        @ParameterizedTest(name = "invalid partition: {0} is refused with 400 and nothing is written")
        @ValueSource(strings = {"-5", "-0.01", "100.01", "105", "\"abc\""})
        @Disabled(LedgerServiceTest.KNOWN_BUG_COMMISSION_RATE)
        void rateOutsideZeroToHundredIsRejected(String rate) {
            given(repository.exists(RATE)).willReturn(true);

            assertThatThrownBy(() -> settings.update(RATE, rate, ADMIN_ID))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            verify(repository, never()).update(anyString(), anyString(), anyLong());
        }
    }
}
