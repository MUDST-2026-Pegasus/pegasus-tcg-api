package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.PayoutAccount;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a seller's money goes.
 *
 * <p>Approval already copies across the account from the verification, so most
 * sellers never call any of this. It exists for the seller who later changes
 * bank, or keeps more than one account.
 */
@Service
public class PayoutAccountService {

    private final PayoutAccountRepository accounts;
    private final SellerOnboardingService sellers;

    public PayoutAccountService(PayoutAccountRepository accounts, SellerOnboardingService sellers) {
        this.accounts = accounts;
        this.sellers = sellers;
    }

    public List<PayoutAccount> listMine(long userId) {
        return accounts.findBySellerProfileId(sellers.requireProfile(userId).id());
    }

    /** The first account a seller adds becomes the default, so a payout always has a target. */
    @Transactional
    public PayoutAccount create(long userId, String bankCode, String bankName,
            String accountName, String accountNumber, boolean makeDefault) {

        long sellerProfileId = sellers.requireVerifiedSeller(userId).id();
        boolean first = accounts.findBySellerProfileId(sellerProfileId).isEmpty();
        boolean isDefault = makeDefault || first;

        if (isDefault) {
            // Clear the old default first: the partial unique index rejects two.
            accounts.clearDefault(sellerProfileId, 0L);
        }

        long id = accounts.insert(
                sellerProfileId, bankCode, bankName, accountName, accountNumber, isDefault);

        return accounts.findByIdAndSeller(id, sellerProfileId).orElseThrow(
                () -> new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND));
    }

    @Transactional
    public PayoutAccount makeDefault(long userId, long accountId) {
        long sellerProfileId = sellers.requireProfile(userId).id();
        accounts.clearDefault(sellerProfileId, accountId);

        if (!accounts.makeDefault(accountId, sellerProfileId)) {
            throw new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND);
        }
        return accounts.findByIdAndSeller(accountId, sellerProfileId).orElseThrow(
                () -> new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND));
    }

    @Transactional
    public void delete(long userId, long accountId) {
        long sellerProfileId = sellers.requireProfile(userId).id();
        if (!accounts.delete(accountId, sellerProfileId)) {
            throw new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND);
        }
    }
}
