package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * Where a seller's money is sent — one per seller, and always the account from
 * their current approved verification.
 *
 * <p>There is no way to add, choose or remove one. Verifying an account and then
 * being paid into a different one is the hole that would make checking it at
 * signup meaningless, so the only thing that writes here is approval.
 */
public record PayoutAccount(
        long id,
        long sellerProfileId,
        String bankCode,
        String bankName,
        String accountName,
        String accountNumber,
        long verificationId,
        OffsetDateTime verifiedAt,
        OffsetDateTime createdAt) {
}
