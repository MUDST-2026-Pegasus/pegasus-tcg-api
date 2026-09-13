package com.pegasus.pegasustcgapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a required query parameter left out is the caller's 400, not the server's 500")
    void missingParameterIsABadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/card-sets");

        ResponseEntity<ApiResponse<ApiError>> response = handler.handleMissingParameter(
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
}
