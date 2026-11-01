package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the endpoints that answer whether a typed discount code exists, so PRIVATE
 * codes cannot be found by trying them one after another [TCG-565].
 *
 * <p>Each caller gets {@code limitPerMinute} such requests a minute, counted per
 * client address ({@link ClientAddress}) and, once signed in, per account as well.
 * Counts live in this instance's memory: with several replicas the cap is per
 * replica, which still bounds a sweep.
 */
public class PromoRateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter limiter;
    private final SecurityErrorHandler errorHandler;

    public PromoRateLimitFilter(int limitPerMinute, Clock clock, SecurityErrorHandler errorHandler) {
        this.limiter = new FixedWindowRateLimiter(limitPerMinute, clock);
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return switch (request.getMethod()) {
            case "POST" -> !path.equals(ApiPaths.CHECKOUT + "/promo-preview")
                    && !path.equals(ApiPaths.ME_PROMOTIONS + "/claim-by-code");
            case "GET" -> !(path.startsWith(ApiPaths.PROMOTIONS + "/") && path.endsWith("/terms"));
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.allow(keys(request))) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(FixedWindowRateLimiter.WINDOW_MILLIS / 1000));
            errorHandler.write(request, response, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private static List<String> keys(HttpServletRequest request) {
        List<String> keys = new ArrayList<>(2);
        keys.add("ip:" + ClientAddress.of(request));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) keys.add("user:" + jwt.getName());
        return keys;
    }
}
