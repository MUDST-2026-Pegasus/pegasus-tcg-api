package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookie {
    private final String name;
    private final String domain;
    private final boolean secure;
    private final String sameSite;
    private final long maxAge;

    public RefreshCookie(AuthProperties auth,
            @Value("${pegasus.auth.refresh-cookie.name:pegasus_rt}") String name,
            @Value("${pegasus.auth.refresh-cookie.domain:}") String domain,
            @Value("${pegasus.auth.refresh-cookie.secure:true}") boolean secure,
            @Value("${pegasus.auth.refresh-cookie.same-site:Lax}") String sameSite) {
        this.name = name;
        this.domain = domain;
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAge = auth.refreshTokenTtl().toSeconds();
    }

    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    public void set(HttpServletResponse response, String value) {
        write(response, value, maxAge);
    }

    public void clear(HttpServletResponse response) {
        write(response, "", 0);
    }

    private void write(HttpServletResponse response, String value, long age) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(name, value)
                .httpOnly(true).secure(secure).sameSite(sameSite)
                .path(ApiPaths.AUTH).maxAge(age);
        if (!domain.isBlank()) cookie.domain(domain);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }
}
