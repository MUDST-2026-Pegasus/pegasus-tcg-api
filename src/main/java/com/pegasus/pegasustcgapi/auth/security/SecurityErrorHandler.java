package com.pegasus.pegasustcgapi.auth.security;

import tools.jackson.databind.ObjectMapper;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.exception.ApiError;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Rejections raised inside the filter chain never reach
 * {@link com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler}, so they are
 * rendered here into the same envelope. Without this a missing token would come
 * back as an empty 401 body while every other failure carries JSON.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No credentials, or credentials that did not verify. */
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(request, response, ErrorCode.UNAUTHENTICATED);
    }

    /** Verified caller, but the endpoint needs a role they do not hold. */
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(request, response, ErrorCode.ACCESS_DENIED);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {

        ApiError detail = new ApiError(
                code.name(), request.getRequestURI(), OffsetDateTime.now(), List.of());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(), ApiResponse.error(code.defaultMessage(), detail));
    }
}
