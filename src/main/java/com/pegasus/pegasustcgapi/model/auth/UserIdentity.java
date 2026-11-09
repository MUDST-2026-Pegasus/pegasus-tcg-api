package com.pegasus.pegasustcgapi.model.auth;

import java.time.OffsetDateTime;

/** A row of {@code user_identity} linking an external OAuth/OIDC subject to a {@code user_account}. */
public record UserIdentity(
        long id,
        long userId,
        IdentityProvider provider,
        String providerSubject,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt) {

    @Override
    public String toString() {
        return "UserIdentity[id=" + id + ", userId=" + userId + ", provider=" + provider
                + ", providerSubject=REDACTED]";
    }
}
