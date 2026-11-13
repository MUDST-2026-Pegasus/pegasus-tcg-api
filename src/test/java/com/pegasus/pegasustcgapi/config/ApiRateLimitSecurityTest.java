package com.pegasus.pegasustcgapi.config;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pegasus.pegasustcgapi.security.SecurityErrorHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(ApiRateLimitSecurityTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "pegasus.api.rate-limit-per-minute=2",
        "pegasus.auth.rate-limit-per-minute=30",
        "pegasus.promo.rate-limit-per-minute=30"
})
class ApiRateLimitSecurityTest {
    @Autowired private WebApplicationContext context;

    @Test
    void realChainLimitsBeforeAuthenticationAndPreservesCorsAndHealthChecks() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Missing and malformed credentials both spend the shared address budget.
        mvc.perform(get("/api/v1/private")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/private").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/games").header("Origin", "https://shop.example"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.example"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsString("Retry-After")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RATE_LIMITED")));
        mvc.perform(options("/api/v1/games").header("Origin", "https://shop.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean CorsProperties corsProperties() { return new CorsProperties(List.of("https://shop.example")); }
        @Bean SecurityErrorHandler errors() { return new SecurityErrorHandler(JsonMapper.builder().build()); }
        @Bean AuthProperties authProperties() {
            return new AuthProperties("a".repeat(64), "test", Duration.ofMinutes(15), Duration.ofDays(30),
                    Duration.ofHours(1), 5, Duration.ofMinutes(15), false);
        }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController
    static class Endpoints {
        @GetMapping({"/api/v1/games", "/api/v1/private", "/actuator/health"})
        String ok() { return "ok"; }
    }
}
