package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.LedgerRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository.Balance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every wallet movement is one locked balance update and one immutable ledger row.
 * A caller's business event and all its movements must share one transaction.
 */
@Service
public class WalletLedgerService {

    static final String PROMO_SUBSIDY = "PROMO_SUBSIDY";

    public enum Bucket { AVAILABLE, PENDING }
    public enum Direction { DEBIT, CREDIT }

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final SellerPort sellers;

    public WalletLedgerService(WalletRepository wallets, LedgerRepository ledger, SellerPort sellers) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.sellers = sellers;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(long walletId, Bucket bucket, String type, Direction direction,
            BigDecimal amount, String referenceType, long referenceId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive");
        }
        BigDecimal money = amount.setScale(2, RoundingMode.UNNECESSARY);
        Balance current = wallets.lock(walletId);
        BigDecimal change = direction == Direction.CREDIT ? money : money.negate();
        Balance next = bucket == Bucket.AVAILABLE
                ? new Balance(walletId, current.available().add(change), current.pending())
                : new Balance(walletId, current.available(), current.pending().add(change));
        // Pegasus paying a shop back for a Pegasus code may run its own balance below zero
        // [TCG-570]; nothing else may overdraw anything.
        boolean subsidy = PROMO_SUBSIDY.equals(type) && walletId == platformWalletId();
        if ((next.available().signum() < 0 && !subsidy) || next.pending().signum() < 0) {
            throw new ConflictException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        wallets.update(next);
        ledger.insert(walletId, type, direction.name(), money, next.total(), referenceType, referenceId);
    }

    public long platformWalletId() {
        return wallets.platformId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long ensureSellerWallet(long userId) {
        return wallets.ensureSeller(userId);
    }

    @Transactional(readOnly = true)
    public WalletView sellerWallet(long userId, int page, int size) {
        long sellerProfileId = sellers.requireProfile(userId).id();
        Paging paging = Paging.of(page, size);
        BigDecimal reserved = wallets.reservedForPayout(sellerProfileId);
        return wallets.findByUserId(userId)
                .map(wallet -> new WalletView(wallet.available(), wallet.pending(),
                        reserved, wallet.available().subtract(reserved).max(BigDecimal.ZERO),
                        PageResponse.of(ledger.page(wallet.id(), paging.size(), paging.offset()),
                                paging.page(), paging.size(), ledger.count(wallet.id()))))
                .orElseGet(() -> new WalletView(BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), reserved, BigDecimal.ZERO.setScale(2),
                        PageResponse.of(List.of(), paging.page(), paging.size(), 0)));
    }

    public record WalletView(BigDecimal balanceAvailable, BigDecimal balancePending,
            BigDecimal reservedForPayout, BigDecimal withdrawable,
            PageResponse<LedgerRepository.Entry> entries) {
    }
}
