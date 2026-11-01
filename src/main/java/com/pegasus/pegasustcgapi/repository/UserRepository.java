package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.AppRole.APP_ROLE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static com.pegasus.pegasustcgapi.jooq.tables.UserRole.USER_ROLE;

import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.jooq.tables.records.UserAccountRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes {@code user_account}. Every lookup skips soft-deleted rows,
 * so a closed account behaves like a missing one everywhere above this class.
 */
@Repository
public class UserRepository {

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** @param email must already be lower-cased; the column is stored folded. */
    public Optional<AuthUser> findByEmail(String email) {
        return dsl.selectFrom(USER_ACCOUNT)
                .where(USER_ACCOUNT.EMAIL.eq(email))
                .and(USER_ACCOUNT.DELETED_AT.isNull())
                .fetchOptional()
                .map(UserRepository::toAuthUser);
    }

    public Optional<AuthUser> findByUsername(String username) {
        return dsl.selectFrom(USER_ACCOUNT)
                .where(USER_ACCOUNT.USERNAME.eq(username))
                .and(USER_ACCOUNT.DELETED_AT.isNull())
                .fetchOptional()
                .map(UserRepository::toAuthUser);
    }

    public Optional<AuthUser> findById(long id) {
        return dsl.selectFrom(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(id))
                .and(USER_ACCOUNT.DELETED_AT.isNull())
                .fetchOptional()
                .map(UserRepository::toAuthUser);
    }

    public boolean existsByEmail(String email) {
        return dsl.fetchExists(dsl.selectOne().from(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq(email)));
    }

    public boolean existsByUsername(String username) {
        return dsl.fetchExists(dsl.selectOne().from(USER_ACCOUNT).where(USER_ACCOUNT.USERNAME.eq(username)));
    }

    /** @return the generated id. */
    public long insert(NewUser user) {
        return dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, user.email())
                .set(USER_ACCOUNT.PASSWORD_HASH, user.passwordHash())
                .set(USER_ACCOUNT.USERNAME, user.username())
                .set(USER_ACCOUNT.DISPLAY_NAME, user.displayName())
                .set(USER_ACCOUNT.PHONE, user.phone())
                .set(USER_ACCOUNT.STATUS, UserStatus.ACTIVE)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);
    }

    /** Also clears the lockout, since a password change ends any brute-force attempt. */
    public void updatePasswordHash(long userId, String passwordHash) {
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordHash)
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, (short) 0)
                .setNull(USER_ACCOUNT.LOCKED_UNTIL)
                .where(USER_ACCOUNT.ID.eq(userId))
                .execute();
    }

    /**
     * Updates only the profile columns whose keys appear in {@code fields}.
     * This prevents a lost-update when two concurrent requests each touch
     * different columns.
     *
     * <p>Recognised keys: {@code displayName}, {@code bio}, {@code phone},
     * {@code avatarUrl}. A {@code null} value in the map sets the column to
     * SQL {@code NULL} (clears the field).
     */
    public void updateProfileSelective(long userId, Map<String, Object> fields) {
        Map<Field<?>, Object> columns = new LinkedHashMap<>();
        fields.forEach((name, value) -> {
            switch (name) {
                case "displayName" -> columns.put(USER_ACCOUNT.DISPLAY_NAME, value);
                case "bio"         -> columns.put(USER_ACCOUNT.BIO, value);
                case "phone"       -> columns.put(USER_ACCOUNT.PHONE, value);
                case "phonePublic" -> columns.put(USER_ACCOUNT.PHONE_PUBLIC, value);
                case "avatarUrl"   -> columns.put(USER_ACCOUNT.AVATAR_URL, value);
            }
        });
        if (columns.isEmpty()) return;
        dsl.update(USER_ACCOUNT)
                .set(columns)
                .where(USER_ACCOUNT.ID.eq(userId))
                .and(USER_ACCOUNT.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Whether the given avatar key is already claimed by a different user. The
     * key is visible inside presigned URLs on public pages, so without this
     * check another user could copy it.
     */
    public boolean avatarKeyUsedByAnother(String avatarUrl, long userId) {
        return dsl.fetchExists(dsl.selectOne().from(USER_ACCOUNT)
                .where(USER_ACCOUNT.AVATAR_URL.eq(avatarUrl))
                .and(USER_ACCOUNT.ID.ne(userId))
                .and(USER_ACCOUNT.DELETED_AT.isNull()));
    }

    public void recordSuccessfulLogin(long userId, OffsetDateTime at) {
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.LAST_LOGIN_AT, at)
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, (short) 0)
                .setNull(USER_ACCOUNT.LOCKED_UNTIL)
                .where(USER_ACCOUNT.ID.eq(userId))
                .execute();
    }

    /**
     * Counts the attempt in the database rather than in memory, so the limit holds
     * across instances.
     *
     * <p>Commits in its own transaction: the caller rejects the login by throwing,
     * and that rollback would otherwise erase the very attempt being counted —
     * leaving the lockout unreachable.
     *
     * @param lockedUntil {@code null} while the account is still under the limit.
     */
    /**
     * Counts a login attempt before its password is checked, in one statement, and
     * says which attempt it is.
     *
     * <p>Reading the counter and writing it back later would let a burst of parallel
     * logins all read the same count and all get a guess in; an in-place increment
     * makes Postgres hand out each number once. Commits on its own for the same
     * reason as {@link #recordFailedLogin}, and so a parallel attempt sees it at once.
     *
     * @return empty when the account is locked as of {@code now}, so nothing was counted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Short> reserveLoginAttempt(long userId, OffsetDateTime now) {
        return dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS.plus((short) 1))
                .where(USER_ACCOUNT.ID.eq(userId))
                .and(USER_ACCOUNT.LOCKED_UNTIL.isNull().or(USER_ACCOUNT.LOCKED_UNTIL.le(now)))
                .returningResult(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS)
                .fetchOptional()
                .map(r -> r.value1());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedLogin(long userId, short attempts, OffsetDateTime lockedUntil) {
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, attempts)
                .set(USER_ACCOUNT.LOCKED_UNTIL, lockedUntil)
                .where(USER_ACCOUNT.ID.eq(userId))
                .execute();
    }

    /**
     * One page of the admin user list, newest account first.
     *
     * @param text matches email, username or display name, case-insensitively; may be blank
     * @param role only accounts holding this role; may be {@code null}
     * @param status only accounts in this state; may be {@code null}
     */
    public List<AuthUser> search(String text, RoleCode role, UserStatus status, int limit, int offset) {
        return dsl.selectFrom(USER_ACCOUNT)
                .where(searchCondition(text, role, status))
                .orderBy(USER_ACCOUNT.CREATED_AT.desc(), USER_ACCOUNT.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch()
                .map(UserRepository::toAuthUser);
    }

    public long countSearch(String text, RoleCode role, UserStatus status) {
        return dsl.fetchCount(USER_ACCOUNT, searchCondition(text, role, status));
    }

    /** @return false when no live account has that id. */
    public boolean updateStatus(long userId, UserStatus status) {
        return dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.STATUS, status)
                .where(USER_ACCOUNT.ID.eq(userId))
                .and(USER_ACCOUNT.DELETED_AT.isNull())
                .execute() > 0;
    }

    private static Condition searchCondition(String text, RoleCode role, UserStatus status) {
        Condition condition = USER_ACCOUNT.DELETED_AT.isNull();
        if (text != null && !text.isBlank()) {
            // Real ILIKE with the wildcards in the input escaped, so "50%" is searched literally.
            String pattern = "%" + text.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%";
            condition = condition.and(
                    DSL.condition("{0} ILIKE {1} ESCAPE '\\'", USER_ACCOUNT.EMAIL, DSL.val(pattern))
                            .or(DSL.condition("{0} ILIKE {1} ESCAPE '\\'", USER_ACCOUNT.USERNAME, DSL.val(pattern)))
                            .or(DSL.condition("{0} ILIKE {1} ESCAPE '\\'", USER_ACCOUNT.DISPLAY_NAME, DSL.val(pattern))));
        }
        if (role != null) {
            condition = condition.and(DSL.exists(DSL.selectOne()
                    .from(USER_ROLE)
                    .join(APP_ROLE).on(APP_ROLE.ID.eq(USER_ROLE.ROLE_ID))
                    .where(USER_ROLE.USER_ID.eq(USER_ACCOUNT.ID))
                    .and(APP_ROLE.CODE.eq(role.name()))));
        }
        if (status != null) {
            condition = condition.and(USER_ACCOUNT.STATUS.eq(status));
        }
        return condition;
    }

    private static AuthUser toAuthUser(UserAccountRecord r) {
        return new AuthUser(
                r.getId(),
                r.getEmail(),
                r.getUsername(),
                r.getDisplayName(),
                r.getPasswordHash(),
                r.getBio(),
                r.getPhone(),
                r.getAvatarUrl(),
                r.getStatus(),
                r.getFailedLoginAttempts(),
                r.getLastLoginAt(),
                r.getLockedUntil(),
                r.getCreatedAt(),
                Set.of(), r.getPhonePublic());
    }

    /** Fields a registration supplies; everything else comes from column defaults. */
    public record NewUser(
            String email,
            String username,
            String displayName,
            String passwordHash,
            String phone) {
    }
}
