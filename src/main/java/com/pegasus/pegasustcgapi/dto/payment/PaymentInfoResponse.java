package com.pegasus.pegasustcgapi.dto.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentInfoResponse(
        String qrPayload,
        String promptpayId,
        String bankName,
        String accountName,
        BigDecimal amount,
        OffsetDateTime expiresAt
) {
}
