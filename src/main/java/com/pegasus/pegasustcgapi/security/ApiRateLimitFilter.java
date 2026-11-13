package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared per-address budget across API routes, before authentication or controller work. */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter limiter;
    private final SecurityErrorHandler errorHandler;

    public ApiRateLimitFilter(int limitPerMinute, Clock clock, SecurityErrorHandler errorHandler) {
        this.limiter = new FixedWindowRateLimiter(limitPerMinute, clock);
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "OPTIONS".equals(request.getMethod())
                || !(path.equals(ApiPaths.API_V1) || path.startsWith(ApiPaths.API_V1 + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.allow(List.of("ip:" + ClientAddress.of(request)))) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            errorHandler.write(request, response, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }
}
