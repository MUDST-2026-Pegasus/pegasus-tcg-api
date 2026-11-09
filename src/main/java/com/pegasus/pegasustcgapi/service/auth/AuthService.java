package com.pegasus.pegasustcgapi.service.auth;

import com.pegasus.pegasustcgapi.service.user.RoleService;

import com.pegasus.pegasustcgapi.dto.auth.AuthResponse;
import com.pegasus.pegasustcgapi.dto.auth.GoogleLoginRequest;
import com.pegasus.pegasustcgapi.dto.auth.LoginRequest;
import com.pegasus.pegasustcgapi.dto.auth.RegisterRequest;
import com.pegasus.pegasustcgapi.dto.auth.TokenResponse;
import com.pegasus.pegasustcgapi.dto.user.UserResponse;
import com.pegasus.pegasustcgapi.model.auth.AuthUser;
import com.pegasus.pegasustcgapi.model.auth.IdentityProvider;
import com.pegasus.pegasustcgapi.model.auth.IssuedToken;
import com.pegasus.pegasustcgapi.model.user.RoleCode;
import com.pegasus.pegasustcgapi.model.auth.StoredToken;
import com.pegasus.pegasustcgapi.model.auth.TokenPurpose;
import com.pegasus.pegasustcgapi.model.auth.UserIdentity;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.auth.UserIdentityRepository;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.service.auth.GoogleTokenVerifier.GoogleIdentity;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;


@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final java.time.Duration REFRESH_RETRY_GRACE = java.time.Duration.ofSeconds(10);
    private static final String USERNAME_PREFIX = "user_";
    private static final String USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int USERNAME_RANDOM_LENGTH = 6;
    private static final int MAX_USERNAME_ATTEMPTS = 10;
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;

    private final UserRepository users;
    private final UserIdentityRepository userIdentities;
    private final RoleService roles;
    private final AuthTokenService authTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final StorageService storage;
    private final Clock clock;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final TransactionOperations tx;
    private final SecureRandom secureRandom = new SecureRandom();

    private final String decoyHash;

    public record GoogleAuthResult(AuthResponse response, boolean created) {
    }

    @Autowired
    public AuthService(
            UserRepository users,
            UserIdentityRepository userIdentities,
            RoleService roles,
            AuthTokenService authTokens,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            StorageService storage,
            Clock clock,
            GoogleTokenVerifier googleTokenVerifier,
            TransactionOperations tx) {

        this.users = users;
        this.userIdentities = userIdentities;
        this.roles = roles;
        this.authTokens = authTokens;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.storage = storage;
        this.clock = clock;
        this.googleTokenVerifier = googleTokenVerifier;
        this.tx = tx != null ? tx : TransactionOperations.withoutTransaction();
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthService(
            UserRepository users,
            UserIdentityRepository userIdentities,
            RoleService roles,
            AuthTokenService authTokens,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            StorageService storage,
            Clock clock,
            GoogleTokenVerifier googleTokenVerifier) {
        this(users, userIdentities, roles, authTokens, jwtService, passwordEncoder, properties,
                storage, clock, googleTokenVerifier, TransactionOperations.withoutTransaction());
    }

    public AuthService(
            UserRepository users,
            RoleService roles,
            AuthTokenService authTokens,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            StorageService storage,
            Clock clock) {
        this(users, null, roles, authTokens, jwtService, passwordEncoder, properties,
                storage, clock, new DefaultGoogleTokenVerifier(properties, clock),
                TransactionOperations.withoutTransaction());
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, ClientInfo client) {
        String email = normalizeEmail(request.email());
        String username = normalizeUsername(request.username());


        if (users.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_USED);
        }
        if (users.existsByUsername(username)) {
            throw new ConflictException(ErrorCode.USERNAME_ALREADY_USED);
        }

        Set<RoleCode> requested = requestedRoles(request.roles());

        long userId;
        try {
            userId = users.insert(new UserRepository.NewUser(
                    email,
                    username,
                    request.displayName().trim(),
                    passwordEncoder.encode(request.password()),
                    blankToNull(request.phone())));
        } catch (DuplicateKeyException e) {
            throw new ConflictException(conflictCode(e));
        }

        roles.grant(userId, requested, null);

        AuthUser user = loadWithRoles(userId);
        log.info("Registered user {} with roles {}", userId, user.roles());

        return new AuthResponse(UserResponse.from(user), startSession(user, client));
    }

    /**
     * Deliberately not one transaction. The attempt counter and the lockout commit on
     * their own ({@code REQUIRES_NEW}), and an outer transaction would hold a pooled
     * connection through the slow password hash while each of those asks for a
     * second one: a burst of wrong passwords then fills the pool and stalls every
     * request. Nothing here needs to roll back together; each step stands alone.
     */
    public AuthResponse login(LoginRequest request, ClientInfo client) {
        AuthUser user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), decoyHash);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
        }

        requireLoginAllowed(user);
        int attempt = reserveAttempt(user);

        if (user.passwordHash() == null) {
            passwordEncoder.matches(request.password(), decoyHash);
            recordFailedAttempt(user, attempt);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            recordFailedAttempt(user, attempt);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
        }

        users.recordSuccessfulLogin(user.id(), OffsetDateTime.now(clock));

        AuthUser current = loadWithRoles(user.id());
        return new AuthResponse(UserResponse.from(current), startSession(current, client));
    }

    public GoogleAuthResult loginWithGoogle(GoogleLoginRequest request, ClientInfo client) {
        GoogleIdentity googleUser = googleTokenVerifier.verify(request.credential());

        Optional<UserIdentity> existingIdentity =
                userIdentities.findByProviderAndSubject(IdentityProvider.GOOGLE, googleUser.subject());
        if (existingIdentity.isPresent()) {
            return new GoogleAuthResult(loginExistingGoogleIdentity(existingIdentity.get(), client), false);
        }

        Optional<AuthUser> existingUser = users.findByEmail(googleUser.email());
        if (existingUser.isPresent()) {
            return new GoogleAuthResult(linkAndLoginExistingAccount(existingUser.get(), googleUser, client), false);
        }

        if (users.existsByEmail(googleUser.email())) {
            Optional<UserIdentity> raced =
                    userIdentities.findByProviderAndSubject(IdentityProvider.GOOGLE, googleUser.subject());
            if (raced.isPresent()) {
                return new GoogleAuthResult(loginExistingGoogleIdentity(raced.get(), client), false);
            }
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_USED);
        }

        return createNewGoogleUser(googleUser, client);
    }

    private AuthResponse loginExistingGoogleIdentity(UserIdentity identity, ClientInfo client) {
        AuthUser user = users.findById(identity.userId())
                .orElseThrow(() -> new ForbiddenException(ErrorCode.ACCOUNT_DEACTIVATED));
        AuthUser userWithRoles = user.withRoles(roles.rolesOf(user.id()));
        requireGoogleAllowed(userWithRoles);

        return tx.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            users.recordSuccessfulLogin(user.id(), now);
            userIdentities.updateLastLoginAt(identity.id(), now);
            AuthUser current = loadWithRoles(user.id());
            return new AuthResponse(UserResponse.from(current), startSession(current, client));
        });
    }

    private AuthResponse linkAndLoginExistingAccount(
            AuthUser existingUser, GoogleIdentity googleUser, ClientInfo client) {
        AuthUser userWithRoles = existingUser.withRoles(roles.rolesOf(existingUser.id()));
        requireGoogleAllowed(userWithRoles);

        Optional<UserIdentity> existingGoogleIdentity =
                userIdentities.findByUserIdAndProvider(existingUser.id(), IdentityProvider.GOOGLE);
        if (existingGoogleIdentity.isPresent()) {
            if (existingGoogleIdentity.get().providerSubject().equals(googleUser.subject())) {
                return loginExistingGoogleIdentity(existingGoogleIdentity.get(), client);
            }
            throw new ConflictException(ErrorCode.GOOGLE_ALREADY_LINKED);
        }
        if (!isGoogleAuthoritativeEmail(googleUser)) {
            throw new ConflictException(ErrorCode.GOOGLE_LINK_NOT_ALLOWED);
        }

        try {
            return tx.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now(clock);
                userIdentities.insert(existingUser.id(), IdentityProvider.GOOGLE, googleUser.subject(), now);
                users.updatePasswordHash(existingUser.id(), null);
                authTokens.revokeAll(existingUser.id(), TokenPurpose.REFRESH);
                authTokens.revokeAll(existingUser.id(), TokenPurpose.PASSWORD_RESET);
                users.recordSuccessfulLogin(existingUser.id(), now);
                AuthUser current = loadWithRoles(existingUser.id());
                TokenResponse tokens = startSession(current, client);
                log.info("Linked existing account for user {} to Google identity", existingUser.id());
                return new AuthResponse(UserResponse.from(current), tokens);
            });
        } catch (DuplicateKeyException e) {
            Optional<UserIdentity> raced = userIdentities.findByProviderAndSubject(
                    IdentityProvider.GOOGLE, googleUser.subject());
            if (raced.isPresent() && raced.get().userId() == existingUser.id()) {
                return loginExistingGoogleIdentity(raced.get(), client);
            }
            throw new ConflictException(ErrorCode.GOOGLE_ALREADY_LINKED);
        }
    }

    private GoogleAuthResult createNewGoogleUser(GoogleIdentity googleUser, ClientInfo client) {
        for (int attempt = 1; attempt <= MAX_USERNAME_ATTEMPTS; attempt++) {
            String username = generateUsername();
            String displayName = resolveDisplayName(googleUser.name(), username);
            try {
                AuthResponse response = tx.execute(status -> {
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    long userId = users.insert(new UserRepository.NewUser(
                            googleUser.email(),
                            username,
                            displayName,
                            null,
                            null));
                    userIdentities.insert(userId, IdentityProvider.GOOGLE, googleUser.subject(), now);
                    roles.grant(userId, Set.of(RoleCode.BUYER), null);
                    AuthUser user = loadWithRoles(userId);
                    log.info("Registered Google user {} with roles {}", userId, user.roles());
                    return new AuthResponse(UserResponse.from(user), startSession(user, client));
                });
                return new GoogleAuthResult(response, true);
            } catch (DuplicateKeyException e) {
                if (isUsernameConflict(e)) {
                    if (attempt == MAX_USERNAME_ATTEMPTS) {
                        throw new ConflictException(ErrorCode.USERNAME_ALREADY_USED);
                    }
                    continue;
                }
                if (isIdentitySubjectConflict(e) || isEmailConflict(e)) {
                    Optional<UserIdentity> raced = userIdentities.findByProviderAndSubject(
                            IdentityProvider.GOOGLE, googleUser.subject());
                    if (raced.isPresent()) {
                        return new GoogleAuthResult(loginExistingGoogleIdentity(raced.get(), client), false);
                    }
                    throw new ConflictException(ErrorCode.EMAIL_ALREADY_USED);
                }
                throw new ConflictException(ErrorCode.DATA_CONFLICT);
            }
        }
        throw new ConflictException(ErrorCode.USERNAME_ALREADY_USED);
    }

    private static void requireGoogleAllowed(AuthUser user) {
        if (user.roles().contains(RoleCode.ADMIN) || user.roles().contains(RoleCode.SUPPORT)) {
            throw new ForbiddenException(ErrorCode.GOOGLE_SIGNIN_NOT_ALLOWED);
        }
        switch (user.status()) {
            case SUSPENDED -> throw new ForbiddenException(ErrorCode.ACCOUNT_SUSPENDED);
            case DEACTIVATED -> throw new ForbiddenException(ErrorCode.ACCOUNT_DEACTIVATED);
            case ACTIVE -> {
                // Allowed.
            }
        }
    }

    private static boolean isGoogleAuthoritativeEmail(GoogleIdentity googleUser) {
        if (!googleUser.emailVerified()) {
            return false;
        }
        return googleUser.email().endsWith("@gmail.com") || googleUser.hasHostedDomain();
    }

    String generateUsername() {
        StringBuilder sb = new StringBuilder(USERNAME_PREFIX.length() + USERNAME_RANDOM_LENGTH);
        sb.append(USERNAME_PREFIX);
        for (int i = 0; i < USERNAME_RANDOM_LENGTH; i++) {
            sb.append(USERNAME_ALPHABET.charAt(secureRandom.nextInt(USERNAME_ALPHABET.length())));
        }
        return sb.toString();
    }

    static String resolveDisplayName(String googleName, String fallbackUsername) {
        if (googleName == null || googleName.isBlank()) {
            return fallbackUsername;
        }
        String trimmed = googleName.trim();
        return trimmed.length() <= MAX_DISPLAY_NAME_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_DISPLAY_NAME_LENGTH);
    }

    /** Rotates the refresh token; a recently spent token may recover its successor after a lost response. */
    @Transactional
    public AuthResponse refresh(String refreshToken, ClientInfo client) {
        StoredToken token = authTokens.find(refreshToken, TokenPurpose.REFRESH)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_TOKEN));

        Instant now = clock.instant();
        if (token.spent() || token.revoked()) return retryOrReject(refreshToken, token, client, now);
        if (token.expired(now)) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        AuthUser user = users.findById(token.userId()).orElse(null);
        if (user == null || user.status() != UserStatus.ACTIVE) {
            // The account went away or was suspended after this session started.
            authTokens.revokeFamilyDetached(token.familyId());
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        if (!authTokens.markUsed(token.id())) {
            StoredToken latest = authTokens.find(refreshToken, TokenPurpose.REFRESH).orElse(token);
            return retryOrReject(refreshToken, latest, client, clock.instant());
        }

        AuthUser current = user.withRoles(roles.rolesOf(user.id()));
        IssuedToken replacement = authTokens.rotate(
                current.id(), refreshToken, properties.refreshTokenTtl(), token.familyId(), client);
        return refreshedResponse(current, replacement);
    }

    private AuthResponse retryOrReject(String raw, StoredToken previous, ClientInfo client, Instant now) {
        if (previous.spent() && !previous.revoked()
                && !previous.usedAt().toInstant().plus(REFRESH_RETRY_GRACE).isBefore(now)) {
            var replacement = authTokens.replacement(raw, previous, now);
            if (replacement.isPresent()) {
                AuthUser user = users.findById(previous.userId()).orElse(null);
                if (user == null || user.status() != UserStatus.ACTIVE) {
                    authTokens.revokeFamilyDetached(previous.familyId());
                    throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
                }
                return refreshedResponse(user.withRoles(roles.rolesOf(user.id())), replacement.get());
            }
            // A rotation still in flight or already superseded is not evidence of theft.
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }
        log.warn("Refresh token replay for user {}; revoking session family {}",
                previous.userId(), previous.familyId());
        authTokens.revokeFamilyDetached(previous.familyId());
        throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
    }

    private AuthResponse refreshedResponse(AuthUser user, IssuedToken refresh) {
        JwtService.AccessToken access = jwtService.issue(user);
        long remainingMillis = java.time.Duration.between(clock.instant(), refresh.expiresAt().toInstant()).toMillis();
        long refreshSeconds = Math.max(0, (remainingMillis + 999) / 1000);
        return new AuthResponse(UserResponse.from(user), TokenResponse.bearer(
                access.value(), access.expiresInSeconds(), refresh.value(), refreshSeconds));
    }

    /**
     * Ends the session the token belongs to. Deliberately silent about an unknown
     * token: a client clearing stale credentials should not get an error.
     */
    @Transactional
    public void logout(String refreshToken) {
        authTokens.find(refreshToken, TokenPurpose.REFRESH)
                .ifPresent(token -> authTokens.revokeFamily(token.familyId()));
    }

    /** Signs the user out everywhere, including the caller's own session. */
    @Transactional
    public int logoutAll(long userId) {
        int revoked = authTokens.revokeAll(userId, TokenPurpose.REFRESH);
        log.info("Revoked {} refresh tokens for user {}", revoked, userId);
        return revoked;
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(long userId) {
        AuthUser user = loadWithRoles(userId);
        String signedAvatar = user.avatarUrl() == null ? null
                : storage.presignDownload(user.avatarUrl());
        return UserResponse.from(user, signedAvatar);
    }

    /** Opens a brand new session; used by login, registration and password changes. */
    public TokenResponse startSession(AuthUser user, ClientInfo client) {
        return issueTokenPair(user, UUID.randomUUID(), client);
    }

    public AuthUser loadWithRoles(long userId) {
        AuthUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
        return user.withRoles(roles.rolesOf(userId));
    }

    private TokenResponse issueTokenPair(AuthUser user, UUID familyId, ClientInfo client) {
        JwtService.AccessToken access = jwtService.issue(user);
        IssuedToken refresh = authTokens.issue(
                user.id(), TokenPurpose.REFRESH, properties.refreshTokenTtl(), familyId, client);

        return TokenResponse.bearer(
                access.value(),
                access.expiresInSeconds(),
                refresh.value(),
                properties.refreshTokenTtl().toSeconds());
    }

    private void requireLoginAllowed(AuthUser user) {
        if (user.lockedUntil() != null && user.lockedUntil().toInstant().isAfter(clock.instant())) {
            throw new ForbiddenException(ErrorCode.ACCOUNT_LOCKED);
        }
        switch (user.status()) {
            case SUSPENDED -> throw new ForbiddenException(ErrorCode.ACCOUNT_SUSPENDED);
            case DEACTIVATED -> throw new ForbiddenException(ErrorCode.ACCOUNT_DEACTIVATED);
            case ACTIVE -> {
                // Allowed.
            }
        }
    }

    /**
     * Counts this attempt in the database before the password is checked, so the
     * limit holds across instances and restarts and also against parallel requests:
     * each attempt gets its own number, and one past the limit is turned away
     * without a guess. A successful login resets the counter as before.
     *
     * <p>One request at a time sees exactly what it did before: attempt n is the
     * stored count plus one, and the n-th wrong password at the limit locks.
     */
    private int reserveAttempt(AuthUser user) {
        int attempt = users.reserveLoginAttempt(user.id(), OffsetDateTime.now(clock))
                // Locked by a parallel attempt since the account was read.
                .orElseThrow(() -> new ForbiddenException(ErrorCode.ACCOUNT_LOCKED));
        if (attempt > properties.maxFailedLogins()) {
            // Only parallel requests get here; the one that reached the limit locks, and
            // so does this one, in case that one never finishes.
            lock(user, attempt);
        }
        return attempt;
    }

    /**
     * The attempt is already counted. Hitting the limit starts a lockout and resets
     * the counter, giving the user a full allowance again once it lapses.
     */
    private void recordFailedAttempt(AuthUser user, int attempt) {
        if (attempt >= properties.maxFailedLogins()) {
            lock(user, attempt);
        }
    }

    private void lock(AuthUser user, int attempts) {
        OffsetDateTime until = OffsetDateTime.now(clock).plus(properties.lockoutDuration());
        users.recordFailedLogin(user.id(), (short) 0, until);
        log.warn("Locked user {} until {} after {} failed logins", user.id(), until, attempts);
        throw new ForbiddenException(ErrorCode.ACCOUNT_LOCKED);
    }

    /** ADMIN and SUPPORT are never self-service; they are granted by an existing admin. */
    private static Set<RoleCode> requestedRoles(Set<RoleCode> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of(RoleCode.BUYER);
        }
        if (!RoleCode.SELF_ASSIGNABLE.containsAll(requested)) {
            throw new ForbiddenException(
                    ErrorCode.ACCESS_DENIED, "Only " + RoleCode.SELF_ASSIGNABLE + " can be chosen at sign-up");
        }
        return requested;
    }

    /** Which unique index the database rejected on; the constraint names are in migration V1. */
    private static ErrorCode conflictCode(DuplicateKeyException e) {
        if (isUsernameConflict(e)) {
            return ErrorCode.USERNAME_ALREADY_USED;
        }
        return ErrorCode.EMAIL_ALREADY_USED;
    }

    private static boolean isUsernameConflict(DuplicateKeyException e) {
        return constraintMessage(e).contains("uq_user_account_username");
    }

    private static boolean isEmailConflict(DuplicateKeyException e) {
        return constraintMessage(e).contains("uq_user_account_email");
    }

    private static boolean isIdentitySubjectConflict(DuplicateKeyException e) {
        return constraintMessage(e).contains("uq_user_identity_subject");
    }

    private static String constraintMessage(DuplicateKeyException e) {
        return String.valueOf(e.getMostSpecificCause().getMessage());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** The column only accepts lower case; the profile path {@code /u/{username}} follows suit. */
    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
