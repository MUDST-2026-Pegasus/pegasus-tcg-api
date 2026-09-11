package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param reason shown to the seller, so it has to say what to fix, not just "rejected". */
public record RejectVerificationRequest(

        @NotBlank @Size(max = 255)
        String reason) {
}
