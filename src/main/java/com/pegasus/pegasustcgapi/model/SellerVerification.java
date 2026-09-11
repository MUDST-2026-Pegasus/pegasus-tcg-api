package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * What a seller submits to be allowed to sell: the name on their bank account,
 * and the account itself.
 *
 * <p>No identity document. Checking one would mean holding data that has to be
 * encrypted, access-controlled and purged, and deciding whether someone may list
 * a card does not need it — a name to attach to a dispute and an account to send
 * money to does.
 */
public record SellerVerification(
        long id,
        long sellerProfileId,
        String legalFirstName,
        String legalLastName,
        String bankCode,
        String bankName,
        String bankAccountNumber,
        VerificationStatus status,
        OffsetDateTime submittedAt,
        Long reviewedBy,
        OffsetDateTime reviewedAt,
        String rejectionReason) {

    public String legalName() {
        return legalFirstName + " " + legalLastName;
    }
}
