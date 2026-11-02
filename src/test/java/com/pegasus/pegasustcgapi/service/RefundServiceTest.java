package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.EscrowHoldRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundTransferAttemptRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.repository.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.RefundRepository;
import com.pegasus.pegasustcgapi.repository.RefundTransferRepository;
import com.pegasus.pegasustcgapi.repository.ReturnRepository;
import com.pegasus.pegasustcgapi.port.CollectionPort;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {
    @Mock RefundRepository refunds;
    @Mock RefundTransferRepository transfers;
    @Mock PaymentRepository payments;
    @Mock EscrowRepository escrow;
    @Mock OrderRepository orders;
    @Mock WalletLedgerService ledger;
    @Mock ReturnRepository returns;
    @Mock ObjectProvider<OrderLifecycleService> lifecycle;
    @Mock EscrowReleaseService escrowRelease;
    @Mock CollectionPort collection;
    RefundService service;

    @BeforeEach void setUp() {
        service = new RefundService(refunds, transfers, payments, escrow, orders, ledger,
                Clock.systemUTC(), returns, lifecycle, escrowRelease, collection);
    }

    private RefundRecord refund(String status) {
        RefundRecord refund = new RefundRecord();
        refund.setId(5L);
        refund.setSalesOrderId(2L);
        refund.setSellerOrderId(3L);
        refund.setStatus(status);
        refund.setMethod("BANK_TRANSFER");
        refund.setBankName("Test Bank");
        refund.setAccountName("Buyer");
        refund.setAccountNumber("1234567890");
        return refund;
    }

    private void buyerOwns(RefundRecord refund, long buyerId) {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setBuyerId(buyerId);
        when(refunds.find(refund.getId())).thenReturn(Optional.of(refund));
        when(payments.findOrder(refund.getSalesOrderId())).thenReturn(Optional.of(order));
    }

    @Test void buyerCanSaveNormalizedDestinationForApprovedBankRefund() {
        RefundRecord refund = refund("APPROVED");
        buyerOwns(refund, 7L);
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));
        when(refunds.setDestination(5L, "Test Bank", "Buyer", "1234567890")).thenReturn(1);

        service.setDestination(5L, 7L, " Test Bank ", " Buyer ", "123 456-7890");

        verify(payments).lockOrder(2L);
        verify(refunds).setDestination(5L, "Test Bank", "Buyer", "1234567890");
    }

    @Test void anotherBuyerCannotReadOrChangeRefundDestination() {
        RefundRecord refund = refund("APPROVED");
        buyerOwns(refund, 7L);

        assertThatThrownBy(() -> service.setDestination(5L, 8L, "Bank", "Other", "1234567890"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(transfers, escrow, ledger);
        org.mockito.Mockito.verify(payments, never()).lockOrder(2L);
        org.mockito.Mockito.verify(refunds, never()).setDestination(anyLong(), any(), any(), any());
    }

    @Test void destinationRejectsInvalidAccountBeforePersisting() {
        RefundRecord refund = refund("APPROVED");
        buyerOwns(refund, 7L);
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> service.setDestination(5L, 7L, "Bank", "Buyer", "123-45"))
                .isInstanceOf(BadRequestException.class);
        verify(payments).lockOrder(2L);
        org.mockito.Mockito.verify(refunds, never()).setDestination(anyLong(), any(), any(), any());
    }

    @Test void staleBankNumberCannotStartExternalTransfer() {
        RefundRecord refund = refund("APPROVED");
        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> service.startTransfer(5L, 9L, "9876543210"))
                .isInstanceOf(ConflictException.class);
        verify(payments).lockOrder(2L);
        verifyNoInteractions(transfers, escrow, ledger);
    }

    @Test void matchingBankNumberLocksDestinationBeforeTransfer() {
        RefundRecord refund = refund("APPROVED");
        refund.setAmount(new BigDecimal("40.00"));
        EscrowHoldRecord hold = new EscrowHoldRecord();
        hold.setStatus("HELD");
        hold.setAmount(new BigDecimal("100.00"));
        hold.setRefundedAmount(BigDecimal.ZERO);
        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));
        when(escrow.lock(3L)).thenReturn(Optional.of(hold));
        when(refunds.transition(5L, "APPROVED", "PROCESSING")).thenReturn(1);

        service.startTransfer(5L, 9L, "1234567890");

        verify(transfers).start(refund, 9L);
        verify(refunds).transition(5L, "APPROVED", "PROCESSING");
        verifyNoInteractions(ledger);
    }

    @Test void failedTransferReopensBankCorrectionWithoutMovingMoney() {
        RefundRecord refund = refund("PROCESSING");
        RefundTransferAttemptRecord attempt = new RefundTransferAttemptRecord();
        attempt.setId(8L);
        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));
        when(transfers.active(5L)).thenReturn(Optional.of(attempt));
        when(refunds.transition(5L, "PROCESSING", "APPROVED")).thenReturn(1);

        service.transferFailed(5L, 9L, "Bank rejected");

        verify(transfers).failed(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq("Bank rejected"), any());
        verify(refunds).transition(5L, "PROCESSING", "APPROVED");
        verifyNoInteractions(escrow, ledger);
    }

    @Test void repeatedConfirmationWithSameReferenceDoesNotPostAgain() {
        RefundRecord refund = refund("PROCESSED");
        refund.setTransferReference("BANK-1");
        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));

        service.processed(5L, 9L, "BANK-1");

        verify(payments).lockOrder(2L);
        verifyNoInteractions(ledger, escrow, transfers);
        verify(refunds, never()).processed(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test void adminSettledBankTransferProcessesAttemptAndEscrow() {
        RefundRecord refund = refund("PROCESSING");
        refund.setAmount(new BigDecimal("40.00"));
        RefundTransferAttemptRecord attempt = new RefundTransferAttemptRecord();
        attempt.setId(8L);
        EscrowHoldRecord hold = new EscrowHoldRecord();
        hold.setId(11L);
        hold.setWalletId(12L);
        hold.setStatus("HELD");
        hold.setAmount(new BigDecimal("100.00"));
        hold.setRefundedAmount(BigDecimal.ZERO);

        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));
        when(transfers.active(5L)).thenReturn(Optional.of(attempt));
        when(escrow.lock(3L)).thenReturn(Optional.of(hold));
        when(refunds.processed(eq(5L), eq(9L), eq("BANK-REF-1"), any())).thenReturn(1);

        service.processed(5L, 9L, "BANK-REF-1");

        verify(payments).lockOrder(2L);
        verify(transfers).processed(eq(8L), eq(9L), eq("BANK-REF-1"), any());
        verify(refunds).processed(eq(5L), eq(9L), eq("BANK-REF-1"), any());
        verify(ledger).post(12L, WalletLedgerService.Bucket.PENDING, "REFUND_OUT",
                WalletLedgerService.Direction.DEBIT, new BigDecimal("40.00"), "REFUND", 5L);
        verify(escrow).refund(11L, new BigDecimal("40.00"), "CANCELLED");
    }

    @Test void bankTransferRejectsSystemSettlement() {
        RefundRecord refund = refund("PROCESSING");
        refund.setAmount(new BigDecimal("40.00"));
        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> service.processedBySystem(5L, "SYS-REF-1"))
                .isInstanceOf(ConflictException.class);

        verify(payments).lockOrder(2L);
        verifyNoInteractions(transfers, escrow, ledger);
        verify(refunds, never()).processed(anyLong(), any(), any(), any());
    }

    @Test void systemSettledMockGatewaySucceedsWithoutAdmin() {
        RefundRecord refund = refund("APPROVED");
        refund.setMethod("MOCK_GATEWAY");
        refund.setAmount(new BigDecimal("40.00"));
        EscrowHoldRecord hold = new EscrowHoldRecord();
        hold.setId(11L);
        hold.setWalletId(12L);
        hold.setStatus("HELD");
        hold.setAmount(new BigDecimal("100.00"));
        hold.setRefundedAmount(BigDecimal.ZERO);

        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));
        when(escrow.lock(3L)).thenReturn(Optional.of(hold));
        when(refunds.processed(eq(5L), isNull(), eq("MOCK:5"), any())).thenReturn(1);

        service.processedBySystem(5L, "MOCK:5");

        verify(payments).lockOrder(2L);
        verifyNoInteractions(transfers);
        verify(refunds).processed(eq(5L), isNull(), eq("MOCK:5"), any());
        verify(ledger).post(12L, WalletLedgerService.Bucket.PENDING, "REFUND_OUT",
                WalletLedgerService.Direction.DEBIT, new BigDecimal("40.00"), "REFUND", 5L);
        verify(escrow).refund(11L, new BigDecimal("40.00"), "CANCELLED");
    }

    @Test void adminSettledMockGatewaySucceedsWithAdmin() {
        RefundRecord refund = refund("APPROVED");
        refund.setMethod("MOCK_GATEWAY");
        refund.setAmount(new BigDecimal("40.00"));
        EscrowHoldRecord hold = new EscrowHoldRecord();
        hold.setId(11L);
        hold.setWalletId(12L);
        hold.setStatus("HELD");
        hold.setAmount(new BigDecimal("100.00"));
        hold.setRefundedAmount(BigDecimal.ZERO);

        when(refunds.find(5L)).thenReturn(Optional.of(refund));
        when(refunds.lock(5L)).thenReturn(Optional.of(refund));
        when(escrow.lock(3L)).thenReturn(Optional.of(hold));
        when(refunds.processed(eq(5L), eq(9L), eq("MOCK:5"), any())).thenReturn(1);

        service.processed(5L, 9L, "MOCK:5");

        verify(payments).lockOrder(2L);
        verifyNoInteractions(transfers);
        verify(refunds).processed(eq(5L), eq(9L), eq("MOCK:5"), any());
        verify(ledger).post(12L, WalletLedgerService.Bucket.PENDING, "REFUND_OUT",
                WalletLedgerService.Direction.DEBIT, new BigDecimal("40.00"), "REFUND", 5L);
        verify(escrow).refund(11L, new BigDecimal("40.00"), "CANCELLED");
    }
}
