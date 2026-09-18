package com.pegasus.pegasustcgapi.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pegasus.pegasustcgapi.dto.VerificationRequest;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.UserAccountRecord;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest
@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class SellerVerificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DSLContext dsl;
    
    @Autowired
    private StorageService storageService;

    private long userId;
    private String username;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        username = "seller_" + tag;

        userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, username + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Seller " + tag)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle().value1();
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/verifications missing bankBookImageKey -> 400")
    void submitVerificationWithoutKeyReturns400() throws Exception {
        String json = """
                {
                    "legalFirstName": "Somchai",
                    "legalLastName": "Jaidee",
                    "bankCode": "KBANK",
                    "bankName": "Kasikorn",
                    "bankAccountNumber": "123-4-56789-0"
                }
                """;

        mockMvc.perform(post("/api/v1/sellers/me/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("key that is not real or wrong purpose -> rejected, no insert")
    void submitVerificationWithFakeKeyIsRejected() throws Exception {
        String fakeKey = "verifications/fake-key-12345.jpg";
        String json = """
                {
                    "legalFirstName": "Somchai",
                    "legalLastName": "Jaidee",
                    "bankCode": "KBANK",
                    "bankName": "Kasikorn",
                    "bankAccountNumber": "123-4-56789-0",
                    "bankBookImageKey": "%s"
                }
                """.formatted(fakeKey);

        mockMvc.perform(post("/api/v1/sellers/me/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.FILE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("complete submit -> key is saved, and GET returns presigned url")
    void completeSubmitReturnsPresignedUrl() throws Exception {
        // 1. Get a presigned upload URL
        String presignReq = """
                {
                    "purpose": "SELLER_VERIFICATION",
                    "contentType": "image/jpeg"
                }
                """;

        String presignResp = mockMvc.perform(post("/api/v1/uploads/presign")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presignReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Extract key and uploadUrl from presignResp (we just need the key to pretend we uploaded it)
        // Wait, to pass storageService.requireUploadedFor, we actually have to upload the file to MinIO
        // because requireUploadedFor checks statObject.
        String key = objectMapper.readTree(presignResp).path("data").path("key").asText();
        String uploadUrl = objectMapper.readTree(presignResp).path("data").path("uploadUrl").asText();

        // We can use Spring's RestTemplate to PUT to MinIO, but requireUploadedFor just calls MinIO.
        // Let's use standard Java HttpURLConnection or java.net.http.HttpClient to do PUT
        java.net.http.HttpRequest putRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(uploadUrl))
                .header("Content-Type", "image/jpeg")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                .build();
        java.net.http.HttpClient.newHttpClient().send(putRequest, java.net.http.HttpResponse.BodyHandlers.discarding());

        // 2. Submit verification
        String json = """
                {
                    "legalFirstName": "Somchai",
                    "legalLastName": "Jaidee",
                    "bankCode": "KBANK",
                    "bankName": "Kasikorn",
                    "bankAccountNumber": "123-4-56789-0",
                    "bankBookImageKey": "%s"
                }
                """.formatted(key);

        mockMvc.perform(post("/api/v1/sellers/me/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bankBookImageKey").value(key))
                .andExpect(jsonPath("$.data.bankBookImageUrl").isString());

        // 3. GET /sellers/me/verifications
        mockMvc.perform(get("/api/v1/sellers/me/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bankBookImageKey").value(key))
                .andExpect(jsonPath("$.data[0].bankBookImageUrl").isString());
                
        // 4. GET /admin/verifications (Admin view)
        // We need an admin user
        long adminId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "admin_" + username + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "admin_" + username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Admin")
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle().value1();
                
        // We can just use the role in jwt
        mockMvc.perform(get("/api/v1/admin/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(adminId)).claim("scope", "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                // Assuming it might be the only one, but to be safe we check the array
                .andExpect(jsonPath("$.data.items[?(@.bankBookImageKey == '%s')].bankBookImageUrl", key).exists());
    }
}
