package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerProfileRecord;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code seller_profile}, the 1:1 selling-side extension of an account. */
@Repository
public class SellerProfileRepository {

    private final DSLContext dsl;

    public SellerProfileRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SellerProfile> findByUserId(long userId) {
        return dsl.selectFrom(SELLER_PROFILE)
                .where(SELLER_PROFILE.USER_ID.eq(userId))
                .fetchOptional()
                .map(SellerProfileRepository::toProfile);
    }

    /** Each seller's avatar object key, keyed by profile id; sellers without a picture are left out. */
    public Map<Long, String> avatarKeys(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.AVATAR_URL)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(ids))
                .and(USER_ACCOUNT.AVATAR_URL.isNotNull())
                .fetchMap(SELLER_PROFILE.ID, USER_ACCOUNT.AVATAR_URL);
    }

    /** How a shop is linked to from a coupon: its name and the username of its store page. */
    public record Storefront(long sellerProfileId, String name, String username) {
    }

    public Map<Long, Storefront> storefronts(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return dsl.select(SELLER_PROFILE.ID, DSL.coalesce(USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.USERNAME),
                        USER_ACCOUNT.USERNAME)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(ids))
                .fetchMap(SELLER_PROFILE.ID, r -> new Storefront(r.value1(), r.value2(), r.value3()));
    }

    public Optional<SellerProfile> findById(long id) {
        return dsl.selectFrom(SELLER_PROFILE)
                .where(SELLER_PROFILE.ID.eq(id))
                .fetchOptional()
                .map(SellerProfileRepository::toProfile);
    }

    /**
     * Creates the profile in {@code NOT_APPLIED}, or returns the existing one.
     *
     * <p>Idempotent so that two taps on "start selling" leave one profile; the
     * unique key on {@code user_id} is what makes that safe under a race.
     */
    public long createIfAbsent(long userId) {
        dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, userId)
                .set(SELLER_PROFILE.STATUS, SellerStatus.NOT_APPLIED.name())
                .onConflict(SELLER_PROFILE.USER_ID)
                .doNothing()
                .execute();

        return dsl.select(SELLER_PROFILE.ID)
                .from(SELLER_PROFILE)
                .where(SELLER_PROFILE.USER_ID.eq(userId))
                .fetchSingle(SELLER_PROFILE.ID);
    }

    public void updateStatus(long id, SellerStatus status, OffsetDateTime verifiedAt, String suspendedReason) {
        dsl.update(SELLER_PROFILE)
                .set(SELLER_PROFILE.STATUS, status.name())
                .set(SELLER_PROFILE.VERIFIED_AT, verifiedAt)
                .set(SELLER_PROFILE.SUSPENDED_REASON, suspendedReason)
                .where(SELLER_PROFILE.ID.eq(id))
                .execute();
    }

    public void updateSettings(long id, short handlingDays, boolean vacationMode, boolean autoAcceptOrders) {
        dsl.update(SELLER_PROFILE)
                .set(SELLER_PROFILE.HANDLING_DAYS, handlingDays)
                .set(SELLER_PROFILE.VACATION_MODE, vacationMode)
                .set(SELLER_PROFILE.AUTO_ACCEPT_ORDERS, autoAcceptOrders)
                .where(SELLER_PROFILE.ID.eq(id))
                .execute();
    }

    private static SellerProfile toProfile(SellerProfileRecord r) {
        return new SellerProfile(
                r.getId(),
                r.getUserId(),
                SellerStatus.valueOf(r.getStatus()),
                r.getVerifiedAt(),
                r.getSuspendedReason(),
                r.getHandlingDays(),
                r.getVacationMode(),
                r.getAutoAcceptOrders(),
                r.getCreatedAt());
    }
}
