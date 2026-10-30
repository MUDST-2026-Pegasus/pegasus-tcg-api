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
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.security.InvalidKeyException;
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
    private final SecretKey signingKey;
    private final SecureRandom random = new SecureRandom();

    public AuthTokenService(AuthTokenRepository tokens, Clock clock, SecretKey jwtSigningKey) {
        this.tokens = tokens;
        this.clock = clock;
        this.signingKey = jwtSigningKey;
    }

    /**
     * @param familyId groups refresh tokens rotated from one login. Pass a fresh
     *                 UUID for anything that is not a rotation.
     */
    public IssuedToken issue(
            long userId, TokenPurpose purpose, Duration ttl, UUID familyId, ClientInfo client) {
        return insert(userId, purpose, ttl, familyId, client, randomToken());
    }

    /** A refresh successor can be reconstructed for a short retry after a lost response. */
    public IssuedToken rotate(long userId, String previousRaw, Duration ttl, UUID familyId, ClientInfo client) {
        return insert(userId, TokenPurpose.REFRESH, ttl, familyId, client, successorValue(previousRaw));
    }

    public Optional<IssuedToken> replacement(String previousRaw, StoredToken previous, java.time.Instant now) {
        String value = successorValue(previousRaw);
        return find(value, TokenPurpose.REFRESH)
                .filter(next -> next.userId() == previous.userId()
                        && next.familyId().equals(previous.familyId()) && next.usableAt(now))
                .map(next -> new IssuedToken(next.id(), value, next.expiresAt()));
    }

    private IssuedToken insert(
            long userId, TokenPurpose purpose, Duration ttl, UUID familyId, ClientInfo client, String value) {
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(ttl);

        long id = tokens.insert(
                userId, purpose, hash(value), familyId, expiresAt, client.userAgent(), client.ip());

        return new IssuedToken(id, value, expiresAt);
    }

    private String successorValue(String previousRaw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            byte[] material = mac.doFinal(("pegasus-refresh-rotation-v1:" + previousRaw)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required for refresh rotation", e);
        }
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

    public boolean markUsed(long tokenId) {
        return tokens.markUsed(tokenId, OffsetDateTime.now(clock));
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
