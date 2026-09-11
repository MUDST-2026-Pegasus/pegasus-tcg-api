package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerVerificationRecord;
import com.pegasus.pegasustcgapi.model.SellerVerification;
import com.pegasus.pegasustcgapi.model.VerificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code seller_verification} — a name and a bank account, awaiting review. */
@Repository
public class SellerVerificationRepository {

    private final DSLContext dsl;

    public SellerVerificationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SellerVerification> findById(long id) {
        return dsl.selectFrom(SELLER_VERIFICATION)
                .where(SELLER_VERIFICATION.ID.eq(id))
                .fetchOptional()
                .map(SellerVerificationRepository::toVerification);
    }

    public List<SellerVerification> findByProfileId(long sellerProfileId) {
        return dsl.selectFrom(SELLER_VERIFICATION)
                .where(SELLER_VERIFICATION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .orderBy(SELLER_VERIFICATION.SUBMITTED_AT.desc())
                .fetch(SellerVerificationRepository::toVerification);
    }

    /** The admin review queue: oldest first, so nobody waits behind a later submission. */
    public List<SellerVerification> findByStatus(VerificationStatus status, int limit, int offset) {
        return dsl.selectFrom(SELLER_VERIFICATION)
                .where(SELLER_VERIFICATION.STATUS.eq(status.name()))
                .orderBy(SELLER_VERIFICATION.SUBMITTED_AT.asc())
                .limit(limit)
                .offset(offset)
                .fetch(SellerVerificationRepository::toVerification);
    }

    public long countByStatus(VerificationStatus status) {
        return dsl.fetchCount(SELLER_VERIFICATION, SELLER_VERIFICATION.STATUS.eq(status.name()));
    }

    /** True when this profile already has a submission nobody has decided yet. */
    public boolean hasOpenRequest(long sellerProfileId) {
        return dsl.fetchExists(dsl.selectOne().from(SELLER_VERIFICATION)
                .where(SELLER_VERIFICATION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(SELLER_VERIFICATION.STATUS.in(
                        VerificationStatus.SUBMITTED.name(), VerificationStatus.UNDER_REVIEW.name())));
    }

    /**
     * One bank account backs one seller. Two profiles sharing an account would
     * leave a payout dispute with no single owner.
     *
     * @param exceptProfileId the profile submitting now, so resubmitting your own account is fine
     */
    public boolean bankAccountUsedByAnother(String bankAccountNumber, long exceptProfileId) {
        return dsl.fetchExists(dsl.selectOne().from(SELLER_VERIFICATION)
                .where(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER.eq(bankAccountNumber))
                .and(SELLER_VERIFICATION.STATUS.eq(VerificationStatus.APPROVED.name()))
                .and(SELLER_VERIFICATION.SELLER_PROFILE_ID.ne(exceptProfileId)));
    }

    public long insert(long sellerProfileId, String legalFirstName, String legalLastName,
            String bankCode, String bankName, String bankAccountNumber) {

        return dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, legalFirstName)
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, legalLastName)
                .set(SELLER_VERIFICATION.BANK_CODE, bankCode)
                .set(SELLER_VERIFICATION.BANK_NAME, bankName)
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, bankAccountNumber)
                .set(SELLER_VERIFICATION.STATUS, VerificationStatus.SUBMITTED.name())
                .returningResult(SELLER_VERIFICATION.ID)
                .fetchSingle(SELLER_VERIFICATION.ID);
    }

    /**
     * Moves a request to its next state, but only from the state the caller
     * believed it was in — so two admins acting at once cannot both decide it.
     *
     * @return true when this caller was the one that moved it
     */
    public boolean transition(long id, VerificationStatus from, VerificationStatus to,
            Long reviewedBy, OffsetDateTime reviewedAt, String rejectionReason) {

        return dsl.update(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.STATUS, to.name())
                .set(SELLER_VERIFICATION.REVIEWED_BY, reviewedBy)
                .set(SELLER_VERIFICATION.REVIEWED_AT, reviewedAt)
                .set(SELLER_VERIFICATION.REJECTION_REASON, rejectionReason)
                .where(SELLER_VERIFICATION.ID.eq(id))
                .and(SELLER_VERIFICATION.STATUS.eq(from.name()))
                .execute() > 0;
    }

    private static SellerVerification toVerification(SellerVerificationRecord r) {
        return new SellerVerification(
                r.getId(),
                r.getSellerProfileId(),
                r.getLegalFirstName(),
                r.getLegalLastName(),
                r.getBankCode(),
                r.getBankName(),
                r.getBankAccountNumber(),
                VerificationStatus.valueOf(r.getStatus()),
                r.getSubmittedAt(),
                r.getReviewedBy(),
                r.getReviewedAt(),
                r.getRejectionReason());
    }
}
