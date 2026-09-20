package com.pegasus.pegasustcgapi.port;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Platform ledger and commission port stub for checkout and order completion.
 */
public interface LedgerPort {

    BigDecimal quoteCommission(BigDecimal itemsSubtotal);

    void release(UUID sellerOrderId);

    void release(long sellerOrderId);
}
