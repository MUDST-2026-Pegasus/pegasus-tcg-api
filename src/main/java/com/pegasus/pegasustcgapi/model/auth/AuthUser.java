package com.pegasus.pegasustcgapi.model.auth;

import com.pegasus.pegasustcgapi.model.user.RoleCode;
import com.pegasus.pegasustcgapi.model.user.UserStatus;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * An account as the auth module works with it. Carries {@code passwordHash}, so
 * it never leaves the service layer — controllers see
 * {@link com.pegasus.pegasustcgapi.dto.user.UserResponse} instead.
 *
 * <p>{@code roles} is filled by {@link com.pegasus.pegasustcgapi.repository.user.RoleRepository};
 * a user loaded without them holds an empty set.
 */
public record AuthUser(
        long id,
        String email,
        String username,
        String displayName,
        String passwordHash,
        String bio,
        String phone,
        String avatarUrl,
        UserStatus status,
        short failedLoginAttempts,
        OffsetDateTime lastLoginAt,
        OffsetDateTime lockedUntil,
        OffsetDateTime createdAt,
        Set<RoleCode> roles,
        boolean phonePublic,
        OffsetDateTime emailVerifiedAt) {

    public AuthUser(long id, String email, String username, String displayName, String passwordHash,
            String bio, String phone, String avatarUrl, UserStatus status, short failedLoginAttempts,
            OffsetDateTime lastLoginAt, OffsetDateTime lockedUntil, OffsetDateTime createdAt,
            Set<RoleCode> roles, boolean phonePublic) {
        this(id, email, username, displayName, passwordHash, bio, phone, avatarUrl, status,
                failedLoginAttempts, lastLoginAt, lockedUntil, createdAt, roles, phonePublic, null);
    }

    public AuthUser(long id, String email, String username, String displayName, String passwordHash,
            String bio, String phone, String avatarUrl, UserStatus status, short failedLoginAttempts,
            OffsetDateTime lastLoginAt, OffsetDateTime lockedUntil, OffsetDateTime createdAt,
            Set<RoleCode> roles) {
        this(id, email, username, displayName, passwordHash, bio, phone, avatarUrl, status,
                failedLoginAttempts, lastLoginAt, lockedUntil, createdAt, roles, false, null);
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public AuthUser withRoles(Set<RoleCode> newRoles) {
        return new AuthUser(id, email, username, displayName, passwordHash, bio, phone, avatarUrl,
                status, failedLoginAttempts, lastLoginAt, lockedUntil, createdAt, newRoles, phonePublic, emailVerifiedAt);
    }
}
