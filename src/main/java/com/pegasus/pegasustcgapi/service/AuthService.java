package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.LoginRequest;
import com.pegasus.pegasustcgapi.dto.RegisterRequest;
import com.pegasus.pegasustcgapi.dto.TokenResponse;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.IssuedToken;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RoleService roles;
    private final AuthTokenService authTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final StorageService storage;
    private final Clock clock;


    private final String decoyHash;

    public AuthService(
            UserRepository users,
            RoleService roles,
            AuthTokenService authTokens,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            StorageService storage,
            Clock clock) {

        this.users = users;
        this.roles = roles;
        this.authTokens = authTokens;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.storage = storage;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
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

    @Transactional
    public AuthResponse login(LoginRequest request, ClientInfo client) {
        AuthUser user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), decoyHash);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
        }

        requireLoginAllowed(user);

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            recordFailedAttempt(user);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
        }

        users.recordSuccessfulLogin(user.id(), OffsetDateTime.now(clock));

        AuthUser current = loadWithRoles(user.id());
        return new AuthResponse(UserResponse.from(current), startSession(current, client));
    }

    /**
     * Rotates the presented refresh token. A token that was already spent or
     * revoked is treated as theft: the family is killed, which signs out the
     * device that legitimately holds the current token as well.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken, ClientInfo client) {
        StoredToken token = authTokens.find(refreshToken, TokenPurpose.REFRESH)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_TOKEN));

        Instant now = clock.instant();
        if (token.spent() || token.revoked()) {
            log.warn("Refresh token replay for user {}; revoking session family {}",
                    token.userId(), token.familyId());
            authTokens.revokeFamilyDetached(token.familyId());
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }
        if (token.expired(now)) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        AuthUser user = users.findById(token.userId()).orElse(null);
        if (user == null || user.status() != UserStatus.ACTIVE) {
            // The account went away or was suspended after this session started.
            authTokens.revokeFamilyDetached(token.familyId());
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        authTokens.markUsed(token.id());

        AuthUser current = user.withRoles(roles.rolesOf(user.id()));
        return new AuthResponse(
                UserResponse.from(current),
                issueTokenPair(current, token.familyId(), client));
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
     * Counts the attempt in the database so the limit holds across instances and
     * restarts. Hitting the limit starts a lockout and resets the counter, giving
     * the user a full allowance again once it lapses.
     */
    private void recordFailedAttempt(AuthUser user) {
        int attempts = user.failedLoginAttempts() + 1;

        if (attempts >= properties.maxFailedLogins()) {
            OffsetDateTime until = OffsetDateTime.now(clock).plus(properties.lockoutDuration());
            users.recordFailedLogin(user.id(), (short) 0, until);
            log.warn("Locked user {} until {} after {} failed logins", user.id(), until, attempts);
            throw new ForbiddenException(ErrorCode.ACCOUNT_LOCKED);
        }

        users.recordFailedLogin(user.id(), (short) attempts, null);
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
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uq_user_account_username")) {
            return ErrorCode.USERNAME_ALREADY_USED;
        }
        return ErrorCode.EMAIL_ALREADY_USED;
    }

    private static String normalizeEmail(String email) {
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
