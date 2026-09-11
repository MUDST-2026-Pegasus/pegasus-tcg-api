package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerProfileRecord;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
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
