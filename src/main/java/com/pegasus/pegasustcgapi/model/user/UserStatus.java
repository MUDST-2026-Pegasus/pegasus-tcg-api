package com.pegasus.pegasustcgapi.model.user;

/**
 * Lifecycle of an account. Names match the {@code user_status} Postgres enum
 * literally — jOOQ converts by constant name.
 */
public enum UserStatus {

    ACTIVE,
    /** Blocked by an admin. */
    SUSPENDED,
    /** Closed by the user. */
    DEACTIVATED
}
