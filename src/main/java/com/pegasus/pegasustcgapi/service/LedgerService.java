package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.port.LedgerPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Platform ledger and commission calculation implementation.
 *
 * <p>Until commission rules exist, every seller is quoted the platform default
 * from {@code platform_setting}. Reading it rather than hard-coding a rate is
 * what lets an admin change it without a deploy [RQ-14].
 */
@Service
public class LedgerService implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private static final BigDecimal PERCENT = new BigDecimal("100");

    private final PlatformSettingService settings;

    public LedgerService(PlatformSettingService settings) {
        this.settings = settings;
    }

    @Override
    public CommissionQuote quoteCommission(long sellerProfileId, BigDecimal itemsSubtotal) {
        if (itemsSubtotal == null || itemsSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return CommissionQuote.NONE;
        }
        BigDecimal ratePercent = settings.getDecimal(PlatformSettingService.COMMISSION_DEFAULT_RATE);
        BigDecimal amount = itemsSubtotal
                .multiply(ratePercent)
                .divide(PERCENT, 2, RoundingMode.HALF_UP);
        return new CommissionQuote(ratePercent, amount);
    }

    /**
     * Escrow release, until there is a payout module to release it into.
     *
     * <p>It logs rather than doing nothing quietly: a completed sub-order is money
     * the platform owes a seller, and the log is the only record of how much has
     * built up before real payouts arrive.
     */
    @Override
    public void release(long sellerOrderId) {
        log.warn("Escrow release for seller order {} is not implemented; payout is still owed", sellerOrderId);
    }
}
