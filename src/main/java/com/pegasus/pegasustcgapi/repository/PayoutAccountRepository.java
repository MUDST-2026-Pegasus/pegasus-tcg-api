package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerPayoutAccount.SELLER_PAYOUT_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerPayoutAccountRecord;
import com.pegasus.pegasustcgapi.model.PayoutAccount;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code seller_payout_account}. One row per seller, enforced
 * by a unique key rather than by whoever remembers to check.
 */
@Repository
public class PayoutAccountRepository {

    private final DSLContext dsl;

    public PayoutAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<PayoutAccount> findBySellerProfileId(long sellerProfileId) {
        return dsl.selectFrom(SELLER_PAYOUT_ACCOUNT)
                .where(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchOptional()
                .map(PayoutAccountRepository::toAccount);
    }

    /**
     * Writes the account a verification just approved, replacing whatever was
     * there. Re-verifying to change bank therefore moves the seller's money to
     * the new account instead of leaving two to choose between.
     *
     * <p>Idempotent: approving the same request twice writes the same row twice.
     */
    public void upsert(long sellerProfileId, long verificationId, String bankCode,
            String bankName, String accountName, String accountNumber) {

        dsl.insertInto(SELLER_PAYOUT_ACCOUNT)
                .set(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_PAYOUT_ACCOUNT.VERIFICATION_ID, verificationId)
                .set(SELLER_PAYOUT_ACCOUNT.BANK_CODE, bankCode)
                .set(SELLER_PAYOUT_ACCOUNT.BANK_NAME, bankName)
                .set(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NAME, accountName)
                .set(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NUMBER, accountNumber)
                .onConflict(SELLER_PAYOUT_ACCOUNT.SELLER_PROFILE_ID)
                .doUpdate()
                .set(SELLER_PAYOUT_ACCOUNT.VERIFICATION_ID, verificationId)
                .set(SELLER_PAYOUT_ACCOUNT.BANK_CODE, bankCode)
                .set(SELLER_PAYOUT_ACCOUNT.BANK_NAME, bankName)
                .set(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NAME, accountName)
                .set(SELLER_PAYOUT_ACCOUNT.ACCOUNT_NUMBER, accountNumber)
                .execute();
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
                r.getVerifiedAt(),
                r.getCreatedAt());
    }
}
