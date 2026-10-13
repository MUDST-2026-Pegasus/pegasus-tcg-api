package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.repository.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bank transfers are recorded only after an admin checks the real bank account. */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final EscrowRepository escrow;
    private final WalletLedgerService ledger;
    private final OrderLifecycleService orders;
    private final PlatformSettingService settings;
    private final StorageService storage;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, EscrowRepository escrow,
            WalletLedgerService ledger, OrderLifecycleService orders,
            PlatformSettingService settings, StorageService storage, Clock clock) {
        this.payments = payments;
        this.escrow = escrow;
        this.ledger = ledger;
        this.orders = orders;
        this.settings = settings;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public View create(AuthPrincipal buyer, long orderId, String method, String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new BadRequestException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!"BANK_TRANSFER".equals(method)) {
            throw new BadRequestException(ErrorCode.PAYMENT_METHOD_NOT_SUPPORTED);
        }
        SalesOrderRecord order = ownedOrder(buyer.userId(), orderId);
        PaymentRecord keyed = payments.byKey(key).orElse(null);
        if (keyed != null) {
            if (keyed.getSalesOrderId() != orderId) throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            return view(keyed, order);
        }
        if (!"PENDING_PAYMENT".equals(order.getStatus())) throw new ConflictException(ErrorCode.ORDER_NOT_PAYABLE);
        PaymentRecord active = payments.activeForOrder(orderId).orElse(null);
        if (active != null) {
            if (!method.equals(active.getMethod())) throw new ConflictException(ErrorCode.PAYMENT_STATE);
            return view(active, order);
        }
        BigDecimal amount = payments.unpaidTotal(orderId);
        if (amount.signum() <= 0) throw new ConflictException(ErrorCode.ORDER_NOT_PAYABLE);
        return view(payments.insert(orderId, method, amount, key), order);
    }

    @Transactional(readOnly = true)
    public View get(AuthPrincipal buyer, long paymentId) {
        PaymentRecord payment = payments.find(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND));
        SalesOrderRecord order = payments.findOrder(payment.getSalesOrderId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND));
        if (order.getBuyerId() != buyer.userId()) throw new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND);
        return view(payment, order);
    }

    @Transactional
    public View attachSlip(AuthPrincipal buyer, long paymentId, String slipKey, String referenceCode) {
        PaymentRecord initial = payments.find(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND));
        SalesOrderRecord order = ownedOrder(buyer.userId(), initial.getSalesOrderId());
        PaymentRecord payment = payments.find(paymentId).orElseThrow();
        if (!"BANK_TRANSFER".equals(payment.getMethod())
                || !List.of("PENDING", "AWAITING_VERIFICATION").contains(payment.getStatus())) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (!"PENDING_PAYMENT".equals(order.getStatus())) throw new ConflictException(ErrorCode.ORDER_NOT_PAYABLE);
        if (payments.unpaidTotal(order.getId()).compareTo(payment.getAmount()) != 0) {
            throw new ConflictException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (slipKey == null || slipKey.isBlank()) throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
        storage.requireUploadedFor(UploadPurpose.PAYMENT_SLIP, slipKey);
        if (payments.slipUsedElsewhere(slipKey, paymentId)) throw new ConflictException(ErrorCode.SLIP_ALREADY_USED);
        if (payments.attachSlip(paymentId, slipKey, referenceCode) != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (settings.getBoolean(PlatformSettingService.PAYMENT_SLIP_AUTO_APPROVE)) {
            succeed(payments.find(paymentId).orElseThrow(), null);
        }
        return view(payments.find(paymentId).orElseThrow(), order);
    }

    @Transactional
    public View verify(long adminId, long paymentId, boolean approved, String reason) {
        PaymentRecord initial = payments.find(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND));
        SalesOrderRecord order = payments.lockOrder(initial.getSalesOrderId()).orElseThrow();
        PaymentRecord payment = payments.find(paymentId).orElseThrow();
        if (!"AWAITING_VERIFICATION".equals(payment.getStatus())) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        if (!"PENDING_PAYMENT".equals(order.getStatus())) throw new ConflictException(ErrorCode.ORDER_NOT_PAYABLE);
        if (payments.unpaidTotal(order.getId()).compareTo(payment.getAmount()) != 0) {
            throw new ConflictException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (approved) {
            succeed(payment, adminId);
        } else {
            if (reason == null || reason.isBlank()) throw new BadRequestException(ErrorCode.VALIDATION_FAILED);
            if (payments.reject(paymentId, adminId, reason, now()) != 1) {
                throw new ConflictException(ErrorCode.PAYMENT_STATE);
            }
        }
        return view(payments.find(paymentId).orElseThrow(), order);
    }

    /** Legacy sandbox endpoint only; its controller is disabled unless mock-enabled. */
    @Transactional
    public void payMock(AuthPrincipal buyer, long orderId) {
        SalesOrderRecord order = ownedOrder(buyer.userId(), orderId);
        if (!"PENDING_PAYMENT".equals(order.getStatus()) || payments.activeForOrder(orderId).isPresent()) {
            throw new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION);
        }
        BigDecimal amount = payments.unpaidTotal(orderId);
        if (amount.signum() <= 0) throw new ConflictException(ErrorCode.ORDER_NOT_PAYABLE);
        PaymentRecord payment = payments.insert(orderId, "MOCK_GATEWAY", amount,
                "mock:" + UUID.randomUUID());
        succeed(payment, null);
    }

    private void succeed(PaymentRecord payment, Long actorUserId) {
        if (payments.succeed(payment.getId(), actorUserId, now()) != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE);
        }
        long platform = ledger.platformWalletId();
        ledger.post(platform, WalletLedgerService.Bucket.AVAILABLE, "PAYMENT_IN",
                WalletLedgerService.Direction.CREDIT, payment.getAmount(), "PAYMENT", payment.getId());
        for (var sellerOrder : payments.unpaidSuborders(payment.getSalesOrderId())) {
            BigDecimal amount = sellerOrder.getGrandTotal();
            escrow.hold(sellerOrder.getId(), platform, amount);
            ledger.post(platform, WalletLedgerService.Bucket.AVAILABLE, "ESCROW_HOLD",
                    WalletLedgerService.Direction.DEBIT, amount, "SELLER_ORDER", sellerOrder.getId());
            ledger.post(platform, WalletLedgerService.Bucket.PENDING, "ESCROW_HOLD",
                    WalletLedgerService.Direction.CREDIT, amount, "SELLER_ORDER", sellerOrder.getId());
        }
        orders.markOrderPaidBySystem(payment.getSalesOrderId(), actorUserId);
    }

    private SalesOrderRecord ownedOrder(long buyerId, long orderId) {
        SalesOrderRecord order = payments.lockOrder(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getBuyerId() != buyerId) throw new NotFoundException(ErrorCode.ORDER_NOT_FOUND);
        return order;
    }

    private View view(PaymentRecord payment, SalesOrderRecord order) {
        return new View(payment.getId(), order.getId(), payment.getMethod(), payment.getStatus(),
                payment.getAmount(), order.getPlacedAt().plus(settings.getMinutes(
                        PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES)),
                settings.get(PlatformSettingService.PAYMENT_TRANSFER_ACCOUNT).value(),
                payment.getReferenceCode(), payment.getFailureReason());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    public record View(long id, long salesOrderId, String method, String status, BigDecimal amount,
            OffsetDateTime expiresAt, String transferAccountJson, String referenceCode, String failureReason) {
    }
}
