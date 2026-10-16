package com.pegasus.pegasustcgapi;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@DisplayName("OpenAPI & Swagger UI Endpoints")
class OpenApiSwaggerSmokeTest {

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
    @DisplayName("serves OpenAPI 3 specification JSON at /v1/api-docs without authentication")
    void openApiDocsAvailableUnauthenticated() throws Exception {
        mockMvc.perform(get("/v1/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Pegasus TCG API"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$.info.description").value(org.hamcrest.Matchers.containsString("Total Endpoints:")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].BearerAuth").exists());
    }

    @Test
    @DisplayName("documents checkout requirements and refund error responses")
    void documentsCurrentCheckoutAndRefundContracts() throws Exception {
        mockMvc.perform(get("/v1/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.description").value(org.hamcrest.Matchers.containsString("refunds")))
                .andExpect(jsonPath("$.paths['/api/v1/checkout'].post.parameters[0].name")
                        .value("Idempotency-Key"))
                .andExpect(jsonPath("$.paths['/api/v1/checkout'].post.parameters[0].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/refunds/{id}/destination'].put.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/refunds/{id}/destination'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/refunds/{id}/transfers'].get.summary")
                        .value("List transfer attempts for a refund"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/refunds/{id}/failed'].post.responses['403']").exists());
    }

    @Test
    @DisplayName("documents public listing seller reputation and delivery fields")
    void documentsPublicListingSummary() throws Exception {
        mockMvc.perform(get("/v1/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.PublicListingResponse.properties.delivery").exists())
                .andExpect(jsonPath("$.components.schemas.PublicListingResponse.properties.seller").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("fastestEstDaysMin"),
                                org.hamcrest.Matchers.containsString("freeShipping"),
                                org.hamcrest.Matchers.containsString("salesCount"),
                                org.hamcrest.Matchers.containsString("ratingAvg"))));
    }

    @Test
    @DisplayName("redirects /v1/api-doc to /v1/api-docs")
    void openApiDocsAliasRedirects() throws Exception {
        mockMvc.perform(get("/v1/api-doc"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("redirects legacy /v3/api-docs to /v1/api-docs")
    void legacyOpenApiDocsRedirects() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("serves Swagger UI endpoint without requiring authentication")
    void swaggerUiAvailableUnauthenticated() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
