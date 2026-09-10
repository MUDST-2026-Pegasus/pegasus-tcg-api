package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller, as read from a verified access token. Controllers take this as a
 * parameter instead of reaching for the {@code SecurityContextHolder}.
 *
 * <p>The fields are a snapshot from the token, so they can be up to one access
 * token TTL out of date — anything that must be current is read from the database.
 */
public record AuthPrincipal(long userId, String email, String username, Set<RoleCode> roles) {

    public static AuthPrincipal from(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        return new AuthPrincipal(
                userId,
                jwt.getClaimAsString(AuthClaims.EMAIL),
                jwt.getClaimAsString(AuthClaims.USERNAME),
                readRoles(jwt));
    }

    public boolean hasRole(RoleCode role) {
        return roles.contains(role);
    }

    /** Unknown role names are dropped: a token minted before a role was removed still works. */
    private static Set<RoleCode> readRoles(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList(AuthClaims.ROLES);
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        if (claim == null) {
            return roles;
        }
        for (String name : claim) {
            try {
                roles.add(RoleCode.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Not a role this build knows about.
            }
        }
        return roles;
    }
}
