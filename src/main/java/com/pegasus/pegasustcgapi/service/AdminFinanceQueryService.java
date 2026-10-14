package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminPayout;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminRefund;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PaymentReview;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PaymentSummary;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutOrder;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutSummary;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.RefundSummary;
import com.pegasus.pegasustcgapi.repository.AdminFinanceRepository;
import com.pegasus.pegasustcgapi.repository.PayoutRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues and stat strips for the admin money desks. Read-only: every state change
 * still goes through {@link PaymentService}, {@link RefundService} or {@link PayoutService}.
 *
 * <p>"Today" and "this month" are cut in Bangkok time, like the admin overview.
 */
@Service
public class AdminFinanceQueryService {

    /** Passed as {@code ?status=} to list refunds in every state. */
    public static final String ALL = "ALL";

    private final AdminFinanceRepository finance;
    private final PayoutRepository payouts;
    private final PlatformSettingService settings;
    private final StorageService storage;
    private final Clock clock;

    public AdminFinanceQueryService(AdminFinanceRepository finance, PayoutRepository payouts,
            PlatformSettingService settings, StorageService storage, Clock clock) {
        this.finance = finance;
        this.payouts = payouts;
        this.settings = settings;
        this.storage = storage;
        this.clock = clock;
    }

    /** @param status a payment status; null means the slips waiting for review */
    @Transactional(readOnly = true)
    public List<PaymentReview> payments(String status) {
        var rows = finance.payments(status == null ? "AWAITING_VERIFICATION" : status);
        var orderIds = rows.stream().map(AdminFinanceRepository.PaymentRow::salesOrderId).distinct().toList();
        var splits = finance.splits(orderIds);
        var unpaid = finance.unpaidTotals(orderIds);
        Duration timeout = settings.getMinutes(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES);
        return rows.stream().map(p -> new PaymentReview(p.id(), p.salesOrderId(), p.orderNumber(),
                        p.buyer(), p.amount(), unpaid.getOrDefault(p.salesOrderId(), BigDecimal.ZERO),
                        p.status(), p.method(), p.referenceCode(),
                        p.slipImageKey() == null ? null : storage.presignDownload(p.slipImageKey()),
                        p.failureReason(), p.createdAt(), p.placedAt().plus(timeout), p.verifiedAt(),
                        splits.getOrDefault(p.salesOrderId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentSummary paymentSummary() {
        return finance.paymentSummary(today().atStartOfDay(AdminMetricsService.ZONE).toOffsetDateTime());
    }

    /** @param status a refund status, {@link #ALL}, or null for the ones ready to transfer */
    @Transactional(readOnly = true)
    public List<AdminRefund> refunds(String status) {
        if (status == null) return finance.refunds("APPROVED");
        return finance.refunds(ALL.equals(status) ? null : status);
    }

    @Transactional(readOnly = true)
    public RefundSummary refundSummary() {
        return finance.refundSummary(monthStart());
    }

    /** @param status a payout status, or null for every payout */
    @Transactional(readOnly = true)
    public List<AdminPayout> payouts(String status) {
        var rows = finance.payouts(status);
        var paidIds = rows.stream().filter(p -> "PAID".equals(p.status()))
                .map(AdminFinanceRepository.PayoutRow::id).toList();
        var linked = finance.linkedOrders(paidIds);
        return rows.stream().map(p -> {
            boolean paid = "PAID".equals(p.status());
            boolean open = List.of("REQUESTED", "APPROVED", "PROCESSING").contains(p.status());
            List<PayoutOrder> orders = paid ? linked.getOrDefault(p.id(), List.of())
                    : open ? payouts.linkableOrders(p.sellerProfileId(), p.amount()) : List.of();
            return new AdminPayout(p.id(), p.sellerProfileId(), p.seller(), p.amount(), p.currency(), p.status(),
                    p.account(), p.wallet(), p.requestedAt(), p.approvedAt(), p.approvedBy(),
                    p.paidAt(), p.transferReference(), p.rejectionReason(), orders, paid);
        }).toList();
    }

    @Transactional(readOnly = true)
    public PayoutSummary payoutSummary() {
        return finance.payoutSummary(monthStart());
    }

    private LocalDate today() {
        return OffsetDateTime.now(clock).atZoneSameInstant(AdminMetricsService.ZONE).toLocalDate();
    }

    private OffsetDateTime monthStart() {
        return today().withDayOfMonth(1).atStartOfDay(AdminMetricsService.ZONE).toOffsetDateTime();
    }
}
