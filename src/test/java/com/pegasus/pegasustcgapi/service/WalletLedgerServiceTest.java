package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.LedgerRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository;
import com.pegasus.pegasustcgapi.repository.WalletRepository.Balance;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Bucket;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Direction;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletLedgerServiceTest {

    @Mock WalletRepository wallets;
    @Mock LedgerRepository ledger;
    @Mock SellerPort sellers;

    WalletLedgerService service;

    @BeforeEach
    void setUp() {
        service = new WalletLedgerService(wallets, ledger, sellers);
    }

    @Test
    void movesAvailableToPendingWithoutChangingTotal() {
        when(wallets.lock(1L)).thenReturn(new Balance(1L,
                new BigDecimal("100.00"), new BigDecimal("0.00")),
                new Balance(1L, new BigDecimal("60.00"), new BigDecimal("0.00")));

        service.post(1L, Bucket.AVAILABLE, "ESCROW_HOLD", Direction.DEBIT,
                new BigDecimal("40.00"), "SELLER_ORDER", 5L);
        service.post(1L, Bucket.PENDING, "ESCROW_HOLD", Direction.CREDIT,
                new BigDecimal("40.00"), "SELLER_ORDER", 5L);

        verify(ledger).insert(1L, "ESCROW_HOLD", "DEBIT", new BigDecimal("40.00"),
                new BigDecimal("60.00"), "SELLER_ORDER", 5L);
        verify(ledger).insert(1L, "ESCROW_HOLD", "CREDIT", new BigDecimal("40.00"),
                new BigDecimal("100.00"), "SELLER_ORDER", 5L);
    }

    @Test
    void refusesToOverdrawBeforeWritingLedger() {
        when(wallets.lock(1L)).thenReturn(new Balance(1L,
                new BigDecimal("10.00"), new BigDecimal("0.00")));

        assertThatThrownBy(() -> service.post(1L, Bucket.AVAILABLE, "PAYOUT_OUT",
                Direction.DEBIT, new BigDecimal("10.01"), "PAYOUT", 7L))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(ledger);
    }

    @Test
    void rejectsZeroAndFractionsOfSatang() {
        assertThatThrownBy(() -> service.post(1L, Bucket.AVAILABLE, "PAYMENT_IN",
                Direction.CREDIT, BigDecimal.ZERO, "PAYMENT", 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1L, Bucket.AVAILABLE, "PAYMENT_IN",
                Direction.CREDIT, new BigDecimal("0.001"), "PAYMENT", 9L))
                .isInstanceOf(ArithmeticException.class);
        verifyNoInteractions(wallets, ledger);
    }
}
