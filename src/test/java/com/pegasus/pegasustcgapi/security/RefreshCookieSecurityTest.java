package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.config.CorsProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class RefreshCookieSecurityTest {
    @Test
    void cookieIsHttpOnlyAndClearsWithSamePath() {
        AuthProperties auth = mock(AuthProperties.class);
        when(auth.refreshTokenTtl()).thenReturn(Duration.ofDays(30));
        RefreshCookie cookie = new RefreshCookie(auth, "pegasus_rt", "", true, "Lax");
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookie.set(response, "secret");
        cookie.clear(response);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        assertThat(response.getHeaders("Set-Cookie").getFirst())
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/auth", "Max-Age=2592000");
        assertThat(response.getHeaders("Set-Cookie").get(1))
                .contains("Path=/api/v1/auth", "Max-Age=0");
    }

    @Test
    void refreshRequiresAllowedOriginAndJson() throws Exception {
        RefreshOriginFilter filter = new RefreshOriginFilter(
                new CorsProperties(List.of("http://localhost:5173")), new SecurityErrorHandler(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);

        request.addHeader("Origin", "http://localhost:5173");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void invalidOriginAndContentTypeReturnApiEnvelopeThroughFilterChain() throws Exception {
        RefreshOriginFilter filter = new RefreshOriginFilter(
                new CorsProperties(List.of("http://localhost:5173")), new SecurityErrorHandler(new ObjectMapper()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DummyRefreshController()).addFilters(filter).build();

        mvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://attacker.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ACCESS_DENIED"));

        mvc.perform(post("/api/v1/auth/refresh").header("Origin", "http://localhost:5173")
                        .contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ACCESS_DENIED"));
    }

    @Test
    void rejectedLogoutAllKeepsCookieForClientRefreshRetry() throws Exception {
        SecurityErrorHandler handler = new SecurityErrorHandler(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout-all");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.commence(request, response, new BadCredentialsException("expired access token"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @RestController
    static class DummyRefreshController {
        @PostMapping("/api/v1/auth/refresh")
        String refresh() { return "ok"; }
    }
}
