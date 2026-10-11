package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CommissionRuleRequest(
        @NotBlank String scope,
        Short gameId,
        Integer categoryId,
        Long sellerProfileId,
        @NotNull BigDecimal ratePercent,
        @NotNull BigDecimal fixedFee,
        BigDecimal minFee,
        BigDecimal maxFee,
        short priority,
        @NotNull OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        Boolean active) {
}
