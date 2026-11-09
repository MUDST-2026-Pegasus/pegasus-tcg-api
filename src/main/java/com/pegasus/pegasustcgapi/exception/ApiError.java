package com.pegasus.pegasustcgapi.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Machine-readable detail of a failure, carried as the {@code data} of an
 * {@link com.pegasus.pegasustcgapi.common.ApiResult}. The human-readable text
 * lives in the envelope's {@code message}, and the status on the response itself.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String path,
        OffsetDateTime timestamp,
        List<FieldViolation> violations,
        java.util.Map<String, Object> details) {

    public ApiError(String code, String path, OffsetDateTime timestamp, List<FieldViolation> violations) {
        this(code, path, timestamp, violations, java.util.Map.of());
    }

    /**
     * One rejected field, reported alongside {@link ErrorCode#VALIDATION_FAILED}.
     * {@code code} is the constraint that failed (e.g. {@code NotBlank}, {@code Size}, {@code typeMismatch})
     * and {@code params} its attributes (e.g. {@code max}); clients translate those and keep
     * {@code message} as the fallback.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record FieldViolation(
            @Schema(description = "Rejected field or parameter", example = "accountNumber") String field,
            @Schema(description = "English fallback text", example = "size must be between 0 and 34") String message,
            @Schema(description = "Failed constraint, e.g. NotBlank, Size, Pattern, typeMismatch, missingParameter",
                    example = "Size") String code,
            @Schema(description = "Constraint attributes for the client's message", example = "{\"min\":0,\"max\":34}")
            java.util.Map<String, Object> params) {

        public FieldViolation(String field, String message) {
            this(field, message, null, java.util.Map.of());
        }
    }
}
