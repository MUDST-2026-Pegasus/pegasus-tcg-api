package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.PayoutAccount;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import org.springframework.stereotype.Service;

/**
 * Where a seller's money goes.
 *
 * <p>Read-only on purpose. A seller has exactly one payout account, it is the
 * one their verification approved, and the only way to change it is to verify a
 * new one — so there is nothing here to add, choose or delete.
 */
@Service
public class PayoutAccountService {

    private final PayoutAccountRepository accounts;
    private final SellerOnboardingService sellers;

    public PayoutAccountService(PayoutAccountRepository accounts, SellerOnboardingService sellers) {
        this.accounts = accounts;
        this.sellers = sellers;
    }

    /** @throws NotFoundException while the seller has no approved verification yet */
    public PayoutAccount mine(long userId) {
        long sellerProfileId = sellers.requireProfile(userId).id();
        return accounts.findBySellerProfileId(sellerProfileId).orElseThrow(
                () -> new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND,
                        "No payout account yet; it is created when a verification is approved"));
    }
}
