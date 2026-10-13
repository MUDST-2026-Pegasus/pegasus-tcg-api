package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.PayoutRecord;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.repository.PayoutRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A payout is reserved on request but debited only after an admin records the real transfer. */
@Service
public class PayoutService {

    private final PayoutRepository payouts;
    private final PayoutAccountRepository accounts;
    private final WalletRepository wallets;
    private final WalletLedgerService ledger;
    private final SellerPort sellers;
    private final PlatformSettingService settings;
    private final StorageService storage;
    private final Clock clock;

    public PayoutService(PayoutRepository payouts, PayoutAccountRepository accounts,
            WalletRepository wallets, WalletLedgerService ledger, SellerPort sellers,
            PlatformSettingService settings, StorageService storage, Clock clock) {
        this.payouts = payouts;
        this.accounts = accounts;
        this.wallets = wallets;
        this.ledger = ledger;
        this.sellers = sellers;
        this.settings = settings;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PayoutRecord> mine(long userId) {
        return payouts.sellerHistory(sellers.requireProfile(userId).id());
    }

    @Transactional
    public PayoutRecord request(long userId, BigDecimal amount) {
        long sellerId = sellers.requireVerifiedSeller(userId).id();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        if (amount.compareTo(settings.getDecimal(PlatformSettingService.PAYOUT_MINIMUM_AMOUNT)) < 0) {
            throw new BadRequestException(ErrorCode.PAYOUT_BELOW_MINIMUM);
        }
        long accountId = accounts.findBySellerProfileId(sellerId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND)).id();
        long walletId = wallets.findByUserId(userId)
                .orElseThrow(() -> new ConflictException(ErrorCode.INSUFFICIENT_BALANCE)).id();
        BigDecimal available = wallets.lock(walletId).available();
        BigDecimal withdrawable = available.subtract(wallets.reservedForPayout(sellerId));
        if (withdrawable.compareTo(amount) < 0) throw new ConflictException(ErrorCode.INSUFFICIENT_BALANCE);
        return payouts.create(sellerId, walletId, accountId, amount);
    }

    @Transactional
    public PayoutRecord approve(long id, long adminId) {
        return transition(id, "REQUESTED", "APPROVED", adminId, null, null, null);
    }

    @Transactional
    public PayoutRecord processing(long id) {
        return transition(id, "APPROVED", "PROCESSING", null, null, null, null);
    }

    @Transactional
    public PayoutRecord reject(long id, String reason) {
        return transition(id, "REQUESTED", "REJECTED", null, requiredReason(reason), null, null);
    }

    @Transactional
    public PayoutRecord fail(long id, String reason) {
        return transition(id, "PROCESSING", "FAILED", null, requiredReason(reason), null, null);
    }

    @Transactional
    public PayoutRecord paid(long id, String transferReference, String slipImageKey) {
        if (transferReference == null || transferReference.isBlank()
                || slipImageKey == null || slipImageKey.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        PayoutRecord payout = payouts.lock(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYOUT_NOT_FOUND));
        if (!"PROCESSING".equals(payout.getStatus())) throw new ConflictException(ErrorCode.PAYOUT_STATE);
        storage.requireUploadedFor(UploadPurpose.PAYOUT_SLIP, slipImageKey);
        ledger.post(payout.getWalletId(), WalletLedgerService.Bucket.AVAILABLE,
                "PAYOUT_OUT", WalletLedgerService.Direction.DEBIT,
                payout.getAmount(), "PAYOUT", id);
        if (payouts.transition(id, "PROCESSING", "PAID", null, null, transferReference,
                slipImageKey, OffsetDateTime.now(clock)) != 1) {
            throw new ConflictException(ErrorCode.PAYOUT_STATE);
        }
        payouts.linkPaidOrders(id, payout.getSellerProfileId(), payout.getAmount());
        return payouts.lock(id).orElseThrow();
    }

    private PayoutRecord transition(long id, String expected, String next, Long adminId,
            String reason, String reference, String slipKey) {
        PayoutRecord payout = payouts.lock(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYOUT_NOT_FOUND));
        if (!expected.equals(payout.getStatus()) || payouts.transition(id, expected, next,
                adminId, reason, reference, slipKey, OffsetDateTime.now(clock)) != 1) {
            throw new ConflictException(ErrorCode.PAYOUT_STATE);
        }
        return payouts.lock(id).orElseThrow();
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        return reason;
    }
}
