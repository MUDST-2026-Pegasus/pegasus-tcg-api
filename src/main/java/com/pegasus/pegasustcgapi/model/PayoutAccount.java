package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * Where a seller's money is sent.
 *
 * <p>The number is stored and returned as written. It is not public: only the
 * owning seller and an admin can reach an endpoint that reveals it.
 */
public record PayoutAccount(
        long id,
        long sellerProfileId,
        String bankCode,
        String bankName,
        String accountName,
        String accountNumber,
        boolean isDefault,
        OffsetDateTime verifiedAt,
        OffsetDateTime createdAt) {
}
