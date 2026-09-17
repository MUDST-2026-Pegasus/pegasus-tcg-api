package com.pegasus.pegasustcgapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiResult;
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

        ResponseEntity<ApiResult<ApiError>> response = handler.handleUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().getData().code()).isEqualTo(ErrorCode.UNSUPPORTED_CONTENT_TYPE.name());
    }

    @Test
    @DisplayName("a client that only accepts XML gets a bodiless 406")
    void notAcceptableHasNoBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/games");

        ResponseEntity<ApiResult<ApiError>> response = handler.handleNotAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNull();
    }
}
