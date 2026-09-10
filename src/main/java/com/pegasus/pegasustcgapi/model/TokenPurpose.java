package com.pegasus.pegasustcgapi.model;

/**
 * What a row in {@code auth_token} is for. Names match the {@code token_purpose}
 * Postgres enum literally — jOOQ converts by constant name.
 */
public enum TokenPurpose {

    REFRESH,
    PASSWORD_RESET
}
