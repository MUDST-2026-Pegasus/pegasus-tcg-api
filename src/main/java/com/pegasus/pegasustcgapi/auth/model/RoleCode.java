package com.pegasus.pegasustcgapi.auth.model;

import java.util.Set;

/**
 * The {@code app_role.code} values seeded by migration V2. These strings travel
 * in the JWT {@code roles} claim, so they are part of the API contract.
 */
public enum RoleCode {

    BUYER,
    SELLER,
    ADMIN,
    SUPPORT;

    /** Roles a visitor may pick for themselves; ADMIN and SUPPORT are granted by an admin. */
    public static final Set<RoleCode> SELF_ASSIGNABLE = Set.of(BUYER, SELLER);

    /** Prefix Spring Security expects on a {@code hasRole} authority. */
    public String authority() {
        return "ROLE_" + name();
    }
}
