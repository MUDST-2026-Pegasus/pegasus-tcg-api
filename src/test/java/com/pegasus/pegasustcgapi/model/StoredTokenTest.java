package com.pegasus.pegasustcgapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StoredToken")
class StoredTokenTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("is usable while it is unspent, unrevoked and in date")
    void liveTokenIsUsable() {
        StoredToken token = token(NOW_AT.plusMinutes(1), null, null);

        assertThat(token.usableAt(NOW)).isTrue();
        assertThat(token.expired(NOW)).isFalse();
        assertThat(token.spent()).isFalse();
        assertThat(token.revoked()).isFalse();
    }

    @Test
    @DisplayName("counts the expiry instant itself as expired")
    void expiryInstantIsExclusive() {
        StoredToken token = token(NOW_AT, null, null);

        assertThat(token.expired(NOW)).isTrue();
        assertThat(token.usableAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("is spent once it has been redeemed")
    void redeemedTokenIsSpent() {
        StoredToken token = token(NOW_AT.plusHours(1), NOW_AT.minusMinutes(1), null);

        assertThat(token.spent()).isTrue();
        assertThat(token.usableAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("is unusable once its family was revoked")
    void revokedTokenIsUnusable() {
        StoredToken token = token(NOW_AT.plusHours(1), null, NOW_AT.minusMinutes(1));

        assertThat(token.revoked()).isTrue();
        assertThat(token.usableAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("compares against the given instant, not the wall clock")
    void expiryFollowsSuppliedInstant() {
        StoredToken token = token(NOW_AT, null, null);

        assertThat(token.expired(NOW.minusSeconds(1))).isFalse();
        assertThat(token.expired(NOW.plusSeconds(1))).isTrue();
    }

    private static StoredToken token(
            OffsetDateTime expiresAt, OffsetDateTime usedAt, OffsetDateTime revokedAt) {
        return new StoredToken(
                7L, 42L, TokenPurpose.REFRESH, UUID.randomUUID(), expiresAt, usedAt, revokedAt);
    }
}
