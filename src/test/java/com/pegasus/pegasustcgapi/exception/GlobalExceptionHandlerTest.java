package com.pegasus.pegasustcgapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a required query parameter left out is the caller's 400, not the server's 500")
    void missingParameterIsABadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/card-sets");

        ResponseEntity<ApiResult<ApiError>> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("gameId", "short"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();

        ApiError detail = response.getBody().getData();
        assertThat(detail.code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(detail.path()).isEqualTo("/api/v1/card-sets");
        assertThat(detail.violations())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.field()).isEqualTo("gameId");
                    assertThat(violation.message()).isEqualTo("is required");
                });
    }

    @Test
    @DisplayName("a body sent as text instead of JSON is a 415, not a 500")
    void unsupportedContentTypeIs415() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");

        ResponseEntity<ApiResult<ApiError>> response = handler.handleUnsupportedMediaType(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.UNSUPPORTED_CONTENT_TYPE.name());
    }

    @Test
    @DisplayName("a client that only accepts XML gets a bodiless 406")
    void notAcceptableHasNoBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/games");

        ResponseEntity<ApiResult<ApiError>> response = handler.handleNotAcceptable();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("constraint violation logs safely when request URI contains CR, LF, and Unicode separators")
    void dataIntegrityViolationWithCrlfInUri() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/products\r\n2026-11-02 [WARN] forged\u2028separator");

        ResponseEntity<ApiResult<ApiError>> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.DATA_CONFLICT.name());
    }

    @Test
    @DisplayName("unexpected exception logs safely when request URI and method contain CR, LF, and Unicode separators")
    void unexpectedExceptionWithCrlfInUriAndMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET\r\nINJECT",
                "/api/v1/unknown\r\n[ERROR] forged\u2029separator");

        ResponseEntity<ApiResult<ApiError>> response = handler.handleUnexpected(
                new RuntimeException("Simulated unexpected failure"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    @Test
    @DisplayName("unreadable message is a 400")
    void unreadableMessageIs400() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        ResponseEntity<ApiResult<ApiError>> response = handler.handleUnreadable(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
    }

    @Test
    @DisplayName("authentication failure is a 401")
    void authenticationFailureIs401() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        ResponseEntity<ApiResult<ApiError>> response = handler.handleAuthentication(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());
    }

    @Test
    @DisplayName("access denied is a 403")
    void accessDeniedIs403() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        ResponseEntity<ApiResult<ApiError>> response = handler.handleAccessDenied(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
    }

    @Test
    @DisplayName("no resource found is a 404")
    void noResourceFoundIs404() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/non-existent");
        ResponseEntity<ApiResult<ApiError>> response = handler.handleNoResource(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name());
    }

    @Test
    @DisplayName("method not supported is a 405")
    void methodNotSupportedIs405() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/auth/login");
        ResponseEntity<ApiResult<ApiError>> response = handler.handleMethodNotSupported(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
    }
}
