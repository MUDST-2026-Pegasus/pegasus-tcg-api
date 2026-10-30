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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * client address and, once signed in, per account as well. The address is the
 * last {@code X-Forwarded-For} hop — the one our own proxy added — so a client
 * cannot pick a fresh one per request. Counts live in this instance's memory: with
 * several replicas the cap is per replica, which still bounds a sweep.
 */
public class PromoRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000;
    /** Old windows are swept once the map grows past this, so idle callers do not pile up. */
    private static final int SWEEP_AT = 10_000;

    private final int limitPerMinute;
    private final Clock clock;
    private final SecurityErrorHandler errorHandler;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PromoRateLimitFilter(int limitPerMinute, Clock clock, SecurityErrorHandler errorHandler) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
        this.errorHandler = errorHandler;
    }

    private record Window(long start, AtomicInteger count) {
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
        long now = clock.millis();
        if (windows.size() > SWEEP_AT) windows.values().removeIf(w -> now - w.start() >= WINDOW_MILLIS);
        for (String key : keys(request)) {
            if (!allow(key, now)) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW_MILLIS / 1000));
                errorHandler.write(request, response, ErrorCode.RATE_LIMITED);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allow(String key, long now) {
        Window w = windows.compute(key, (k, current) ->
                current == null || now - current.start() >= WINDOW_MILLIS
                        ? new Window(now, new AtomicInteger()) : current);
        return w.count().incrementAndGet() <= limitPerMinute;
    }

    private static List<String> keys(HttpServletRequest request) {
        List<String> keys = new ArrayList<>(2);
        keys.add("ip:" + clientAddress(request));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) keys.add("user:" + jwt.getName());
        return keys;
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return request.getRemoteAddr();
        String[] hops = forwarded.split(",");
        return hops[hops.length - 1].trim();
    }
}
