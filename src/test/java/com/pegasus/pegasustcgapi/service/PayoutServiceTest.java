package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.jooq.tables.records.PayoutRecord;
import com.pegasus.pegasustcgapi.model.PayoutAccount;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.repository.PayoutRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository.Balance;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {
    @Mock PayoutRepository payouts;
    @Mock PayoutAccountRepository accounts;
    @Mock WalletRepository wallets;
    @Mock WalletLedgerService ledger;
    @Mock SellerPort sellers;
    @Mock PlatformSettingService settings;
    @Mock StorageService storage;
    PayoutService service;

    @BeforeEach void setUp() {
        service = new PayoutService(payouts, accounts, wallets, ledger, sellers,
                settings, storage, Clock.systemUTC());
    }

    @Test void requestCannotSpendMoneyAlreadyReservedForAnotherPayout() {
        when(sellers.requireVerifiedSeller(7L)).thenReturn(new SellerProfile(3L, 7L,
                null, null, null, (short) 0, false, false, null));
        when(settings.getDecimal(PlatformSettingService.PAYOUT_MINIMUM_AMOUNT))
                .thenReturn(new BigDecimal("50.00"));
        when(accounts.findBySellerProfileId(3L)).thenReturn(Optional.of(new PayoutAccount(
                4L, 3L, null, null, null, null, 1L, null, null)));
        when(wallets.findByUserId(7L)).thenReturn(Optional.of(new Balance(5L,
                new BigDecimal("100.00"), BigDecimal.ZERO)));
        when(wallets.lock(5L)).thenReturn(new Balance(5L, new BigDecimal("100.00"), BigDecimal.ZERO));
        when(wallets.reservedForPayout(3L)).thenReturn(new BigDecimal("40.00"));

        assertThatThrownBy(() -> service.request(7L, new BigDecimal("70.00")))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).create(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test void paidDebitsWalletOnlyAfterTransferProofAndProcessingState() {
        PayoutRecord payout = new PayoutRecord();
        payout.setStatus("PROCESSING");
        payout.setWalletId(5L);
        payout.setSellerProfileId(3L);
        payout.setAmount(new BigDecimal("70.00"));
        when(payouts.lock(9L)).thenReturn(Optional.of(payout));
        when(payouts.transition(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq("PROCESSING"), org.mockito.ArgumentMatchers.eq("PAID"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("BANK-1"), org.mockito.ArgumentMatchers.eq("slip.png"), any()))
                .thenReturn(1);

        service.paid(9L, "BANK-1", "slip.png");

        verify(storage).requireUploadedFor(UploadPurpose.PAYOUT_SLIP, "slip.png");
        verify(ledger).post(5L, WalletLedgerService.Bucket.AVAILABLE, "PAYOUT_OUT",
                WalletLedgerService.Direction.DEBIT, new BigDecimal("70.00"), "PAYOUT", 9L);
        verify(payouts).linkPaidOrders(9L, 3L, new BigDecimal("70.00"));
    }

    @Test void payoutInWrongStateCannotDebitWallet() {
        PayoutRecord payout = new PayoutRecord();
        payout.setStatus("APPROVED");
        when(payouts.lock(9L)).thenReturn(Optional.of(payout));

        assertThatThrownBy(() -> service.paid(9L, "BANK-1", "slip.png"))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(ledger, storage);
    }
}
