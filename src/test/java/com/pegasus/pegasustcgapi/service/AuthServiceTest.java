package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.LoginRequest;
import com.pegasus.pegasustcgapi.dto.RegisterRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.IssuedToken;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.support.FakePasswordEncoder;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private static final long USER_ID = 42L;
    private static final ClientInfo CLIENT = new ClientInfo("JUnit/1.0", "203.0.113.7");

    private static final String ACCESS_TOKEN = "signed.access.jwt";
    private static final String REFRESH_TOKEN = "opaque-refresh-token";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final int MAX_FAILED_LOGINS = 5;

    @Mock
    private UserRepository users;

    @Mock
    private RoleService roles;

    @Mock
    private AuthTokenService authTokens;

    @Mock
    private JwtService jwtService;

    @Spy
    private PasswordEncoder passwordEncoder = new FakePasswordEncoder();

    private Clock clock;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuthProperties properties = new AuthProperties(
                "unit-test-secret-key-at-least-32-bytes-long",
                "pegasus-tcg-api",
                ACCESS_TTL,
                REFRESH_TTL,
                Duration.ofHours(1),
                MAX_FAILED_LOGINS,
                LOCKOUT,
                false);

        service = new AuthService(users, roles, authTokens, jwtService, passwordEncoder, properties, clock);
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("normalises the account, defaults to BUYER and opens a session")
        void registersUser() {
            RegisterRequest request = new RegisterRequest(
                    "  Ploy@Example.COM ", "Ploy_01", "  Ploy Somsak  ", "correct-horse", "   ", null);

            given(users.existsByEmail("ploy@example.com")).willReturn(false);
            given(users.existsByUsername("ploy_01")).willReturn(false);
            given(users.insert(any())).willReturn(USER_ID);
            givenStoredUser(anActiveUser().withId(USER_ID).build());
            givenIssuedTokens();

            AuthResponse response = service.register(request, CLIENT);

            UserRepository.NewUser inserted = capturedNewUser();
            assertThat(inserted.email()).isEqualTo("ploy@example.com");
            assertThat(inserted.username()).isEqualTo("ploy_01");
            assertThat(inserted.displayName()).isEqualTo("Ploy Somsak");
            assertThat(inserted.phone()).as("a blank phone is stored as null").isNull();
            assertThat(inserted.passwordHash())
                    .as("the raw password never reaches the database")
                    .isEqualTo(FakePasswordEncoder.encoded("correct-horse"))
                    .isNotEqualTo("correct-horse");

            verify(roles).grant(USER_ID, Set.of(RoleCode.BUYER), null);
            assertThat(response.user().id()).isEqualTo(USER_ID);
            assertThat(response.tokens().accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(response.tokens().tokenType()).isEqualTo("Bearer");
            assertThat(response.tokens().expiresIn()).isEqualTo(ACCESS_TTL.toSeconds());
            assertThat(response.tokens().refreshToken()).isEqualTo(REFRESH_TOKEN);
            assertThat(response.tokens().refreshExpiresIn()).isEqualTo(REFRESH_TTL.toSeconds());
        }

        @Test
        @DisplayName("issues the refresh token against a fresh session family")
        void startsNewFamily() {
            given(users.insert(any())).willReturn(USER_ID);
            givenStoredUser(anActiveUser().withId(USER_ID).build());
            givenIssuedTokens();

            service.register(registration(null), CLIENT);

            ArgumentCaptor<UUID> family = ArgumentCaptor.forClass(UUID.class);
            verify(authTokens).issue(
                    eq(USER_ID), eq(TokenPurpose.REFRESH), eq(REFRESH_TTL), family.capture(), eq(CLIENT));
            assertThat(family.getValue()).isNotNull();
        }

        @Test
        @DisplayName("keeps a self-assignable role such as SELLER")
        void keepsSelfAssignableRoles() {
            given(users.insert(any())).willReturn(USER_ID);
            givenStoredUser(anActiveUser().withId(USER_ID).withRoles(RoleCode.SELLER).build());
            givenIssuedTokens();

            service.register(registration(Set.of(RoleCode.SELLER)), CLIENT);

            verify(roles).grant(USER_ID, Set.of(RoleCode.SELLER), null);
        }

        @Test
        @DisplayName("refuses ADMIN, which is only ever granted by an admin")
        void rejectsPrivilegedRoles() {
            assertThatThrownBy(() -> service.register(registration(Set.of(RoleCode.ADMIN)), CLIENT))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

            verify(users, never()).insert(any());
            verifyNoInteractions(roles, authTokens, jwtService);
        }

        @Test
        @DisplayName("reports a taken e-mail before touching the database")
        void rejectsDuplicateEmail() {
            given(users.existsByEmail("ploy@example.com")).willReturn(true);

            assertThatThrownBy(() -> service.register(registration(null), CLIENT))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_USED));

            verify(users, never()).insert(any());
        }

        @Test
        @DisplayName("reports a taken username")
        void rejectsDuplicateUsername() {
            given(users.existsByUsername("ploy_01")).willReturn(true);

            assertThatThrownBy(() -> service.register(registration(null), CLIENT))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USERNAME_ALREADY_USED));

            verify(users, never()).insert(any());
        }

        @Test
        @DisplayName("maps a username unique-index violation lost in a race")
        void mapsUsernameConstraintViolation() {
            given(users.insert(any())).willThrow(duplicateKey("uq_user_account_username"));

            assertThatThrownBy(() -> service.register(registration(null), CLIENT))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USERNAME_ALREADY_USED));
        }

        @Test
        @DisplayName("maps any other unique-index violation to the e-mail conflict")
        void mapsEmailConstraintViolation() {
            given(users.insert(any())).willThrow(duplicateKey("uq_user_account_email"));

            assertThatThrownBy(() -> service.register(registration(null), CLIENT))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_USED));
        }

        private RegisterRequest registration(Set<RoleCode> requestedRoles) {
            return new RegisterRequest(
                    "ploy@example.com", "ploy_01", "Ploy", "correct-horse", null, requestedRoles);
        }

        private UserRepository.NewUser capturedNewUser() {
            ArgumentCaptor<UserRepository.NewUser> captor =
                    ArgumentCaptor.forClass(UserRepository.NewUser.class);
            verify(users).insert(captor.capture());
            return captor.getValue();
        }

        private DuplicateKeyException duplicateKey(String constraint) {
            return new DuplicateKeyException(
                    "insert failed",
                    new SQLException("duplicate key value violates unique constraint \"" + constraint + "\""));
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("signs in on a matching password and records the login")
        void signsIn() {
            AuthUser stored = anActiveUser().withId(USER_ID).withPassword("correct-horse").build();
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(stored));
            given(users.findById(USER_ID)).willReturn(Optional.of(stored));
            given(roles.rolesOf(USER_ID)).willReturn(Set.of(RoleCode.BUYER, RoleCode.SELLER));
            givenIssuedTokens();

            AuthResponse response = service.login(
                    new LoginRequest("  Ploy@Example.com  ", "correct-horse"), CLIENT);

            verify(users).recordSuccessfulLogin(USER_ID, NOW_AT);
            assertThat(response.user().roles()).containsExactlyInAnyOrder(RoleCode.BUYER, RoleCode.SELLER);
            assertThat(response.tokens().accessToken()).isEqualTo(ACCESS_TOKEN);
        }

        @Test
        @DisplayName("rejects an unknown e-mail without revealing that it is unknown")
        void rejectsUnknownEmail() {
            given(users.findByEmail("nobody@example.com")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "guess"), CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

            // The decoy comparison keeps the response time in line with a real account.
            verify(passwordEncoder).matches(eq("guess"), anyString());
            verifyNoInteractions(authTokens, jwtService);
        }

        @Test
        @DisplayName("counts a wrong password without locking before the limit")
        void countsFailedAttempt() {
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(
                    anActiveUser().withId(USER_ID).withPassword("correct-horse")
                            .withFailedLoginAttempts(1).build()));

            assertThatThrownBy(() -> service.login(new LoginRequest("ploy@example.com", "wrong"), CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

            verify(users).recordFailedLogin(USER_ID, (short) 2, null);
            verify(users, never()).recordSuccessfulLogin(anyLong(), any());
            verifyNoInteractions(authTokens, jwtService);
        }

        @Test
        @DisplayName("locks the account on the last allowed attempt and resets the counter")
        void locksAfterLastAllowedAttempt() {
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(
                    anActiveUser().withId(USER_ID).withPassword("correct-horse")
                            .withFailedLoginAttempts(MAX_FAILED_LOGINS - 1).build()));

            assertThatThrownBy(() -> service.login(new LoginRequest("ploy@example.com", "wrong"), CLIENT))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));

            verify(users).recordFailedLogin(USER_ID, (short) 0, NOW_AT.plus(LOCKOUT));
        }

        @Test
        @DisplayName("turns away a locked account without checking the password")
        void rejectsLockedAccount() {
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(
                    anActiveUser().withId(USER_ID).withPassword("correct-horse")
                            .withLockedUntil(NOW_AT.plusMinutes(5)).build()));

            assertThatThrownBy(() -> service.login(new LoginRequest("ploy@example.com", "correct-horse"), CLIENT))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));

            verify(passwordEncoder, never()).matches(any(), anyString());
            verify(users, never()).recordFailedLogin(anyLong(), anyShort(), any());
            verifyNoInteractions(authTokens, jwtService);
        }

        @Test
        @DisplayName("lets the account back in once the lockout has lapsed")
        void allowsLoginAfterLockoutExpires() {
            AuthUser stored = anActiveUser().withId(USER_ID).withPassword("correct-horse")
                    .withLockedUntil(NOW_AT.minusSeconds(1)).build();
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(stored));
            given(users.findById(USER_ID)).willReturn(Optional.of(stored));
            given(roles.rolesOf(USER_ID)).willReturn(Set.of(RoleCode.BUYER));
            givenIssuedTokens();

            AuthResponse response = service.login(new LoginRequest("ploy@example.com", "correct-horse"), CLIENT);

            assertThat(response.tokens().refreshToken()).isEqualTo(REFRESH_TOKEN);
        }

        @Test
        @DisplayName("turns away a suspended account")
        void rejectsSuspendedAccount() {
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(
                    anActiveUser().withStatus(UserStatus.SUSPENDED).build()));

            assertThatThrownBy(() -> service.login(new LoginRequest("ploy@example.com", "correct-horse"), CLIENT))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED));
        }

        @Test
        @DisplayName("turns away a deactivated account")
        void rejectsDeactivatedAccount() {
            given(users.findByEmail("ploy@example.com")).willReturn(Optional.of(
                    anActiveUser().withStatus(UserStatus.DEACTIVATED).build()));

            assertThatThrownBy(() -> service.login(new LoginRequest("ploy@example.com", "correct-horse"), CLIENT))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DEACTIVATED));
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        private final UUID familyId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        @Test
        @DisplayName("spends the presented token and rotates within the same family")
        void rotatesToken() {
            given(authTokens.find("refresh-in", TokenPurpose.REFRESH))
                    .willReturn(Optional.of(liveToken()));
            given(users.findById(USER_ID)).willReturn(Optional.of(anActiveUser().withId(USER_ID).build()));
            given(roles.rolesOf(USER_ID)).willReturn(Set.of(RoleCode.BUYER));
            givenIssuedTokens();

            AuthResponse response = service.refresh("refresh-in", CLIENT);

            verify(authTokens).markUsed(7L);
            verify(authTokens).issue(USER_ID, TokenPurpose.REFRESH, REFRESH_TTL, familyId, CLIENT);
            verify(authTokens, never()).revokeFamilyDetached(any());
            assertThat(response.tokens().refreshToken()).isEqualTo(REFRESH_TOKEN);
        }

        @Test
        @DisplayName("rejects a token nobody issued")
        void rejectsUnknownToken() {
            given(authTokens.find("nonsense", TokenPurpose.REFRESH)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("nonsense", CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

            verify(authTokens, never()).revokeFamilyDetached(any());
        }

        @Test
        @DisplayName("treats a replayed token as theft and kills the whole family")
        void killsFamilyOnReplay() {
            given(authTokens.find("replayed", TokenPurpose.REFRESH))
                    .willReturn(Optional.of(tokenWith(NOW_AT.minusMinutes(1), null)));

            assertThatThrownBy(() -> service.refresh("replayed", CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

            verify(authTokens).revokeFamilyDetached(familyId);
            verify(authTokens, never()).markUsed(anyLong());
        }

        @Test
        @DisplayName("kills the family for a token from an already revoked session")
        void killsFamilyOnRevokedToken() {
            given(authTokens.find("revoked", TokenPurpose.REFRESH))
                    .willReturn(Optional.of(tokenWith(null, NOW_AT.minusMinutes(1))));

            assertThatThrownBy(() -> service.refresh("revoked", CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

            verify(authTokens).revokeFamilyDetached(familyId);
        }

        @Test
        @DisplayName("rejects an expired token but leaves the family alone")
        void rejectsExpiredToken() {
            StoredToken expired = new StoredToken(
                    7L, USER_ID, TokenPurpose.REFRESH, familyId, NOW_AT.minusSeconds(1), null, null);
            given(authTokens.find("stale", TokenPurpose.REFRESH)).willReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.refresh("stale", CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

            verify(authTokens, never()).revokeFamilyDetached(any());
        }

        @Test
        @DisplayName("ends the session when the account was suspended mid-session")
        void rejectsSuspendedAccount() {
            given(authTokens.find("refresh-in", TokenPurpose.REFRESH)).willReturn(Optional.of(liveToken()));
            given(users.findById(USER_ID)).willReturn(Optional.of(
                    anActiveUser().withId(USER_ID).withStatus(UserStatus.SUSPENDED).build()));

            assertThatThrownBy(() -> service.refresh("refresh-in", CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

            verify(authTokens).revokeFamilyDetached(familyId);
            verify(authTokens, never()).markUsed(anyLong());
        }

        @Test
        @DisplayName("ends the session when the account is gone")
        void rejectsMissingAccount() {
            given(authTokens.find("refresh-in", TokenPurpose.REFRESH)).willReturn(Optional.of(liveToken()));
            given(users.findById(USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("refresh-in", CLIENT))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

            verify(authTokens).revokeFamilyDetached(familyId);
        }

        private StoredToken liveToken() {
            return tokenWith(null, null);
        }

        private StoredToken tokenWith(OffsetDateTime usedAt, OffsetDateTime revokedAt) {
            return new StoredToken(
                    7L, USER_ID, TokenPurpose.REFRESH, familyId, NOW_AT.plusDays(1), usedAt, revokedAt);
        }
    }

    @Nested
    @DisplayName("sessions")
    class Sessions {

        @Test
        @DisplayName("logout revokes the family the token belongs to")
        void logoutRevokesFamily() {
            UUID familyId = UUID.randomUUID();
            given(authTokens.find("bye", TokenPurpose.REFRESH)).willReturn(Optional.of(new StoredToken(
                    7L, USER_ID, TokenPurpose.REFRESH, familyId, NOW_AT.plusDays(1), null, null)));

            service.logout("bye");

            verify(authTokens).revokeFamily(familyId);
        }

        @Test
        @DisplayName("logout stays silent about a token it does not know")
        void logoutIgnoresUnknownToken() {
            given(authTokens.find("stale", TokenPurpose.REFRESH)).willReturn(Optional.empty());

            service.logout("stale");

            verify(authTokens, never()).revokeFamily(any());
        }

        @Test
        @DisplayName("logout-all reports how many sessions it ended")
        void logoutAllReturnsRevokedCount() {
            given(authTokens.revokeAll(USER_ID, TokenPurpose.REFRESH)).willReturn(3);

            assertThat(service.logoutAll(USER_ID)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("currentUser")
    class CurrentUser {

        @Test
        @DisplayName("reads the account fresh, with its roles")
        void returnsUserWithRoles() {
            given(users.findById(USER_ID)).willReturn(Optional.of(anActiveUser().withId(USER_ID).build()));
            given(roles.rolesOf(USER_ID)).willReturn(Set.of(RoleCode.BUYER, RoleCode.SELLER));

            UserResponse response = service.currentUser(USER_ID);

            assertThat(response.id()).isEqualTo(USER_ID);
            assertThat(response.email()).isEqualTo("ploy@example.com");
            assertThat(response.roles()).containsExactlyInAnyOrder(RoleCode.BUYER, RoleCode.SELLER);
        }

        @Test
        @DisplayName("reports a missing account as 404 rather than an empty body")
        void rejectsMissingAccount() {
            given(users.findById(USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.currentUser(USER_ID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        }
    }

    private void givenStoredUser(AuthUser user) {
        given(users.findById(user.id())).willReturn(Optional.of(user));
        given(roles.rolesOf(user.id())).willReturn(user.roles());
    }

    private void givenIssuedTokens() {
        given(jwtService.issue(any()))
                .willReturn(new JwtService.AccessToken(ACCESS_TOKEN, ACCESS_TTL.toSeconds()));
        given(authTokens.issue(anyLong(), eq(TokenPurpose.REFRESH), eq(REFRESH_TTL), any(), any()))
                .willReturn(new IssuedToken(7L, REFRESH_TOKEN, NOW_AT.plus(REFRESH_TTL)));
    }
}
