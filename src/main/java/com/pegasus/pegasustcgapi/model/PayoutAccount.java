package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * Where a seller's money is sent.
 *
 * <p>Always the account from an approved verification — there is no other way to
 * create one. Verifying an account and then being paid into a different one is
 * the hole that would make the check at signup meaningless.
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
        long verificationId,
        boolean isDefault,
        OffsetDateTime verifiedAt,
        OffsetDateTime createdAt) {
}
