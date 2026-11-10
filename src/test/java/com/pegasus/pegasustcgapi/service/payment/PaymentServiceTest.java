package com.pegasus.pegasustcgapi.service.payment;

import com.pegasus.pegasustcgapi.service.audit.AuditLogService;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.dto.payment.PaymentInfoResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.repository.finance.escrow.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.payment.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
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
    @Mock AuctionRepository auctions;
    private final com.pegasus.pegasustcgapi.config.PromptPayProperties promptPayProperties =
            new com.pegasus.pegasustcgapi.config.PromptPayProperties(null, null, null, null);
    PaymentService service;
    AuthPrincipal buyer = new AuthPrincipal(7, null, null, Set.of());

    @BeforeEach void setUp() {
        service = new PaymentService(payments, escrow, ledger, orders, settings, storage, Clock.systemUTC(),
                auctions, promptPayProperties,
                new com.pegasus.pegasustcgapi.service.payment.PromptPayQrService(promptPayProperties),
                mock(AuditLogService.class));
    }

    @Test void auctionPaymentShowsDeadlineFromAuctionClose() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setBuyerId(7L);
        order.setAuctionId(5L);
        PaymentRecord payment = new PaymentRecord();
        payment.setId(21L);
        payment.setSalesOrderId(11L);
        Auction auction = mock(Auction.class);
        OffsetDateTime due = OffsetDateTime.parse("2026-11-06T10:00:00Z");
        when(auction.paymentDueAt()).thenReturn(due);
        when(auctions.findById(5L)).thenReturn(Optional.of(auction));
        when(payments.find(21L)).thenReturn(Optional.of(payment));
        when(payments.findOrder(11L)).thenReturn(Optional.of(order));
        when(settings.get(PlatformSettingService.PAYMENT_TRANSFER_ACCOUNT))
                .thenReturn(new com.pegasus.pegasustcgapi.repository.setting.PlatformSettingRepository.Setting(
                        PlatformSettingService.PAYMENT_TRANSFER_ACCOUNT, "{}", null, null));

        assertThat(service.get(buyer, 21L).expiresAt()).isEqualTo(due);
    }

    @Test void paymentCreationRequiresKeyBeforeReadingAnOrder() {
        assertThatThrownBy(() -> service.create(buyer, 11L, "BANK_TRANSFER", " "))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        verifyNoInteractions(payments, escrow, ledger);
    }

    @Test void paymentCreationHidesAnotherBuyersOrder() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setBuyerId(8L);
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.create(buyer, 11L, "BANK_TRANSFER", "pay-key"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        verify(payments, never()).byKey(any());
        verifyNoInteractions(escrow, ledger);
    }

    @Test void paymentKeyCannotBeReusedForAnotherOrder() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setBuyerId(7L);
        order.setStatus("PENDING_PAYMENT");
        PaymentRecord keyed = new PaymentRecord();
        keyed.setSalesOrderId(99L);
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));
        when(payments.byKey("shared-key")).thenReturn(Optional.of(keyed));

        assertThatThrownBy(() -> service.create(buyer, 11L, "BANK_TRANSFER", "shared-key"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        verify(payments, never()).insert(eq(11L), any(), any(), any());
        verifyNoInteractions(escrow, ledger);
    }

    @Test void changedOrderTotalRejectsSlipBeforeStorageOrLedger() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setBuyerId(7L);
        order.setStatus("PENDING_PAYMENT");
        PaymentRecord payment = new PaymentRecord();
        payment.setId(21L);
        payment.setSalesOrderId(11L);
        payment.setMethod("BANK_TRANSFER");
        payment.setStatus("PENDING");
        payment.setAmount(new BigDecimal("150.00"));
        when(payments.find(21L)).thenReturn(Optional.of(payment));
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));
        when(payments.unpaidTotal(11L)).thenReturn(new BigDecimal("149.00"));

        assertThatThrownBy(() -> service.attachSlip(buyer, 21L, "payments/slip.png", "REF"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
        verifyNoInteractions(storage, escrow, ledger);
        verify(payments, never()).attachSlip(eq(21L), any(), any());
    }

    @Test void alreadyUsedSlipCannotBeAttachedToAnotherPayment() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setBuyerId(7L);
        order.setStatus("PENDING_PAYMENT");
        PaymentRecord payment = new PaymentRecord();
        payment.setId(21L);
        payment.setSalesOrderId(11L);
        payment.setMethod("BANK_TRANSFER");
        payment.setStatus("PENDING");
        payment.setAmount(new BigDecimal("150.00"));
        when(payments.find(21L)).thenReturn(Optional.of(payment));
        when(payments.lockOrder(11L)).thenReturn(Optional.of(order));
        when(payments.unpaidTotal(11L)).thenReturn(new BigDecimal("150.00"));
        when(payments.slipUsedElsewhere("payments/slip.png", 21L)).thenReturn(true);

        assertThatThrownBy(() -> service.attachSlip(buyer, 21L, "payments/slip.png", "REF"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SLIP_ALREADY_USED));
        verify(storage).requireUploadedFor(UploadPurpose.PAYMENT_SLIP, "payments/slip.png");
        verify(payments, never()).attachSlip(eq(21L), any(), any());
        verifyNoInteractions(escrow, ledger);
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

    @Test
    void getPaymentInfoReturnsPromptPayQrAndDetails() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setBuyerId(7L);
        OffsetDateTime placedAt = OffsetDateTime.parse("2026-11-06T10:00:00Z");
        order.setPlacedAt(placedAt);
        order.setGrandTotal(new BigDecimal("673.00"));

        when(payments.findOrder(11L)).thenReturn(Optional.of(order));
        when(payments.activeForOrder(11L)).thenReturn(Optional.empty());
        when(payments.unpaidTotal(11L)).thenReturn(new BigDecimal("673.00"));
        when(settings.getMinutes(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES))
                .thenReturn(java.time.Duration.ofMinutes(60));

        PaymentInfoResponse info = service.getPaymentInfo(buyer, 11L);
        assertThat(info.qrPayload()).isEqualTo("00020101021229370016A0000006770101110113006694776111953037645406673.005802TH5911PEGASUS TCG6007Bangkok6304C2AF");
        assertThat(info.promptpayId()).isEqualTo("094-xxx-1119");
        assertThat(info.bankName()).isEmpty();
        assertThat(info.accountName()).isEqualTo("PEGASUS TCG");
        assertThat(info.amount()).isEqualByComparingTo("673.00");
        assertThat(info.expiresAt()).isEqualTo(placedAt.plusMinutes(60));
    }

    @Test
    void getPaymentInfoThrowsNotFoundForNonOwner() {
        SalesOrderRecord order = new SalesOrderRecord();
        order.setId(11L);
        order.setBuyerId(99L);

        when(payments.findOrder(11L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getPaymentInfo(buyer, 11L))
                .isInstanceOf(NotFoundException.class);
    }
}
