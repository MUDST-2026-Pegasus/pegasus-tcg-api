package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.PromotionClaimSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bodies for collecting a code into "My coupons" [TCG-576]. */
public final class ClaimPromotionRequests {

    private ClaimPromotionRequests() {
    }

    /** @param source where the button was; null means the code's own strip (HOME or STORE) */
    public record ById(PromotionClaimSource source) {
    }

    public record ByCode(@NotBlank @Size(max = 20) String code) {
    }
}
