package com.pegasus.pegasustcgapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.controller.MockPaymentController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("Mock Payment Smoke Tests")
class MockPaymentSmokeTest {

    @Nested
    @SpringBootTest(properties = {
            "pegasus.payment.mock-enabled=false",
            "PAYMENT_MOCK_ENABLED=false"
    })
    @ActiveProfiles("prod")
    @DisplayName("When production profile is active (default production config)")
    class ProductionProfileTests {

        @Autowired
        private WebApplicationContext context;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(springSecurity())
                    .build();
        }

        @Test
        @DisplayName("MockPaymentController bean is NOT registered in application context")
        void mockPaymentControllerBeanAbsentInProd() {
            String[] beans = context.getBeanNamesForType(MockPaymentController.class);
            assertThat(beans)
                    .as("MockPaymentController must never be registered in production profile")
                    .isEmpty();
        }

        @Test
        @DisplayName("POST /api/v1/orders/{id}/pay endpoint returns 404 Not Found for authenticated request")
        void payEndpointNotFoundInProd() throws Exception {
            mockMvc.perform(post("/api/v1/orders/1/pay")
                            .with(jwt().jwt(j -> j.subject("100"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("OpenAPI /v1/api-docs does not contain /api/v1/orders/{id}/pay in production")
        void openApiDocsDoNotExposeMockPaymentInProd() throws Exception {
            mockMvc.perform(get("/v1/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/pay']").doesNotExist());
        }
    }

    @Nested
    @DisplayName("Startup Guard in Production")
    class ProductionStartupGuardTests {

        @Test
        @DisplayName("Starting application with no profile (as deployments run) and mock-enabled=true fails at startup")
        void startupFailsWhenMockEnabledWithoutProfile() {
            assertThatThrownBy(() -> {
                // Closed in case the guard ever lets it start, so the context does not leak.
                try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(PegasusTcgApiApplication.class)
                        .properties(
                                "pegasus.payment.mock-enabled=true",
                                "spring.main.web-application-type=none"
                        )
                        .run()) {
                    // started: assertThatThrownBy fails
                }
            })
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("CRITICAL SECURITY CONFIGURATION ERROR");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "pegasus.payment.mock-enabled=true",
            "PAYMENT_MOCK_ENABLED=true"
    })
    @ActiveProfiles("test")
    @DisplayName("When mock payment is enabled in non-production profile")
    class NonProductionEnabledTests {

        @Autowired
        private WebApplicationContext context;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(springSecurity())
                    .build();
        }

        @Test
        @DisplayName("MockPaymentController bean IS registered in application context")
        void mockPaymentControllerBeanPresentWhenEnabled() {
            String[] beans = context.getBeanNamesForType(MockPaymentController.class);
            assertThat(beans)
                    .as("MockPaymentController should be registered when mock-enabled is true in non-prod")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("OpenAPI /v1/api-docs exposes /api/v1/orders/{id}/pay when mock is enabled")
        void openApiDocsExposeMockPaymentWhenEnabled() throws Exception {
            mockMvc.perform(get("/v1/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/pay']").exists());
        }
    }
}
