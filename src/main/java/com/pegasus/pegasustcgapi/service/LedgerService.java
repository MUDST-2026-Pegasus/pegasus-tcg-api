package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.port.LedgerPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Platform ledger and commission calculation implementation.
 */
@Service
public class LedgerService implements LedgerPort {

    private static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.10");

    @Override
    public BigDecimal quoteCommission(BigDecimal itemsSubtotal) {
        if (itemsSubtotal == null || itemsSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return itemsSubtotal.multiply(DEFAULT_COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public void release(UUID sellerOrderId) {
        // Escrow release stub for future payout integration
    }

    @Override
    public void release(long sellerOrderId) {
        // Escrow release stub for future payout integration
    }
}
