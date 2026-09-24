package com.pegasus.pegasustcgapi.exception;

import com.pegasus.pegasustcgapi.common.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns exceptions into {@link ApiError} bodies so controllers never format errors themselves. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Checkout refused because prices moved. Handled ahead of {@link ApiException}
     * so the response names every line that changed and what it changed to, instead
     * of sending the buyer back to the basket to work it out.
     */
    @ExceptionHandler(CartPriceChangedException.class)
    public ResponseEntity<ApiResult<ApiError>> handleCartPriceChanged(
            CartPriceChangedException ex, HttpServletRequest request) {

        List<ApiError.FieldViolation> violations = ex.changes().stream()
                .map(change -> new ApiError.FieldViolation(
                        "listing:" + change.listingId(),
                        "price changed from " + change.oldPrice() + " to " + change.newPrice()))
                .toList();

        return build(ex.errorCode(), ex.getMessage(), request, violations);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResult<ApiError>> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.errorCode(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<ApiError>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();

        return build(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request,
                violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<ApiError>> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(),
                request, List.of());
    }

    /**
     * Spring Security normally handles these in the filter chain, before the
     * dispatcher. They only reach here when thrown from inside a controller or service.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResult<ApiError>> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        return build(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage(),
                request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<ApiError>> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage(),
                request, List.of());
    }

    /** A path variable or query parameter that could not be converted, e.g. an unknown role name. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<ApiError>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        List<ApiError.FieldViolation> violations =
                List.of(new ApiError.FieldViolation(ex.getName(), "is not a valid value"));

        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request, violations);
    }

    /**
     * A required query parameter that was left out, e.g. {@code GET /card-sets}
     * without {@code gameId}. That is the caller's mistake, so it is a 400 naming
     * the parameter — without this it fell through to the catch-all as a 500.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<ApiError>> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        List<ApiError.FieldViolation> violations =
                List.of(new ApiError.FieldViolation(ex.getParameterName(), "is required"));

        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request, violations);
    }

    /**
     * A body sent as something other than JSON — Postman's "Text" body type is the
     * usual way to get here. Without this it was a 500.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<ApiError>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(ErrorCode.UNSUPPORTED_CONTENT_TYPE, ErrorCode.UNSUPPORTED_CONTENT_TYPE.defaultMessage(),
                request, List.of());
    }

    /**
     * The client accepts only a format this API does not produce, e.g. XML.
     *
     * <p>Answered with no body on purpose: the envelope is JSON, which is exactly
     * what the client said it cannot read. Trying to send one anyway failed a
     * second time and ended up as a 401 from the error page.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResult<ApiError>> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /** Without this an unmapped path would fall through to the catch-all below as a 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<ApiError>> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(),
                request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<ApiError>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(),
                request, List.of());
    }

    /**
     * A unique index or foreign key the caller walked into — a duplicate
     * {@code Idempotency-Key} that no retry path caught, most often. It is the
     * caller's request that conflicts with what is already stored, so it is a 409;
     * without this it fell through to the catch-all as a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResult<ApiError>> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        // The constraint name can name internals, so it is logged rather than returned.
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.DATA_CONFLICT, ErrorCode.DATA_CONFLICT.defaultMessage(),
                request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<ApiError>> handleUnexpected(Exception ex, HttpServletRequest request) {
        // The cause is logged but never returned: it can carry internals.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(),
                request, List.of());
    }

    private static ApiError.FieldViolation toViolation(FieldError error) {
        String message = error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
        return new ApiError.FieldViolation(error.getField(), message);
    }

    private static ResponseEntity<ApiResult<ApiError>> build(
            ErrorCode code,
            String message,
            HttpServletRequest request,
            List<ApiError.FieldViolation> violations) {

        ApiError detail = new ApiError(
                code.name(),
                request.getRequestURI(),
                OffsetDateTime.now(),
                violations);

        return ResponseEntity.status(code.status()).body(ApiResult.error(message, detail));
    }
}
