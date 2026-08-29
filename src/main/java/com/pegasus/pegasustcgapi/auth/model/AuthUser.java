package com.pegasus.pegasustcgapi.auth.model;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * An account as the auth module works with it. Carries {@code passwordHash}, so
 * it never leaves the service layer — controllers see
 * {@link com.pegasus.pegasustcgapi.auth.dto.UserResponse} instead.
 *
 * <p>{@code roles} is filled by {@link com.pegasus.pegasustcgapi.auth.repository.RoleRepository};
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
        Set<RoleCode> roles) {

    public AuthUser withRoles(Set<RoleCode> newRoles) {
        return new AuthUser(id, email, username, displayName, passwordHash, bio, phone, avatarUrl,
                status, failedLoginAttempts, lastLoginAt, lockedUntil, createdAt, newRoles);
    }
}
