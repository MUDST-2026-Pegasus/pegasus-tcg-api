package com.pegasus.pegasustcgapi.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates the one rounded fee; the seller's net is the order total minus it. */
public final class CommissionCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CommissionCalculator() {
    }

    public static BigDecimal fee(BigDecimal base, BigDecimal rate, BigDecimal fixed,
            BigDecimal minimum, BigDecimal maximum) {
        if (base.signum() < 0 || rate.signum() < 0 || rate.compareTo(HUNDRED) > 0
                || fixed.signum() < 0 || (minimum != null && minimum.signum() < 0)
                || (maximum != null && maximum.signum() < 0)) {
            throw new IllegalArgumentException("Invalid commission amount or rate");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Minimum fee exceeds maximum fee");
        }
        BigDecimal fee = base.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP).add(fixed);
        if (minimum != null) fee = fee.max(minimum);
        if (maximum != null) fee = fee.min(maximum);
        return fee.min(base).max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);
    }
}
