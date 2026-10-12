package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CommissionRuleRequest(
        @NotBlank String scope,
        Short gameId,
        Integer categoryId,
        Long sellerProfileId,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 3) BigDecimal ratePercent,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal fixedFee,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal minFee,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal maxFee,
        short priority,
        @NotNull OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        Boolean active) {
}
