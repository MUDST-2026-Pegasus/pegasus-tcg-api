package com.pegasus.pegasustcgapi.auth.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.AppRole.APP_ROLE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserRole.USER_ROLE;

import com.pegasus.pegasustcgapi.auth.model.RoleCode;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code app_role} and maintains {@code user_role}. Role rows are seeded by
 * migration, so this class only ever looks them up — it never creates one.
 */
@Repository
public class RoleRepository {

    private final DSLContext dsl;

    public RoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Short> findIdByCode(RoleCode code) {
        return dsl.select(APP_ROLE.ID)
                .from(APP_ROLE)
                .where(APP_ROLE.CODE.eq(code.name()))
                .fetchOptional(APP_ROLE.ID);
    }

    public Set<RoleCode> findCodesByUserId(long userId) {
        Set<RoleCode> codes = EnumSet.noneOf(RoleCode.class);
        dsl.select(APP_ROLE.CODE)
                .from(USER_ROLE)
                .join(APP_ROLE).on(APP_ROLE.ID.eq(USER_ROLE.ROLE_ID))
                .where(USER_ROLE.USER_ID.eq(userId))
                .fetch(APP_ROLE.CODE)
                .forEach(code -> toRoleCode(code).ifPresent(codes::add));
        return codes;
    }

    /**
     * Idempotent: granting a role the user already holds leaves the original
     * {@code granted_at} and {@code granted_by} alone.
     *
     * @param grantedBy the acting admin, or {@code null} when the user chose the role at sign-up.
     * @return true when the row was actually created.
     */
    public boolean grant(long userId, short roleId, Long grantedBy) {
        return dsl.insertInto(USER_ROLE)
                .set(USER_ROLE.USER_ID, userId)
                .set(USER_ROLE.ROLE_ID, roleId)
                .set(USER_ROLE.GRANTED_BY, grantedBy)
                .onConflictDoNothing()
                .execute() > 0;
    }

    /** @return true when the user actually held the role. */
    public boolean revoke(long userId, short roleId) {
        return dsl.deleteFrom(USER_ROLE)
                .where(USER_ROLE.USER_ID.eq(userId))
                .and(USER_ROLE.ROLE_ID.eq(roleId))
                .execute() > 0;
    }

    /** A code present in the table but unknown to the enum is ignored rather than fatal. */
    private static Optional<RoleCode> toRoleCode(String code) {
        try {
            return Optional.of(RoleCode.valueOf(code));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
