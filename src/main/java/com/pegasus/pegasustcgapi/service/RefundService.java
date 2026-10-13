package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundTransferAttemptRecord;
import com.pegasus.pegasustcgapi.repository.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.RefundRepository;
import com.pegasus.pegasustcgapi.repository.RefundTransferRepository;
import com.pegasus.pegasustcgapi.repository.ReturnRepository;
import com.pegasus.pegasustcgapi.model.SellerOrderStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A manual bank refund stays owed until an admin confirms the external transfer. */
@Service
public class RefundService {

    private final RefundRepository refunds;
    private final RefundTransferRepository transfers;
    private final PaymentRepository payments;
    private final EscrowRepository escrow;
    private final OrderRepository orders;
    private final WalletLedgerService ledger;
    private final Clock clock;
    private final ReturnRepository returns;
    private final ObjectProvider<OrderLifecycleService> lifecycle;
    private final EscrowReleaseService escrowRelease;
    private final com.pegasus.pegasustcgapi.port.CollectionPort collection;

    public RefundService(RefundRepository refunds, RefundTransferRepository transfers, PaymentRepository payments,
            EscrowRepository escrow, OrderRepository orders, WalletLedgerService ledger, Clock clock,
            ReturnRepository returns, ObjectProvider<OrderLifecycleService> lifecycle,
            EscrowReleaseService escrowRelease,
            com.pegasus.pegasustcgapi.port.CollectionPort collection) {
        this.refunds = refunds;
        this.transfers = transfers;
        this.payments = payments;
        this.escrow = escrow;
        this.orders = orders;
        this.ledger = ledger;
        this.clock = clock;
        this.returns = returns;
        this.lifecycle = lifecycle;
        this.escrowRelease = escrowRelease;
        this.collection = collection;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onCancel(long sellerOrderId, String reason, Long actorUserId) {
        var sellerOrder = orders.findSellerOrderById(sellerOrderId).orElseThrow();
        var hold = escrow.lock(sellerOrderId).orElse(null);
        if (hold == null) {
            payments.cancelPendingWhenNoUnpaidSuborders(sellerOrder.getSalesOrderId(), actorUserId == null);
            return;
        }
        if (!"HELD".equals(hold.getStatus())) throw new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        String key = "cancel:" + sellerOrderId;
        if (refunds.byKey(key).isPresent()) return;
        var payment = payments.succeededForOrder(sellerOrder.getSalesOrderId())
                .orElseThrow(() -> new ConflictException(ErrorCode.PAYMENT_STATE));
        var refund = refunds.create(sellerOrder.getSalesOrderId(), sellerOrderId,
                hold.getAmount().subtract(hold.getRefundedAmount()), reason, payment.getMethod(), key);
        if ("MOCK_GATEWAY".equals(payment.getMethod())) {
            processed(refund.getId(), null, "MOCK:" + refund.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<RefundTransferAttemptRecord> transferHistory(long refundId) {
        if (refunds.find(refundId).isEmpty()) throw new NotFoundException(ErrorCode.REFUND_NOT_FOUND);
        return transfers.history(refundId);
    }

    @Transactional(readOnly = true)
    public List<RefundRecord> mine(long buyerId) {
        return refunds.forBuyer(buyerId);
    }

    @Transactional(readOnly = true)
    public RefundRecord mine(long refundId, long buyerId) {
        var refund = refunds.find(refundId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFUND_NOT_FOUND));
        var order = payments.findOrder(refund.getSalesOrderId()).orElseThrow();
        if (order.getBuyerId() != buyerId) throw new NotFoundException(ErrorCode.REFUND_NOT_FOUND);
        return refund;
    }

    @Transactional
    public RefundRecord setDestination(long refundId, long buyerId,
            String bankName, String accountName, String accountNumber) {
        var initial = mine(refundId, buyerId);
        payments.lockOrder(initial.getSalesOrderId());
        var refund = refunds.lock(refundId).orElseThrow();
        if (!"APPROVED".equals(refund.getStatus()) || !"BANK_TRANSFER".equals(refund.getMethod())) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (bankName == null || bankName.isBlank() || bankName.length() > 100
                || accountName == null || accountName.isBlank() || accountName.length() > 100
                || accountNumber == null || !accountNumber.matches("[0-9 -]{6,40}")) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = accountNumber.replaceAll("[ -]", "");
        if (normalized.length() < 6 || normalized.length() > 34) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        if (refunds.setDestination(refundId, bankName.trim(), accountName.trim(), normalized) != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        return refunds.find(refundId).orElseThrow();
    }

    @Transactional
    public RefundRecord startTransfer(long refundId, long adminId, String expectedAccountNumber) {
        var initial = refunds.find(refundId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFUND_NOT_FOUND));
        payments.lockOrder(initial.getSalesOrderId());
        var refund = refunds.lock(refundId).orElseThrow();
        if (!"APPROVED".equals(refund.getStatus()) || !"BANK_TRANSFER".equals(refund.getMethod())) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (refund.getBankName() == null || refund.getAccountName() == null
                || refund.getAccountNumber() == null) {
            throw new ConflictException(ErrorCode.REFUND_DESTINATION_REQUIRED);
        }
        if (expectedAccountNumber == null || !refund.getAccountNumber().equals(expectedAccountNumber)) {
            throw new ConflictException(ErrorCode.REFUND_DESTINATION_CHANGED);
        }
        requireRefundableHold(refund);
        transfers.start(refund, adminId);
        if (refunds.transition(refundId, "APPROVED", "PROCESSING") != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        return refunds.find(refundId).orElseThrow();
    }

    @Transactional
    public RefundRecord transferFailed(long refundId, long adminId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        var initial = refunds.find(refundId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFUND_NOT_FOUND));
        payments.lockOrder(initial.getSalesOrderId());
        var refund = refunds.lock(refundId).orElseThrow();
        if (!"PROCESSING".equals(refund.getStatus())) throw new ConflictException(ErrorCode.PAYMENT_STATE);
        var attempt = transfers.active(refundId).orElseThrow();
        transfers.failed(attempt.getId(), adminId, reason.trim(), OffsetDateTime.now(clock));
        if (refunds.transition(refundId, "PROCESSING", "APPROVED") != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        return refunds.find(refundId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onReturn(long sellerOrderId, long returnId, String reason, java.math.BigDecimal amount) {
        if (refunds.byKey("return:" + returnId).isPresent()) return;
        var sellerOrder = orders.findSellerOrderById(sellerOrderId).orElseThrow();
        var payment = payments.succeededForOrder(sellerOrder.getSalesOrderId())
                .orElseThrow(() -> new ConflictException(ErrorCode.PAYMENT_STATE));
        var hold = escrow.lock(sellerOrderId)
                .orElseThrow(() -> new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE));
        if (hold.getAmount().subtract(hold.getRefundedAmount()).compareTo(amount) < 0) {
            throw new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        }
        var refund = refunds.createForReturn(sellerOrder.getSalesOrderId(), sellerOrderId,
                returnId, amount, reason, payment.getMethod());
        if ("MOCK_GATEWAY".equals(payment.getMethod())) {
            processed(refund.getId(), null, "MOCK:" + refund.getId());
        }
    }

    /**
     * @param adminId who confirmed the transfer; null when the system settles it on its
     *                own, as it does for the mock gateway. {@code refund.processed_by}
     *                references {@code user_account}, so a placeholder id such as 0 would
     *                fail the foreign key and roll the whole cancel back.
     */
    @Transactional
    public RefundRecord processed(long refundId, Long adminId, String transferReference) {
        var initial = refunds.find(refundId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFUND_NOT_FOUND));
        payments.lockOrder(initial.getSalesOrderId());
        var refund = refunds.lock(refundId).orElseThrow();
        if (transferReference == null || transferReference.isBlank() || transferReference.length() > 64) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        }
        String reference = transferReference.trim();
        if ("PROCESSED".equals(refund.getStatus()) && reference.equals(refund.getTransferReference())) {
            return refund;
        }
        boolean bank = "BANK_TRANSFER".equals(refund.getMethod());
        if (!(bank ? "PROCESSING" : "APPROVED").equals(refund.getStatus())) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        var attempt = bank ? transfers.active(refundId).orElseThrow() : null;
        var hold = requireRefundableHold(refund);
        ledger.post(hold.getWalletId(), WalletLedgerService.Bucket.PENDING,
                "REFUND_OUT", WalletLedgerService.Direction.DEBIT,
                refund.getAmount(), "REFUND", refundId);
        var totalRefunded = hold.getRefundedAmount().add(refund.getAmount());
        boolean full = totalRefunded.compareTo(hold.getAmount()) == 0;
        escrow.refund(hold.getId(), totalRefunded,
                refund.getReturnRequestId() == null ? "CANCELLED" : full ? "REFUNDED" : "HELD");
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (attempt != null) transfers.processed(attempt.getId(), adminId, reference, now);
        if (refunds.processed(refundId, adminId, reference, now) != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (refund.getReturnRequestId() != null) {
            returns.markRefunded(refund.getReturnRequestId());
            lifecycle.getObject().transitionAfterSales(refund.getSellerOrderId(),
                    full ? SellerOrderStatus.REFUNDED : SellerOrderStatus.COMPLETED,
                    adminId, "Return refund transferred");
            if (!full) escrowRelease.release(refund.getSellerOrderId());
            returns.close(refund.getReturnRequestId(), now);
            if (!full) {
                var order = payments.findOrder(refund.getSalesOrderId()).orElseThrow();
                collection.grant(order.getBuyerId(), refund.getSellerOrderId());
            }
        }
        return refunds.lock(refundId).orElseThrow();
    }

    private com.pegasus.pegasustcgapi.jooq.tables.records.EscrowHoldRecord requireRefundableHold(RefundRecord refund) {
        var hold = escrow.lock(refund.getSellerOrderId())
                .orElseThrow(() -> new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE));
        if (!"HELD".equals(hold.getStatus()) ||
                hold.getAmount().subtract(hold.getRefundedAmount()).compareTo(refund.getAmount()) < 0) {
            throw new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        }
        return hold;
    }
}
