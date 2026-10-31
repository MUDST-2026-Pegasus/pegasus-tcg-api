package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CommissionCharge.COMMISSION_CHARGE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.repository.EscrowRepository;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.ReturnRepository;
import com.pegasus.pegasustcgapi.repository.ReturnRepository.ReturnedValue;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Bucket;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EscrowReleaseService {

    private static final Logger log = LoggerFactory.getLogger(EscrowReleaseService.class);

    private final EscrowRepository escrow;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final WalletLedgerService ledger;
    private final DSLContext dsl;
    private final Clock clock;
    private final ReturnRepository returns;

    public EscrowReleaseService(EscrowRepository escrow, OrderRepository orders,
            PaymentRepository payments, WalletLedgerService ledger, DSLContext dsl, Clock clock,
            ReturnRepository returns) {
        this.escrow = escrow;
        this.orders = orders;
        this.payments = payments;
        this.ledger = ledger;
        this.dsl = dsl;
        this.clock = clock;
        this.returns = returns;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long sellerOrderId) {
        var initial = orders.findSellerOrderById(sellerOrderId).orElse(null);
        if (initial == null) return;
        payments.lockOrder(initial.getSalesOrderId());
        var hold = escrow.lock(sellerOrderId).orElse(null);
        if (hold == null) {
            log.warn("No escrow for completed seller order {}", sellerOrderId);
            return;
        }
        if (!"HELD".equals(hold.getStatus())) return;
        var order = orders.findSellerOrderById(sellerOrderId).orElseThrow();
        BigDecimal release = hold.getAmount().subtract(hold.getRefundedAmount());
        if (release.signum() <= 0) return;
        // The commission base is the items less the shop's own code, less what came
        // back; a Pegasus code never lowers it. Pegasus then pays the shop back its own
        // code, less the part that went with returned cards [TCG-570].
        ReturnedValue returned = returns.returnedValue(sellerOrderId);
        BigDecimal quotedBase = order.getItemsSubtotal().subtract(order.getSellerDiscountAmount());
        BigDecimal base = quotedBase.subtract(returned.gross().subtract(returned.shopShare()))
                .max(BigDecimal.ZERO);
        BigDecimal subsidy = order.getPlatformDiscountAmount().subtract(returned.pegasusShare())
                .max(BigDecimal.ZERO);
        BigDecimal commission = quotedBase.signum() == 0 ? BigDecimal.ZERO :
                order.getCommissionAmount().multiply(base)
                        .divide(quotedBase, 2, RoundingMode.HALF_UP);
        commission = commission.min(release.add(subsidy));
        BigDecimal sellerNet = release.add(subsidy).subtract(commission);
        if (sellerNet.signum() < 0) throw new ConflictException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        long sellerUserId = dsl.select(SELLER_PROFILE.USER_ID).from(SELLER_PROFILE)
                .where(SELLER_PROFILE.ID.eq(order.getSellerProfileId()))
                .fetchSingle(SELLER_PROFILE.USER_ID);
        long sellerWallet = ledger.ensureSellerWallet(sellerUserId);
        long platformWallet = hold.getWalletId();
        ledger.post(platformWallet, Bucket.PENDING, "ESCROW_RELEASE", Direction.DEBIT,
                release, "SELLER_ORDER", sellerOrderId);
        if (commission.signum() > 0) {
            ledger.post(platformWallet, Bucket.AVAILABLE, "COMMISSION_FEE", Direction.CREDIT,
                    commission, "SELLER_ORDER", sellerOrderId);
        }
        if (subsidy.signum() > 0) {
            ledger.post(platformWallet, Bucket.AVAILABLE, "PROMO_SUBSIDY", Direction.DEBIT,
                    subsidy, "SELLER_ORDER", sellerOrderId);
        }
        if (sellerNet.signum() > 0) {
            ledger.post(sellerWallet, Bucket.AVAILABLE, "SELLER_EARNING", Direction.CREDIT,
                    sellerNet, "SELLER_ORDER", sellerOrderId);
        }
        escrow.release(hold.getId(), release,
                hold.getRefundedAmount().signum() > 0 ? "PARTIALLY_RELEASED" : "RELEASED",
                OffsetDateTime.now(clock));
        dsl.insertInto(COMMISSION_CHARGE)
                .set(COMMISSION_CHARGE.SELLER_ORDER_ID, sellerOrderId)
                .set(COMMISSION_CHARGE.BASE_AMOUNT, base)
                .set(COMMISSION_CHARGE.RATE_PERCENT, order.getCommissionRatePercent())
                .set(COMMISSION_CHARGE.FIXED_FEE, BigDecimal.ZERO)
                .set(COMMISSION_CHARGE.CHARGED_AMOUNT, commission)
                .execute();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(long sellerOrderId, OffsetDateTime dueAt) {
        escrow.schedule(sellerOrderId, dueAt);
    }
}
