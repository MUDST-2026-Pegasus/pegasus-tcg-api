package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import java.time.OffsetDateTime;
import java.util.Set;

/** The public shape of an account. Deliberately has no password field of any kind. */
public record UserResponse(
        long id,
        String email,
        String username,
        String displayName,
        String bio,
        String phone,
        String avatarUrl,
        UserStatus status,
        Set<RoleCode> roles,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt) {

    public static UserResponse from(AuthUser user) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.username(),
                user.displayName(),
                user.bio(),
                user.phone(),
                user.avatarUrl(),
                user.status(),
                user.roles(),
                user.createdAt(),
                user.lastLoginAt());
    }

    /**
     * Builds a response using the given {@code presignedAvatarUrl} instead of the
     * raw object key stored in the database. Call this when the bucket is private
     * and the client needs a short-lived read URL.
     */
    public static UserResponse from(AuthUser user, String presignedAvatarUrl) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.username(),
                user.displayName(),
                user.bio(),
                user.phone(),
                presignedAvatarUrl,
                user.status(),
                user.roles(),
                user.createdAt(),
                user.lastLoginAt());
    }
}
