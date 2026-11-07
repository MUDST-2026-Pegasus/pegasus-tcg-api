package com.pegasus.pegasustcgapi.dto.listing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param reason why; for a block, the seller reads this */
public record ListingBlockRequest(
        @Schema(example = "สงสัยการ์ดปลอม รูปไม่ตรงกับการ์ดจริง")
        @NotBlank @Size(max = 500) String reason) {
}
