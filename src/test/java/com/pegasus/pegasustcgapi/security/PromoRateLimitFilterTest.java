package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class PromoRateLimitFilterTest {

    private final MutableClock clock = new MutableClock();
    private final PromoRateLimitFilter filter =
            new PromoRateLimitFilter(3, clock, new SecurityErrorHandler(JsonMapper.builder().build()));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theFourthTryInAMinuteIs429AndTheNextMinuteIsOpenAgain() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(send(preview("10.0.0.1"))).isEqualTo(200);

        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(preview("10.0.0.1"), refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
        assertThat(refused.getContentAsString()).contains("RATE_LIMITED");

        assertThat(send(preview("10.0.0.2"))).isEqualTo(200);
        clock.advance(Duration.ofSeconds(61));
        assertThat(send(preview("10.0.0.1"))).isEqualTo(200);
    }

    @Test
    void aSpoofedFirstForwardedHopDoesNotBuyFreshTries() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = preview("10.0.0.9");
            r.addHeader("X-Forwarded-For", "1.2.3." + i + ", 203.0.113.7");
            assertThat(send(r)).isEqualTo(200);
        }
        MockHttpServletRequest r = preview("10.0.0.9");
        r.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
        assertThat(send(r)).isEqualTo(429);
    }

    @Test
    void aForwardedHeaderFromOutsideTheClusterIsNotTrusted() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = preview("198.51.100.20");
            r.addHeader("X-Forwarded-For", "192.0.2." + i);
            assertThat(send(r)).isEqualTo(200);
        }
        MockHttpServletRequest r = preview("198.51.100.20");
        r.addHeader("X-Forwarded-For", "192.0.2.99");
        assertThat(send(r)).isEqualTo(429);
    }

    @Test
    void aSignedInCallerIsCountedPerAccountAcrossAddresses() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        for (int i = 0; i < 3; i++) assertThat(send(claimByCode("10.0.1." + i))).isEqualTo(200);
        assertThat(send(claimByCode("10.0.1.99"))).isEqualTo(429);
    }

    @Test
    void otherEndpointsAreNotCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/promotions/featured");
            r.setRemoteAddr("10.0.0.5");
            assertThat(send(r)).isEqualTo(200);
        }
        MockHttpServletRequest terms = new MockHttpServletRequest("GET", "/api/v1/promotions/ABCD/terms");
        terms.setRemoteAddr("10.0.0.5");
        assertThat(send(terms)).isEqualTo(200);
    }

    private int send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest preview(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/checkout/promo-preview");
        r.setRemoteAddr(ip);
        return r;
    }

    private static MockHttpServletRequest claimByCode(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/me/promotions/claim-by-code");
        r.setRemoteAddr(ip);
        return r;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-31T00:00:00Z");

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
