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
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the endpoints that check a password or create an account, per client address.
 *
 * <p>The per-account lockout stops guessing one account's password, but not trying
 * one common password against many accounts, nor signing up accounts by the
 * thousand. This counts every such request from one address together, whatever
 * account it names. Refresh is left out: its token is 256 random bits, so there is
 * nothing to guess, and a browser with several tabs open calls it often.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            ApiPaths.AUTH + "/login",
            ApiPaths.AUTH + "/register",
            ApiPaths.AUTH + "/password/reset",
            ApiPaths.AUTH + "/password/change");

    private final FixedWindowRateLimiter limiter;
    private final SecurityErrorHandler errorHandler;

    public AuthRateLimitFilter(int limitPerMinute, Clock clock, SecurityErrorHandler errorHandler) {
        this.limiter = new FixedWindowRateLimiter(limitPerMinute, clock);
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.allow(List.of("ip:" + ClientAddress.of(request)))) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(FixedWindowRateLimiter.WINDOW_MILLIS / 1000));
            errorHandler.write(request, response, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }
}
