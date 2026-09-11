package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ConflictException;
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
 * <p>There is deliberately no way to add an account here. An account appears
 * only when an admin approves a verification, so every account a seller can be
 * paid into is one somebody checked. Changing bank means submitting a new
 * verification and going through the same review.
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

    /** Picks which verified account payouts go to, when a seller has more than one. */
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

    /**
     * Removes an old account a seller no longer uses.
     *
     * <p>The last one cannot go: a verified seller with nowhere to be paid would
     * have to be re-verified to get an account back, and until then their
     * earnings have no destination.
     */
    @Transactional
    public void delete(long userId, long accountId) {
        long sellerProfileId = sellers.requireProfile(userId).id();

        if (accounts.findBySellerProfileId(sellerProfileId).size() <= 1) {
            throw new ConflictException(ErrorCode.PAYOUT_ACCOUNT_REQUIRED);
        }
        if (!accounts.delete(accountId, sellerProfileId)) {
            throw new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND);
        }
    }
}
