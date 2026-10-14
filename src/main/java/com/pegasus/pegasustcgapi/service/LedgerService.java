package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository.Rule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Core ledger and commission service implementation [RQ-11, RQ-14, RQ-15, CR-4].
 *
 * <p><h3>Financial & Accounting Invariants:</h3>
 * <ul>
 *   <li><b>Precision & Rounding:</b> All calculations strictly use {@link BigDecimal} with scale 2
 *       and {@link java.math.RoundingMode#HALF_UP}. Rounding occurs ONLY at commission calculation.
 *       Seller net earning is derived by subtraction ({@code seller_net = total - commission}),
 *       never by separate rounding, to prevent one-satang ledger drift.</li>
 *   <li><b>Single-Legged External Entries:</b> Movements entering or leaving the system
 *       ({@code PAYMENT_IN}, {@code REFUND_OUT}, {@code PAYOUT_OUT}) post a single ledger row
 *       because {@code ck_wallet_kind} does not define an internal clearing wallet; the opposite
 *       leg resides in the real external banking system.</li>
 *   <li><b>Zero Amount Rule:</b> Zero-amount rows are forbidden. If a commission fee resolves to 0,
 *       the {@code COMMISSION_FEE} ledger entry is skipped.</li>
 *   <li><b>SELLER Wallets:</b> Seller wallets maintain zero pending balance because escrow funds
 *       reside in the PLATFORM wallet pending bucket until sub-order completion.</li>
 *   <li><b>Corrections:</b> Mispostings may only be corrected via {@code ADJUSTMENT} entries
 *       pairing DEBIT and CREDIT across wallets.</li>
 *   <li><b>Concurrency & Locking:</b> Lock ordering strictly follows:
 *       {@code sales_order} &rarr; {@code payment/escrow_hold} &rarr; {@code PLATFORM wallet} &rarr; {@code SELLER wallet}.
 *       Ledger posting methods require active transaction ({@code MANDATORY}) and rely on
 *       {@code ux_ledger_entry_once} as a final database guard against duplicate postings.</li>
 * </ul>
 */
@Service
public class LedgerService implements LedgerPort {

    private final PlatformSettingService settings;
    private final CommissionRuleRepository rules;
    private final Clock clock;
    private final EscrowReleaseService escrowRelease;
    private final RefundService refunds;

    public LedgerService(PlatformSettingService settings, CommissionRuleRepository rules,
            Clock clock, EscrowReleaseService escrowRelease, RefundService refunds) {
        this.settings = settings;
        this.rules = rules;
        this.clock = clock;
        this.escrowRelease = escrowRelease;
        this.refunds = refunds;
    }

    @Override
    public CommissionQuote quoteCommission(long sellerProfileId, BigDecimal itemsSubtotal) {
        return quoteCommission(sellerProfileId, itemsSubtotal, List.of());
    }

    @Override
    public CommissionQuote quoteCommission(long sellerProfileId, BigDecimal itemsSubtotal,
            Collection<Long> catalogVariantIds) {
        if (itemsSubtotal == null || itemsSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return CommissionQuote.NONE;
        }
        Set<Long> ids = new HashSet<>(catalogVariantIds);
        var scopes = rules.productScopes(ids);
        if (scopes.size() != ids.size()) {
            throw new IllegalStateException("Commission quote references a missing catalogue variant");
        }
        Short gameId = scopes.isEmpty() || scopes.stream().map(s -> s.gameId()).distinct().count() != 1
                ? null : scopes.getFirst().gameId();
        Integer categoryId = scopes.isEmpty() || scopes.stream().map(s -> s.categoryId()).distinct().count() != 1
                ? null : scopes.getFirst().categoryId();
        Rule chosen = rules.activeAt(OffsetDateTime.now(clock)).stream()
                .filter(r -> switch (r.scope()) {
                    case "SELLER" -> r.sellerProfileId() != null && r.sellerProfileId() == sellerProfileId;
                    case "CATEGORY" -> categoryId != null && categoryId.equals(r.categoryId());
                    case "GAME" -> gameId != null && gameId.equals(r.gameId());
                    case "GLOBAL" -> true;
                    default -> false;
                })
                .max(Comparator.comparingInt((Rule r) -> switch (r.scope()) {
                    case "SELLER" -> 4;
                    case "CATEGORY" -> 3;
                    case "GAME" -> 2;
                    default -> 1;
                }).thenComparingInt(Rule::priority).thenComparingLong(Rule::id))
                .orElse(null);
        BigDecimal rate = chosen == null
                ? settings.getDecimal(PlatformSettingService.COMMISSION_DEFAULT_RATE) : chosen.rate();
        BigDecimal fee = CommissionCalculator.fee(itemsSubtotal, rate,
                chosen == null ? BigDecimal.ZERO : chosen.fixed(),
                chosen == null ? null : chosen.minimum(), chosen == null ? null : chosen.maximum());
        return new CommissionQuote(rate, fee);
    }

    @Override
    public void release(long sellerOrderId) {
        escrowRelease.release(sellerOrderId);
    }

    @Override
    public void refundOnCancel(long sellerOrderId, String reason, Long actorUserId) {
        refunds.onCancel(sellerOrderId, reason, actorUserId);
    }

    @Override
    public void scheduleRelease(long sellerOrderId, OffsetDateTime dueAt) {
        escrowRelease.schedule(sellerOrderId, dueAt);
    }
}
