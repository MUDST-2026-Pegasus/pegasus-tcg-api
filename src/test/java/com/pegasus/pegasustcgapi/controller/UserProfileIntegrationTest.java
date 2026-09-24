package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@DisplayName("UserProfile integration tests")
class UserProfileIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private StorageService storageService;

    private long userId;
    private String email;
    private String username;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String tag = UUID.randomUUID().toString().substring(0, 8);
        username = "user_" + tag;
        email = username + "@example.com";

        userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.PASSWORD_HASH, "hash_dummy")
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Original Name")
                .set(USER_ACCOUNT.BIO, "Original Bio")
                .set(USER_ACCOUNT.PHONE, "+66800000000")
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle().value1();
    }

    @Test
    @DisplayName("PUT /api/v1/users/me successfully updates profile and GET /api/v1/auth/me reflects changes")
    void updateProfileSuccessAndVerifyViaGetAuthMe() throws Exception {
        String updateJson = """
                {
                    "displayName": "Updated Display Name",
                    "bio": "Updated Bio Description",
                    "phone": "+66812345678",
                    "avatarUrl": "avatars/2026/09/sample.png"
                }
                """;

        // PUT /api/v1/users/me
        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Updated Display Name"))
                .andExpect(jsonPath("$.data.bio").value("Updated Bio Description"))
                .andExpect(jsonPath("$.data.phone").value("+66812345678"))
                .andExpect(jsonPath("$.data.avatarUrl").value("avatars/2026/09/sample.png"));

        // Acceptance criteria: GET /api/v1/auth/me returns updated values
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Updated Display Name"))
                .andExpect(jsonPath("$.data.bio").value("Updated Bio Description"))
                .andExpect(jsonPath("$.data.phone").value("+66812345678"))
                .andExpect(jsonPath("$.data.avatarUrl").value("avatars/2026/09/sample.png"));
    }

    @Test
    @DisplayName("Forbidden fields (email, username) in request body cannot be changed")
    void forbiddenFieldsCannotBeChanged() throws Exception {
        String updateJson = """
                {
                    "displayName": "Changed Name Only",
                    "email": "hacked@example.com",
                    "username": "hackeduser"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Changed Name Only"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.username").value(username));

        // Acceptance criteria: GET /auth/me verifies forbidden fields remain untouched
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Changed Name Only"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    @DisplayName("PUT /api/v1/users/me without token returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        String updateJson = """
                {
                    "displayName": "Unauthorized Name"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Non-existent avatar key is rejected by storage validation")
    void nonExistentAvatarKeyIsRejected() throws Exception {
        String missingKey = "avatars/missing.png";
        doThrow(new NotFoundException(ErrorCode.FILE_NOT_FOUND, "File not found"))
                .when(storageService).requireUploadedFor(UploadPurpose.AVATAR_IMAGE, missingKey);

        String updateJson = """
                {
                    "avatarUrl": "avatars/missing.png"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.FILE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Avatar key from wrong prefix is rejected by storage validation")
    void wrongPrefixAvatarKeyIsRejected() throws Exception {
        String wrongKey = "slips/payment.jpg";
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "The key must come from a AVATAR_IMAGE upload"))
                .when(storageService).requireUploadedFor(UploadPurpose.AVATAR_IMAGE, wrongKey);

        String updateJson = """
                {
                    "avatarUrl": "slips/payment.jpg"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("Blank displayName returns 400 VALIDATION_FAILED")
    void blankDisplayNameReturns400() throws Exception {
        String updateJson = """
                {
                    "displayName": "    "
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("Invalid phone format returns 400 VALIDATION_FAILED")
    void invalidPhoneFormatReturns400() throws Exception {
        String updateJson = """
                {
                    "phone": "invalid_number_abc"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }
}
