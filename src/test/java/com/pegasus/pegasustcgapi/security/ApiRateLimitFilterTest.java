package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ApiRateLimitFilterTest {
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(2,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new SecurityErrorHandler(JsonMapper.builder().build()));

    @Test
    void budgetIsSharedAcrossRoutesAndMethodsAndStopsTheChain() throws Exception {
        assertThat(send("GET", "/api/v1/games", "203.0.113.5").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/cart", "203.0.113.5").getStatus()).isEqualTo(200);
        var request = request("DELETE", "/api/v1/cart/items/1", "203.0.113.5");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        assertThat(send("GET", "/api/v1/games", "203.0.113.6").getStatus()).isEqualTo(200);
    }

    @Test
    void preflightMonitoringAndDocumentationDoNotConsumeBudget() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("OPTIONS", "/api/v1/games", "203.0.113.5").getStatus()).isEqualTo(200);
            assertThat(send("GET", "/actuator/health/liveness", "203.0.113.5").getStatus()).isEqualTo(200);
            assertThat(send("GET", "/actuator/prometheus", "203.0.113.5").getStatus()).isEqualTo(200);
            assertThat(send("GET", "/v1/api-docs", "203.0.113.5").getStatus()).isEqualTo(200);
            assertThat(send("GET", "/api/v10/games", "203.0.113.5").getStatus()).isEqualTo(200);
        }
        assertThat(send("GET", "/api/v1/games", "203.0.113.5").getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedHeaderCannotRotateTheBudgetForDirectPublicCallers() throws Exception {
        for (int i = 0; i < 3; i++) {
            var request = request("GET", "/api/v1/games", "203.0.113.5");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
        }
    }

    @Test
    void servletContextDoesNotBypassTheLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            var request = request("GET", "/shop/api/v1/games", "203.0.113.5");
            request.setContextPath("/shop");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
        }
    }

    private MockHttpServletResponse send(String method, String path, String ip) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(method, path, ip), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }
}
