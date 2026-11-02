package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AuthRateLimitFilterTest {

    private final MutableClock clock = new MutableClock();
    private final AuthRateLimitFilter filter =
            new AuthRateLimitFilter(3, clock, new SecurityErrorHandler(JsonMapper.builder().build()));

    @Test
    void theFourthPasswordCheckInAMinuteFromOneAddressIs429WhateverTheAccount() throws Exception {
        assertThat(send(post("/api/v1/auth/login", "203.0.113.5"))).isEqualTo(200);
        assertThat(send(post("/api/v1/auth/register", "203.0.113.5"))).isEqualTo(200);
        assertThat(send(post("/api/v1/auth/password/reset", "203.0.113.5"))).isEqualTo(200);

        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(post("/api/v1/auth/password/change", "203.0.113.5"), refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
        assertThat(refused.getContentAsString()).contains("RATE_LIMITED");

        assertThat(send(post("/api/v1/auth/login", "203.0.113.6"))).isEqualTo(200);
        clock.advance(Duration.ofSeconds(61));
        assertThat(send(post("/api/v1/auth/login", "203.0.113.5"))).isEqualTo(200);
    }

    @Test
    void refreshLogoutAndReadsAreNotCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send(post("/api/v1/auth/refresh", "203.0.113.9"))).isEqualTo(200);
            assertThat(send(post("/api/v1/auth/logout", "203.0.113.9"))).isEqualTo(200);
            MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/v1/auth/me");
            me.setRemoteAddr("203.0.113.9");
            assertThat(send(me)).isEqualTo(200);
        }
    }

    @Test
    void aCallerReachingTheAppDirectlyCannotBuyFreshTriesWithAForwardedHeader() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = post("/api/v1/auth/login", "198.51.100.20");
            r.addHeader("X-Forwarded-For", "192.0.2." + i);
            assertThat(send(r)).isEqualTo(200);
        }
        MockHttpServletRequest r = post("/api/v1/auth/login", "198.51.100.20");
        r.addHeader("X-Forwarded-For", "192.0.2.99");
        assertThat(send(r)).isEqualTo(429);
    }

    private int send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest post(String path, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRemoteAddr(ip);
        return r;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
