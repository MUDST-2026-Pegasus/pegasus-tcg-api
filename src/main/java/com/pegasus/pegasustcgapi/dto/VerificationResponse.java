package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.SellerVerification;
import com.pegasus.pegasustcgapi.model.VerificationStatus;
import java.time.OffsetDateTime;

/** A verification as the API reports it — what was submitted, and how it was judged. */
public record VerificationResponse(
        long id,
        long sellerProfileId,
        String legalFirstName,
        String legalLastName,
        String bankCode,
        String bankName,
        String bankAccountNumber,
        String bankBookImageKey,
        String bankBookImageUrl,
        VerificationStatus status,
        OffsetDateTime submittedAt,
        Long reviewedBy,
        OffsetDateTime reviewedAt,
        String rejectionReason) {

    public static VerificationResponse from(SellerVerification v, String bankBookImageUrl) {
        return new VerificationResponse(v.id(), v.sellerProfileId(), v.legalFirstName(),
                v.legalLastName(), v.bankCode(), v.bankName(), v.bankAccountNumber(),
                v.bankBookImageKey(), bankBookImageUrl, v.status(),
                v.submittedAt(), v.reviewedBy(), v.reviewedAt(), v.rejectionReason());
    }
}
