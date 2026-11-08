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
                .andExpect(jsonPath("$.info.contact.email").value("thanapat.boat16@gmail.com"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$.info.description").value(org.hamcrest.Matchers.containsString("Total Endpoints:")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].BearerAuth").exists());
    }

    @Test
    @DisplayName("documents bearer authentication for protected operations and keeps guest operations public")
    void documentsAuthenticationRequirements() throws Exception {
        mockMvc.perform(get("/v1/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/pricing/market-run'].post.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/games'].get.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/catalog/products'].post.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sellers/me/listings'].get.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sellers/me/listings/stock-forecast'].get.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sellers/me/units'].get.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/collection'].get.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/uploads/presign'].post.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/cart/items/batch'].post.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/cart/merge'].post.security[0].BearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/cart/items'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/listings'].get.security").doesNotExist());
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
    @DisplayName("documents the secured Buyer, Seller and Admin order summary PDF downloads")
    void documentsOrderSummaryPdfDownloads() throws Exception {
        mockMvc.perform(get("/v1/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/summary.pdf'].get.responses['200'].content['application/pdf'].schema.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/summary.pdf'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sellers/me/orders/{id}/summary.pdf'].get.responses['200'].content['application/pdf'].schema.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/v1/sellers/me/orders/{id}/summary.pdf'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/orders/{sellerOrderId}/summary.pdf'].get.responses['200'].content['application/pdf'].schema.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/orders/{sellerOrderId}/summary.pdf'].get.responses['403']")
                        .exists());
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
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("index.css")));
        mockMvc.perform(get("/swagger-ui/index.css"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("golden-council.png")));
        mockMvc.perform(get("/swagger-ui/golden-council.png"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("image/png"));
    }
}
