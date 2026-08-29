package com.pegasus.pegasustcgapi.auth.security;

/** Names of the private claims this API puts in its access tokens. */
public final class AuthClaims {

    /** List of {@link com.pegasus.pegasustcgapi.auth.model.RoleCode} names. */
    public static final String ROLES = "roles";
    public static final String EMAIL = "email";
    public static final String USERNAME = "username";
    /** Always {@code access} — refresh tokens are opaque, so they never reach the decoder. */
    public static final String TOKEN_TYPE = "token_type";
    public static final String ACCESS_TOKEN_TYPE = "access";

    private AuthClaims() {
    }
}
