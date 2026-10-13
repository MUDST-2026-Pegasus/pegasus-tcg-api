package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Payout.PAYOUT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;

import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutOrder;
import com.pegasus.pegasustcgapi.jooq.tables.records.PayoutRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class PayoutRepository {

    private final DSLContext dsl;

    public PayoutRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public PayoutRecord create(long sellerProfileId, long walletId, long accountId, BigDecimal amount) {
        return dsl.insertInto(PAYOUT)
                .set(PAYOUT.SELLER_PROFILE_ID, sellerProfileId)
                .set(PAYOUT.WALLET_ID, walletId)
                .set(PAYOUT.PAYOUT_ACCOUNT_ID, accountId)
                .set(PAYOUT.AMOUNT, amount)
                .returning().fetchSingle();
    }

    public Optional<PayoutRecord> lock(long id) {
        return dsl.selectFrom(PAYOUT).where(PAYOUT.ID.eq(id)).forUpdate().fetchOptional();
    }

    public List<PayoutRecord> sellerHistory(long sellerProfileId) {
        return dsl.selectFrom(PAYOUT).where(PAYOUT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .orderBy(PAYOUT.REQUESTED_AT.desc(), PAYOUT.ID.desc()).fetch();
    }

    public int transition(long id, String expected, String next, Long adminId, String reason,
            String reference, String slipKey, OffsetDateTime now) {
        var update = dsl.update(PAYOUT).set(PAYOUT.STATUS, next);
        if ("APPROVED".equals(next)) {
            update.set(PAYOUT.APPROVED_BY, adminId).set(PAYOUT.APPROVED_AT, now);
        }
        if ("PAID".equals(next)) {
            update.set(PAYOUT.PAID_AT, now).set(PAYOUT.TRANSFER_REFERENCE, reference)
                    .set(PAYOUT.SLIP_IMAGE_KEY, slipKey);
        }
        if ("REJECTED".equals(next) || "FAILED".equals(next)) {
            update.set(PAYOUT.REJECTION_REASON, reason);
        }
        return update.where(PAYOUT.ID.eq(id)).and(PAYOUT.STATUS.eq(expected)).execute();
    }

    /**
     * Completed sub-orders not yet paid out, oldest first, taken while their net
     * still fits inside {@code amount}. Paying a payout links exactly these; the
     * admin desk shows the same list beforehand as a preview.
     */
    public List<PayoutOrder> linkableOrders(long sellerProfileId, BigDecimal amount) {
        var completedOrders = dsl.select(SELLER_ORDER.ID, SELLER_ORDER.SELLER_ORDER_NUMBER,
                        SELLER_ORDER.SELLER_NET_AMOUNT, SELLER_ORDER.COMPLETED_AT)
                .from(SELLER_ORDER)
                .where(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(SELLER_ORDER.STATUS.eq("COMPLETED"))
                .and(SELLER_ORDER.PAYOUT_ID.isNull())
                .orderBy(SELLER_ORDER.COMPLETED_AT.asc(), SELLER_ORDER.ID.asc())
                .fetch();
        BigDecimal accumulated = BigDecimal.ZERO;
        List<PayoutOrder> fitting = new ArrayList<>();
        for (var record : completedOrders) {
            BigDecimal net = record.value3();
            if (net == null) continue;
            if (accumulated.add(net).compareTo(amount) <= 0) {
                accumulated = accumulated.add(net);
                fitting.add(new PayoutOrder(record.value1(), record.value2(), net, record.value4()));
            } else {
                break;
            }
        }
        return fitting;
    }

    public void linkPaidOrders(long payoutId, long sellerProfileId, BigDecimal amount) {
        List<Long> toLink = linkableOrders(sellerProfileId, amount).stream()
                .map(PayoutOrder::sellerOrderId).toList();
        if (!toLink.isEmpty()) {
            dsl.update(SELLER_ORDER)
                    .set(SELLER_ORDER.PAYOUT_ID, payoutId)
                    .where(SELLER_ORDER.ID.in(toLink))
                    .execute();
        }
    }
}
