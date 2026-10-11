package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.RefundRecord;
import com.pegasus.pegasustcgapi.repository.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.RefundRepository;
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
    private final PaymentRepository payments;
    private final EscrowRepository escrow;
    private final OrderRepository orders;
    private final WalletLedgerService ledger;
    private final Clock clock;
    private final ReturnRepository returns;
    private final ObjectProvider<OrderLifecycleService> lifecycle;
    private final EscrowReleaseService escrowRelease;
    private final com.pegasus.pegasustcgapi.port.CollectionPort collection;

    public RefundService(RefundRepository refunds, PaymentRepository payments,
            EscrowRepository escrow, OrderRepository orders, WalletLedgerService ledger, Clock clock,
            ReturnRepository returns, ObjectProvider<OrderLifecycleService> lifecycle,
            EscrowReleaseService escrowRelease,
            com.pegasus.pegasustcgapi.port.CollectionPort collection) {
        this.refunds = refunds;
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
            processed(refund.getId(), 0L);
        }
    }

    @Transactional(readOnly = true)
    public List<RefundRecord> queue(String status) {
        return refunds.queue(status == null ? "APPROVED" : status);
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
            processed(refund.getId(), 0L);
        }
    }

    @Transactional
    public RefundRecord processed(long refundId, long adminId) {
        var initial = refunds.find(refundId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFUND_NOT_FOUND));
        payments.lockOrder(initial.getSalesOrderId());
        var refund = refunds.lock(refundId).orElseThrow();
        if (!"APPROVED".equals(refund.getStatus())) throw new ConflictException(ErrorCode.PAYMENT_STATE);
        var hold = escrow.lock(refund.getSellerOrderId())
                .orElseThrow(() -> new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE));
        if (!"HELD".equals(hold.getStatus()) ||
                hold.getAmount().subtract(hold.getRefundedAmount()).compareTo(refund.getAmount()) < 0) {
            throw new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        }
        ledger.post(hold.getWalletId(), WalletLedgerService.Bucket.PENDING,
                "REFUND_OUT", WalletLedgerService.Direction.DEBIT,
                refund.getAmount(), "REFUND", refundId);
        var totalRefunded = hold.getRefundedAmount().add(refund.getAmount());
        boolean full = totalRefunded.compareTo(hold.getAmount()) == 0;
        escrow.refund(hold.getId(), totalRefunded,
                refund.getReturnRequestId() == null ? "CANCELLED" : full ? "REFUNDED" : "HELD");
        if (refunds.processed(refundId, adminId, OffsetDateTime.now(clock)) != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (refund.getReturnRequestId() != null) {
            returns.markRefunded(refund.getReturnRequestId());
            lifecycle.getObject().transitionAfterSales(refund.getSellerOrderId(),
                    full ? SellerOrderStatus.REFUNDED : SellerOrderStatus.COMPLETED,
                    adminId, "Return refund transferred");
            if (!full) escrowRelease.release(refund.getSellerOrderId());
            returns.close(refund.getReturnRequestId(), OffsetDateTime.now(clock));
            if (!full) {
                var order = payments.findOrder(refund.getSalesOrderId()).orElseThrow();
                collection.grant(order.getBuyerId(), refund.getSellerOrderId());
            }
        }
        return refunds.lock(refundId).orElseThrow();
    }
}
