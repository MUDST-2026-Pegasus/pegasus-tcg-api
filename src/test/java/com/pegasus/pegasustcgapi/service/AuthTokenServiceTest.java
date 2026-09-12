package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.IssuedToken;
import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.repository.AuthTokenRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenService")
class AuthTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private static final long USER_ID = 42L;
    private static final long TOKEN_ID = 7L;
    private static final UUID FAMILY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final ClientInfo CLIENT = new ClientInfo("JUnit/1.0", "203.0.113.7");

    @Mock
    private AuthTokenRepository tokens;

    private AuthTokenService service;

    @BeforeEach
    void setUp() {
        service = new AuthTokenService(tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("stores only the hash of the token it hands out")
    void storesHashNotValue() {
        given(tokens.insert(anyLong(), any(), any(), any(), any(), any(), any())).willReturn(TOKEN_ID);

        IssuedToken issued = service.issue(
                USER_ID, TokenPurpose.REFRESH, Duration.ofDays(30), FAMILY_ID, CLIENT);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(tokens).insert(
                eq(USER_ID),
                eq(TokenPurpose.REFRESH),
                hash.capture(),
                eq(FAMILY_ID),
                eq(NOW_AT.plusDays(30)),
                eq("JUnit/1.0"),
                eq("203.0.113.7"));

        assertThat(hash.getValue())
                .as("the plain token must not be recoverable from the row")
                .isNotEqualTo(issued.value())
                .isEqualTo(AuthTokenService.hash(issued.value()))
                .hasSize(64);

        assertThat(issued.id()).isEqualTo(TOKEN_ID);
        assertThat(issued.expiresAt()).isEqualTo(NOW_AT.plusDays(30));
    }

    @Test
    @DisplayName("hands out url-safe values that never repeat")
    void issuesUniqueUrlSafeValues() {
        given(tokens.insert(anyLong(), any(), any(), any(), any(), any(), any())).willReturn(TOKEN_ID);

        String first = service.issue(USER_ID, TokenPurpose.REFRESH, Duration.ofDays(1), FAMILY_ID, CLIENT).value();
        String second = service.issue(USER_ID, TokenPurpose.REFRESH, Duration.ofDays(1), FAMILY_ID, CLIENT).value();

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]+").hasSize(43);
    }

    @Test
    @DisplayName("looks a token up by its hash, never by its value")
    void findsByHash() {
        StoredToken stored = liveToken();
        given(tokens.findByHash(AuthTokenService.hash("raw-token"), TokenPurpose.REFRESH))
                .willReturn(Optional.of(stored));

        assertThat(service.find("raw-token", TokenPurpose.REFRESH)).contains(stored);
    }

    @Test
    @DisplayName("consume redeems a usable token exactly once")
    void consumeRedeemsToken() {
        given(tokens.findByHash(AuthTokenService.hash("raw"), TokenPurpose.PASSWORD_RESET))
                .willReturn(Optional.of(liveToken()));
        given(tokens.markUsed(TOKEN_ID, NOW_AT)).willReturn(true);

        StoredToken consumed = service.consume("raw", TokenPurpose.PASSWORD_RESET);

        assertThat(consumed.userId()).isEqualTo(USER_ID);
        verify(tokens).markUsed(TOKEN_ID, NOW_AT);
    }

    @Test
    @DisplayName("consume rejects a token nobody issued")
    void consumeRejectsUnknownToken() {
        given(tokens.findByHash(any(), eq(TokenPurpose.PASSWORD_RESET))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume("nonsense", TokenPurpose.PASSWORD_RESET))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

        verify(tokens, never()).markUsed(anyLong(), any());
    }

    @Test
    @DisplayName("consume rejects an expired token")
    void consumeRejectsExpiredToken() {
        givenStored(new StoredToken(TOKEN_ID, USER_ID, TokenPurpose.PASSWORD_RESET, FAMILY_ID,
                NOW_AT.minusSeconds(1), null, null));

        assertThatThrownBy(() -> service.consume("raw", TokenPurpose.PASSWORD_RESET))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

        verify(tokens, never()).markUsed(anyLong(), any());
    }

    @Test
    @DisplayName("consume rejects a token that was already spent")
    void consumeRejectsSpentToken() {
        givenStored(new StoredToken(TOKEN_ID, USER_ID, TokenPurpose.PASSWORD_RESET, FAMILY_ID,
                NOW_AT.plusHours(1), NOW_AT.minusMinutes(5), null));

        assertThatThrownBy(() -> service.consume("raw", TokenPurpose.PASSWORD_RESET))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

        verify(tokens, never()).markUsed(anyLong(), any());
    }

    @Test
    @DisplayName("consume rejects a revoked token")
    void consumeRejectsRevokedToken() {
        givenStored(new StoredToken(TOKEN_ID, USER_ID, TokenPurpose.PASSWORD_RESET, FAMILY_ID,
                NOW_AT.plusHours(1), null, NOW_AT.minusMinutes(5)));

        assertThatThrownBy(() -> service.consume("raw", TokenPurpose.PASSWORD_RESET))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));

        verify(tokens, never()).markUsed(anyLong(), any());
    }

    @Test
    @DisplayName("consume lets only one of two racing requests win")
    void consumeLosesRaceGracefully() {
        givenStored(liveToken());
        // The other request got there first, so the conditional update matched no row.
        given(tokens.markUsed(TOKEN_ID, NOW_AT)).willReturn(false);

        assertThatThrownBy(() -> service.consume("raw", TokenPurpose.PASSWORD_RESET))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("revoking stamps the clock's time on the family")
    void revokeFamilyUsesClock() {
        service.revokeFamily(FAMILY_ID);

        verify(tokens).revokeFamily(FAMILY_ID, NOW_AT);
    }

    @Test
    @DisplayName("revokeAll reports how many tokens were still live")
    void revokeAllReturnsCount() {
        given(tokens.revokeAllForUser(USER_ID, TokenPurpose.REFRESH, NOW_AT)).willReturn(4);

        assertThat(service.revokeAll(USER_ID, TokenPurpose.REFRESH)).isEqualTo(4);
    }

    @Test
    @DisplayName("markUsed stamps the clock's time on the token")
    void markUsedUsesClock() {
        service.markUsed(TOKEN_ID);

        verify(tokens).markUsed(TOKEN_ID, NOW_AT);
    }

    @Test
    @DisplayName("hashes with plain SHA-256, hex encoded")
    void hashesWithSha256() {
        assertThat(AuthTokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(AuthTokenService.hash("abc")).isEqualTo(AuthTokenService.hash("abc"));
        assertThat(AuthTokenService.hash("abd")).isNotEqualTo(AuthTokenService.hash("abc"));
    }

    private void givenStored(StoredToken token) {
        given(tokens.findByHash(AuthTokenService.hash("raw"), token.purpose()))
                .willReturn(Optional.of(token));
    }

    private StoredToken liveToken() {
        return new StoredToken(TOKEN_ID, USER_ID, TokenPurpose.PASSWORD_RESET, FAMILY_ID,
                NOW_AT.plusHours(1), null, null);
    }
}
