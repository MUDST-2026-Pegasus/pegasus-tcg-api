package com.pegasus.pegasustcgapi.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
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
    
    @MockitoBean
    private StorageService storageService;

    private long userId;
    private String username;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        
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

        // Mock StorageService to throw exception like real service does
        org.mockito.Mockito.doThrow(new com.pegasus.pegasustcgapi.exception.NotFoundException(ErrorCode.FILE_NOT_FOUND))
                .when(storageService).requireUploadedFor(UploadPurpose.SELLER_VERIFICATION, fakeKey);

        mockMvc.perform(post("/api/v1/sellers/me/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.FILE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("complete submit -> key is saved, and GET returns presigned url")
    void completeSubmitReturnsPresignedUrl() throws Exception {
        // 1. Get a presigned upload URL
        String presignReq = """
                {
                    "purpose": "SELLER_VERIFICATION",
                    "contentType": "image/jpeg",
                    "sizeBytes": 1048576
                }
                """;

        when(storageService.uploadUrlTtlSeconds()).thenReturn(3600L);
        when(storageService.presignUpload(any())).thenReturn("https://mock-minio/upload");

        String presignResp = mockMvc.perform(post("/api/v1/uploads/presign")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presignReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String key = objectMapper.readTree(presignResp).path("data").path("objectKey").asText();

        // 2. Mock requireUploadedFor and presignDownload
        when(storageService.requireUploadedFor(UploadPurpose.SELLER_VERIFICATION, key))
                .thenReturn(new com.pegasus.pegasustcgapi.storage.StoredObject(key, 1048576L, "image/jpeg"));
        when(storageService.presignDownload(key)).thenReturn("https://mock-minio/download");

        // 3. Submit verification
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
                .andExpect(jsonPath("$.data.bankBookImageUrl").value("https://mock-minio/download"));

        // 4. GET /sellers/me/verifications
        mockMvc.perform(get("/api/v1/sellers/me/verifications")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bankBookImageKey").value(key))
                .andExpect(jsonPath("$.data[0].bankBookImageUrl").value("https://mock-minio/download"));
                
        // 5. GET /admin/verifications (Admin view)
        long adminId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "admin_" + username + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "admin_" + username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Admin")
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle().value1();
                
        mockMvc.perform(get("/api/v1/admin/verifications")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)).claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.bankBookImageKey == '%s')].bankBookImageUrl", key).exists());
    }
}
