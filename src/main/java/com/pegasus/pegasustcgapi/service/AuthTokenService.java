package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.model.IssuedToken;
import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.repository.AuthTokenRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems the opaque tokens: refresh, e-mail verification and password
 * reset. They are random rather than signed so they can be revoked, and only
 * their SHA-256 is stored so a database leak yields nothing usable.
 *
 * <p>A plain digest is right here — unlike a password these are 256 bits of
 * uniform randomness, so there is nothing for an attacker to guess at and no
 * work factor to add.
 */
@Service
public class AuthTokenService {

    private static final int TOKEN_BYTES = 32;

    private final AuthTokenRepository tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthTokenService(AuthTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * @param familyId groups refresh tokens rotated from one login. Pass a fresh
     *                 UUID for anything that is not a rotation.
     */
    public IssuedToken issue(
            long userId, TokenPurpose purpose, Duration ttl, UUID familyId, ClientInfo client) {

        String value = randomToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(ttl);

        long id = tokens.insert(
                userId, purpose, hash(value), familyId, expiresAt, client.userAgent(), client.ip());

        return new IssuedToken(id, value, expiresAt);
    }

    public Optional<StoredToken> find(String rawToken, TokenPurpose purpose) {
        return tokens.findByHash(hash(rawToken), purpose);
    }

    /**
     * Redeems a single-use token. Unknown, expired, revoked and already-spent
     * tokens are all reported as {@link ErrorCode#INVALID_TOKEN}, so a caller
     * cannot probe which of those a value happens to be.
     */
    public StoredToken consume(String rawToken, TokenPurpose purpose) {
        StoredToken token = find(rawToken, purpose)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_TOKEN));

        if (!token.usableAt(clock.instant())) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }
        // Conditional update: two requests racing on one token, only one wins.
        if (!tokens.markUsed(token.id(), OffsetDateTime.now(clock))) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }
        return token;
    }

    public void markUsed(long tokenId) {
        tokens.markUsed(tokenId, OffsetDateTime.now(clock));
    }

    public void revokeFamily(UUID familyId) {
        tokens.revokeFamily(familyId, OffsetDateTime.now(clock));
    }

    /**
     * Same, but committed independently of the caller. Reuse detection revokes the
     * family and then rejects the request; without its own transaction the throw
     * would roll the revocation back and leave the stolen session alive.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamilyDetached(UUID familyId) {
        revokeFamily(familyId);
    }

    /** @return how many tokens were still live. */
    public int revokeAll(long userId, TokenPurpose purpose) {
        return tokens.revokeAllForUser(userId, purpose, OffsetDateTime.now(clock));
    }

    /** URL-safe and unpadded, so the value survives a query string or an e-mail link. */
    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
