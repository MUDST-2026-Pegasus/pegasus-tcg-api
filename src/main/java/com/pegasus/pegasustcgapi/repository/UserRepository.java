package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.jooq.tables.records.UserAccountRecord;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jooq.Field;
import org.jooq.DSLContext;
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedLogin(long userId, short attempts, OffsetDateTime lockedUntil) {
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, attempts)
                .set(USER_ACCOUNT.LOCKED_UNTIL, lockedUntil)
                .where(USER_ACCOUNT.ID.eq(userId))
                .execute();
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
                Set.of());
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
