package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.AuthToken.AUTH_TOKEN;

import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.jooq.tables.records.AuthTokenRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Stores refresh, verification and reset tokens by SHA-256 hash. Rows are kept
 * after use rather than deleted: the spent row is what lets a replayed refresh
 * token be recognised as theft instead of as an unknown token.
 */
@Repository
public class AuthTokenRepository {

    private final DSLContext dsl;

    public AuthTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(
            long userId,
            TokenPurpose purpose,
            String tokenHash,
            UUID familyId,
            OffsetDateTime expiresAt,
            String userAgent,
            String clientIp) {

        return dsl.insertInto(AUTH_TOKEN)
                .set(AUTH_TOKEN.USER_ID, userId)
                .set(AUTH_TOKEN.PURPOSE, purpose)
                .set(AUTH_TOKEN.TOKEN_HASH, tokenHash)
                .set(AUTH_TOKEN.FAMILY_ID, familyId)
                .set(AUTH_TOKEN.EXPIRES_AT, expiresAt)
                .set(AUTH_TOKEN.USER_AGENT, userAgent)
                .set(AUTH_TOKEN.CLIENT_IP, clientIp)
                .returningResult(AUTH_TOKEN.ID)
                .fetchSingle(AUTH_TOKEN.ID);
    }

    /** Returns spent and revoked rows too; the caller decides what that means. */
    public Optional<StoredToken> findByHash(String tokenHash, TokenPurpose purpose) {
        return dsl.selectFrom(AUTH_TOKEN)
                .where(AUTH_TOKEN.TOKEN_HASH.eq(tokenHash))
                .and(AUTH_TOKEN.PURPOSE.eq(purpose))
                .fetchOptional()
                .map(AuthTokenRepository::toStoredToken);
    }

    /**
     * Redeems a token, guarding on it still being unused so two concurrent
     * requests cannot both spend it.
     *
     * @return true for the request that won.
     */
    public boolean markUsed(long id, OffsetDateTime at) {
        return dsl.update(AUTH_TOKEN)
                .set(AUTH_TOKEN.USED_AT, at)
                .where(AUTH_TOKEN.ID.eq(id))
                .and(AUTH_TOKEN.USED_AT.isNull())
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .execute() > 0;
    }

    public void revokeById(long id, OffsetDateTime at) {
        dsl.update(AUTH_TOKEN)
                .set(AUTH_TOKEN.REVOKED_AT, at)
                .where(AUTH_TOKEN.ID.eq(id))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .execute();
    }

    /** Ends one login session: every token rotated out of the original one goes with it. */
    public int revokeFamily(UUID familyId, OffsetDateTime at) {
        return dsl.update(AUTH_TOKEN)
                .set(AUTH_TOKEN.REVOKED_AT, at)
                .where(AUTH_TOKEN.FAMILY_ID.eq(familyId))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .execute();
    }

    /** Signs the user out of every device, or invalidates every outstanding reset link. */
    public int revokeAllForUser(long userId, TokenPurpose purpose, OffsetDateTime at) {
        return dsl.update(AUTH_TOKEN)
                .set(AUTH_TOKEN.REVOKED_AT, at)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.PURPOSE.eq(purpose))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .execute();
    }

    /** Housekeeping for rows that can no longer be redeemed or replayed. */
    public int deleteExpiredBefore(OffsetDateTime cutoff) {
        return dsl.deleteFrom(AUTH_TOKEN)
                .where(AUTH_TOKEN.EXPIRES_AT.lt(cutoff))
                .execute();
    }

    private static StoredToken toStoredToken(AuthTokenRecord r) {
        return new StoredToken(
                r.getId(),
                r.getUserId(),
                r.getPurpose(),
                r.getFamilyId(),
                r.getExpiresAt(),
                r.getUsedAt(),
                r.getRevokedAt());
    }
}
