package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock PaymentRepository payments;
    @Mock EscrowRepository escrow;
    @Mock WalletLedgerService ledger;
    @Mock OrderLifecycleService orders;
    @Mock PlatformSettingService settings;
    @Mock StorageService storage;
    PaymentService service;
    AuthPrincipal buyer = new AuthPrincipal(7, null, null, Set.of());

    @BeforeEach void setUp() {
        service = new PaymentService(payments, escrow, ledger, orders, settings, storage, Clock.systemUTC());
    }

    @Test void mockPaymentMovesEachSellerShareIntoEscrow() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setBuyerId(7L);
        order.setStatus("PENDING_PAYMENT");
        PaymentRecord payment = new PaymentRecord();
        payment.setId(21L);
        payment.setSalesOrderId(11L);
        payment.setAmount(new BigDecimal("150.00"));
        SellerOrderRecord first = new SellerOrderRecord();
        first.setId(31L);
        first.setGrandTotal(new BigDecimal("100.00"));
        SellerOrderRecord second = new SellerOrderRecord();
        second.setId(32L);
        second.setGrandTotal(new BigDecimal("50.00"));
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));
        when(payments.activeForOrder(11L)).thenReturn(Optional.empty());
        when(payments.unpaidTotal(11L)).thenReturn(new BigDecimal("150.00"));
        when(payments.insert(eq(11L), eq("MOCK_GATEWAY"), eq(new BigDecimal("150.00")), any()))
                .thenReturn(payment);
        when(payments.succeed(eq(21L), eq(null), any())).thenReturn(1);
        when(ledger.platformWalletId()).thenReturn(1L);
        when(payments.unpaidSuborders(11L)).thenReturn(List.of(first, second));

        service.payMock(buyer, 11L);

        verify(ledger).post(1L, WalletLedgerService.Bucket.AVAILABLE, "PAYMENT_IN",
                WalletLedgerService.Direction.CREDIT, new BigDecimal("150.00"), "PAYMENT", 21L);
        verify(escrow).hold(31L, 1L, new BigDecimal("100.00"));
        verify(escrow).hold(32L, 1L, new BigDecimal("50.00"));
        verify(ledger).post(1L, WalletLedgerService.Bucket.PENDING, "ESCROW_HOLD",
                WalletLedgerService.Direction.CREDIT, new BigDecimal("100.00"), "SELLER_ORDER", 31L);
        verify(ledger).post(1L, WalletLedgerService.Bucket.PENDING, "ESCROW_HOLD",
                WalletLedgerService.Direction.CREDIT, new BigDecimal("50.00"), "SELLER_ORDER", 32L);
        verify(orders).markOrderPaidBySystem(11L, null);
    }

    @Test void repeatedPaymentIsRejectedBeforePostingMoney() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setBuyerId(7L);
        order.setStatus("PENDING_PAYMENT");
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));
        when(payments.activeForOrder(11L)).thenReturn(Optional.of(new PaymentRecord()));

        assertThatThrownBy(() -> service.payMock(buyer, 11L)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(escrow, ledger, orders);
        verify(payments, never()).insert(eq(11L), any(), any(), any());
    }

    @Test void rejectedBankSlipNeedsReasonAndNeverMovesMoney() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setStatus("PENDING_PAYMENT");
        PaymentRecord payment = new PaymentRecord();
        payment.setId(21L);
        payment.setSalesOrderId(11L);
        payment.setStatus("AWAITING_VERIFICATION");
        payment.setAmount(new BigDecimal("150.00"));
        when(payments.find(21L)).thenReturn(Optional.of(payment));
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));
        when(payments.unpaidTotal(11L)).thenReturn(new BigDecimal("150.00"));

        assertThatThrownBy(() -> service.verify(9L, 21L, false, " "))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(escrow, ledger, orders);
        verify(payments, never()).reject(eq(21L), eq(9L), any(), any());
    }
}
