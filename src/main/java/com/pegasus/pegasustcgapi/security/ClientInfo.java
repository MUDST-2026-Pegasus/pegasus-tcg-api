package com.pegasus.pegasustcgapi.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a token was handed out, recorded on the {@code auth_token} row so a user
 * can tell their own sessions apart.
 *
 * <p>Purely descriptive: {@code X-Forwarded-For} is client-controlled unless a
 * trusted proxy rewrites it, so nothing is ever authorised on these values.
 */
public record ClientInfo(String userAgent, String ip) {

    private static final int MAX_USER_AGENT = 255;
    private static final int MAX_IP = 45;

    public static ClientInfo from(HttpServletRequest request) {
        return new ClientInfo(
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT),
                truncate(clientIp(request), MAX_IP));
    }

    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        // The originating client is the first hop in the list.
        return forwarded.split(",")[0].trim();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
