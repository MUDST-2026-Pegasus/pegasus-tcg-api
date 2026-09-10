package com.pegasus.pegasustcgapi.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A row of {@code auth_token}. The token value itself is not stored, only its hash. */
public record StoredToken(
        long id,
        long userId,
        TokenPurpose purpose,
        UUID familyId,
        OffsetDateTime expiresAt,
        OffsetDateTime usedAt,
        OffsetDateTime revokedAt) {

    public boolean expired(Instant now) {
        return !expiresAt.toInstant().isAfter(now);
    }

    public boolean spent() {
        return usedAt != null;
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    /** Usable exactly once: not yet redeemed, not revoked, not past its expiry. */
    public boolean usableAt(Instant now) {
        return !spent() && !revoked() && !expired(now);
    }
}
