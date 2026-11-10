package com.pegasus.pegasustcgapi.repository.auth;

import static com.pegasus.pegasustcgapi.jooq.tables.UserIdentity.USER_IDENTITY;

import com.pegasus.pegasustcgapi.jooq.tables.records.UserIdentityRecord;
import com.pegasus.pegasustcgapi.model.auth.IdentityProvider;
import com.pegasus.pegasustcgapi.model.auth.UserIdentity;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code user_identity} rows linking external OAuth/OIDC identities to user accounts. */
@Repository
public class UserIdentityRepository {

    private final DSLContext dsl;

    public UserIdentityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String providerSubject) {
        return dsl.selectFrom(USER_IDENTITY)
                .where(USER_IDENTITY.PROVIDER.eq(provider.name()))
                .and(USER_IDENTITY.PROVIDER_SUBJECT.eq(providerSubject))
                .fetchOptional()
                .map(UserIdentityRepository::toUserIdentity);
    }

    public Optional<UserIdentity> findByUserIdAndProvider(long userId, IdentityProvider provider) {
        return dsl.selectFrom(USER_IDENTITY)
                .where(USER_IDENTITY.USER_ID.eq(userId))
                .and(USER_IDENTITY.PROVIDER.eq(provider.name()))
                .fetchOptional()
                .map(UserIdentityRepository::toUserIdentity);
    }

    public boolean existsByUserIdAndProvider(long userId, IdentityProvider provider) {
        return dsl.fetchExists(dsl.selectOne()
                .from(USER_IDENTITY)
                .where(USER_IDENTITY.USER_ID.eq(userId))
                .and(USER_IDENTITY.PROVIDER.eq(provider.name())));
    }

    public long insert(long userId, IdentityProvider provider, String providerSubject, OffsetDateTime lastLoginAt) {
        return dsl.insertInto(USER_IDENTITY)
                .set(USER_IDENTITY.USER_ID, userId)
                .set(USER_IDENTITY.PROVIDER, provider.name())
                .set(USER_IDENTITY.PROVIDER_SUBJECT, providerSubject)
                .set(USER_IDENTITY.LAST_LOGIN_AT, lastLoginAt)
                .returningResult(USER_IDENTITY.ID)
                .fetchSingle(USER_IDENTITY.ID);
    }

    public void updateLastLoginAt(long id, OffsetDateTime lastLoginAt) {
        dsl.update(USER_IDENTITY)
                .set(USER_IDENTITY.LAST_LOGIN_AT, lastLoginAt)
                .where(USER_IDENTITY.ID.eq(id))
                .execute();
    }

    private static UserIdentity toUserIdentity(UserIdentityRecord r) {
        return new UserIdentity(
                r.getId(),
                r.getUserId(),
                IdentityProvider.valueOf(r.getProvider()),
                r.getProviderSubject(),
                r.getCreatedAt(),
                r.getLastLoginAt());
    }
}
