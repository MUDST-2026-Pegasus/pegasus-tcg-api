package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.ChangePasswordRequest;
import com.pegasus.pegasustcgapi.dto.ResetPasswordRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.IssuedToken;
import com.pegasus.pegasustcgapi.model.StoredToken;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgotten, reset and changed passwords.
 *
 * <p>Every path that sets a new hash also revokes the account's refresh tokens:
 * if the password was changed because it leaked, the sessions opened with it
 * have to go too.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository users;
    private final AuthTokenService authTokens;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public PasswordService(
            UserRepository users,
            AuthTokenService authTokens,
            AuthService authService,
            PasswordEncoder passwordEncoder,
            AuthProperties properties) {

        this.users = users;
        this.authTokens = authTokens;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** Redeems a reset token and signs the account out everywhere. */
    @Transactional
    public void reset(ResetPasswordRequest request) {
        StoredToken token = authTokens.consume(request.token(), TokenPurpose.PASSWORD_RESET);

        users.updatePasswordHash(token.userId(), passwordEncoder.encode(request.newPassword()));
        authTokens.revokeAll(token.userId(), TokenPurpose.REFRESH);
        authTokens.revokeAll(token.userId(), TokenPurpose.PASSWORD_RESET);

        log.info("Password reset completed for user {}", token.userId());
    }

    /**
     * Changes the password of the signed-in user. Existing sessions are dropped
     * and a fresh pair is returned, so the caller stays signed in on this device
     * and only this device.
     */
    @Transactional
    public AuthResponse change(long userId, ChangePasswordRequest request, ClientInfo client) {
        AuthUser user = authService.loadWithRoles(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }

        users.updatePasswordHash(userId, passwordEncoder.encode(request.newPassword()));
        authTokens.revokeAll(userId, TokenPurpose.REFRESH);

        log.info("Password changed for user {}", userId);
        return new AuthResponse(UserResponse.from(user), authService.startSession(user, client));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
