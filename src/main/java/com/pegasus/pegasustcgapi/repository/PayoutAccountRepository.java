package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerPayoutAccount.SELLER_PAYOUT_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerPayoutAccountRecord;
import com.pegasus.pegasustcgapi.model.PayoutAccount;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code seller_payout_account} — where a seller's money is sent. */
@Repository
public class PayoutAccountRepository {

    private final DSLContext dsl;

    public PayoutAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<PayoutAccount> findBySellerProfileId(long sellerProfileId) {
        return dsl.selectFrom(SELLER_PAYOUT_ACCOUNT)
                .where(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .orderBy(SELLER_PAYOUT_ACCOUNT.IS_DEFAULT.desc(), SELLER_PAYOUT_ACCOUNT.ID.asc())
                .fetch(PayoutAccountRepository::toAccount);
    }

    public Optional<PayoutAccount> findByIdAndSeller(long id, long sellerProfileId) {
        return dsl.selectFrom(SELLER_PAYOUT_ACCOUNT)
                .where(SELLER_PAYOUT_ACCOUNT.ID.eq(id))
                .and(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchOptional()
                .map(PayoutAccountRepository::toAccount);
    }

    /**
     * @param verificationId the approved request this account came from; the unique
     *        index on it is what makes a retried approval a no-op
     * @return the new id, or empty when this verification already produced an account
     */
    public Optional<Long> insert(long sellerProfileId, long verificationId,
            String bankCode, String bankName, String accountName, String accountNumber,
            boolean isDefault) {

        return dsl.insertInto(SELLER_PAYOUT_ACCOUNT)
                .set(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_PAYOUT_ACCOUNT.BANK_CODE, bankCode)
                .set(SELLER_PAYOUT_ACCOUNT.BANK_NAME, bankName)
                .set(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NAME, accountName)
                .set(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NUMBER, accountNumber)
                .set(SELLER_PAYOUT_ACCOUNT.VERIFICATION_ID, verificationId)
                .set(SELLER_PAYOUT_ACCOUNT.IS_DEFAULT, isDefault)
                .onConflict(SELLER_PAYOUT_ACCOUNT.VERIFICATION_ID)
                .doNothing()
                .returningResult(SELLER_PAYOUT_ACCOUNT.ID)
                .fetchOptional(SELLER_PAYOUT_ACCOUNT.ID);
    }

    /** Paired with the partial unique index, which is what really keeps "default" singular. */
    public void clearDefault(long sellerProfileId, long exceptId) {
        dsl.update(SELLER_PAYOUT_ACCOUNT)
                .set(SELLER_PAYOUT_ACCOUNT.IS_DEFAULT, false)
                .where(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(SELLER_PAYOUT_ACCOUNT.ID.ne(exceptId))
                .and(SELLER_PAYOUT_ACCOUNT.IS_DEFAULT.isTrue())
                .execute();
    }

    public boolean makeDefault(long id, long sellerProfileId) {
        return dsl.update(SELLER_PAYOUT_ACCOUNT)
                .set(SELLER_PAYOUT_ACCOUNT.IS_DEFAULT, true)
                .where(SELLER_PAYOUT_ACCOUNT.ID.eq(id))
                .and(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .execute() > 0;
    }

    public boolean delete(long id, long sellerProfileId) {
        return dsl.deleteFrom(SELLER_PAYOUT_ACCOUNT)
                .where(SELLER_PAYOUT_ACCOUNT.ID.eq(id))
                .and(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .execute() > 0;
    }

    private static PayoutAccount toAccount(SellerPayoutAccountRecord r) {
        return new PayoutAccount(
                r.getId(),
                r.getSellerProfileId(),
                r.getBankCode(),
                r.getBankName(),
                r.getAccountName(),
                r.getAccountNumber(),
                r.getVerificationId(),
                r.getIsDefault(),
                r.getVerifiedAt(),
                r.getCreatedAt());
    }
}
