package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.config.CorsProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
        RefreshOriginFilter filter = new RefreshOriginFilter(new CorsProperties(List.of("http://localhost:5173")));
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
}
