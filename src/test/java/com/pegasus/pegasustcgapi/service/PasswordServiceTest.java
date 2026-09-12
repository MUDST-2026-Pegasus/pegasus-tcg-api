package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.ChangePasswordRequest;
import com.pegasus.pegasustcgapi.dto.ResetPasswordRequest;
import com.pegasus.pegasustcgapi.dto.TokenResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.support.FakePasswordEncoder;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordService")
class PasswordServiceTest {

    private static final long USER_ID = 42L;
    private static final ClientInfo CLIENT = new ClientInfo("JUnit/1.0", "203.0.113.7");
    private static final TokenResponse FRESH_TOKENS =
            TokenResponse.bearer("new.access.jwt", 900, "new-refresh-token", 2_592_000);

    @Mock
    private UserRepository users;

    @Mock
    private AuthTokenService authTokens;

    @Mock
    private AuthService authService;

    @Spy
    private PasswordEncoder passwordEncoder = new FakePasswordEncoder();

    private PasswordService service;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                "unit-test-secret-key-at-least-32-bytes", "pegasus-tcg-api",
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofHours(1),
                5, Duration.ofMinutes(15), false);

        service = new PasswordService(users, authTokens, authService, passwordEncoder, properties);
    }

//    @Nested
//    @DisplayName("reset")
//    class Reset {
//
//        @Test
//        @DisplayName("sets the new hash and signs the account out everywhere")
//        void resetsPassword() {
//            given(authTokens.consume("reset-token", TokenPurpose.PASSWORD_RESET))
//                    .willReturn(resetToken());
//
//            service.reset(new ResetPasswordRequest("reset-token", "brand-new-password"));
//
//            verify(users).updatePasswordHash(USER_ID, FakePasswordEncoder.encoded("brand-new-password"));
//            verify(authTokens).revokeAll(USER_ID, TokenPurpose.REFRESH);
//            // Any other reset link that is still in the mailbox has to die with it.
//            verify(authTokens).revokeAll(USER_ID, TokenPurpose.PASSWORD_RESET);
//        }
//
//        @Test
//        @DisplayName("changes nothing when the token is invalid, expired or spent")
//        void rejectsInvalidToken() {
//            willThrow(new UnauthorizedException(ErrorCode.INVALID_TOKEN))
//                    .given(authTokens).consume("stale-token", TokenPurpose.PASSWORD_RESET);
//
//            assertThatThrownBy(() -> service.reset(new ResetPasswordRequest("stale-token", "brand-new-password")))
//                    .isInstanceOfSatisfying(UnauthorizedException.class,
//                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
//
//            verify(users, never()).updatePasswordHash(anyLong(), anyString());
//            verify(authTokens, never()).revokeAll(anyLong(), any());
//        }
//
//        private StoredToken resetToken() {
//            return new StoredToken(7L, USER_ID, TokenPurpose.PASSWORD_RESET, UUID.randomUUID(),
//                    OffsetDateTime.now().plusHours(1), null, null);
//        }
//    }

    @Nested
    @DisplayName("change")
    class Change {

        @Test
        @DisplayName("stores the new hash, drops old sessions and returns a fresh pair")
        void changesPassword() {
            AuthUser user = anActiveUser().withId(USER_ID).withPassword("current-password").build();
            given(authService.loadWithRoles(USER_ID)).willReturn(user);
            given(authService.startSession(user, CLIENT)).willReturn(FRESH_TOKENS);

            AuthResponse response = service.change(
                    USER_ID, new ChangePasswordRequest("current-password", "brand-new-password"), CLIENT);

            verify(users).updatePasswordHash(USER_ID, FakePasswordEncoder.encoded("brand-new-password"));
            verify(authTokens).revokeAll(USER_ID, TokenPurpose.REFRESH);
            assertThat(response.tokens()).isEqualTo(FRESH_TOKENS);
            assertThat(response.user().id()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("refuses a wrong current password and leaves the account untouched")
        void rejectsWrongCurrentPassword() {
            given(authService.loadWithRoles(USER_ID))
                    .willReturn(anActiveUser().withId(USER_ID).withPassword("current-password").build());

            assertThatThrownBy(() -> service.change(
                    USER_ID, new ChangePasswordRequest("not-my-password", "brand-new-password"), CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS))
                    .hasMessage("Current password is incorrect");

            verify(users, never()).updatePasswordHash(anyLong(), anyString());
            verify(authTokens, never()).revokeAll(anyLong(), any());
            verify(authService, never()).startSession(any(), any());
        }
    }
}
