package com.pegasus.pegasustcgapi.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Machine-readable detail of a failure, carried as the {@code data} of an
 * {@link com.pegasus.pegasustcgapi.common.ApiResponse}. The human-readable text
 * lives in the envelope's {@code message}, and the status on the response itself.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String path,
        OffsetDateTime timestamp,
        List<FieldViolation> violations) {

    /** One rejected field, reported alongside {@link ErrorCode#VALIDATION_FAILED}. */
    public record FieldViolation(String field, String message) {
    }
}
