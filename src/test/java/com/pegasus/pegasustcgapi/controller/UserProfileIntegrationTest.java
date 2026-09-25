package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
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

        // Make presignDownload return a predictable URL for assertions
        when(storageService.presignDownload(anyString()))
                .thenAnswer(inv -> "https://cdn.example.com/" + inv.getArgument(0));
    }

    // ── Acceptance criteria 1: PUT updates, GET /auth/me reflects changes ──

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

        // PUT /api/v1/users/me — response should contain presigned avatar URL
        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Updated Display Name"))
                .andExpect(jsonPath("$.data.bio").value("Updated Bio Description"))
                .andExpect(jsonPath("$.data.phone").value("+66812345678"))
                .andExpect(jsonPath("$.data.avatarUrl").value(
                        "https://cdn.example.com/avatars/2026/09/sample.png"));

        // GET /api/v1/auth/me — should also return presigned avatar URL
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Updated Display Name"))
                .andExpect(jsonPath("$.data.bio").value("Updated Bio Description"))
                .andExpect(jsonPath("$.data.phone").value("+66812345678"))
                .andExpect(jsonPath("$.data.avatarUrl").value(
                        "https://cdn.example.com/avatars/2026/09/sample.png"));
    }

    // ── Acceptance criteria 2: forbidden fields cannot be changed ──

    @Test
    @DisplayName("Forbidden fields (email, username) in request body are ignored")
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

        // Verify via GET /auth/me — forbidden fields remain untouched
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Changed Name Only"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.username").value(username));
    }

    // ── Acceptance criteria 3: unauthenticated → 401 ──

    @Test
    @DisplayName("PUT /api/v1/users/me without token returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Unauthorized Name\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Avatar storage validation ──

    @Test
    @DisplayName("Non-existent avatar key is rejected by storage validation")
    void nonExistentAvatarKeyIsRejected() throws Exception {
        String missingKey = "avatars/missing.png";
        doThrow(new NotFoundException(ErrorCode.FILE_NOT_FOUND, "File not found"))
                .when(storageService).requireUploadedFor(UploadPurpose.AVATAR_IMAGE, missingKey);

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\": \"avatars/missing.png\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.FILE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Avatar key from wrong prefix is rejected by storage validation")
    void wrongPrefixAvatarKeyIsRejected() throws Exception {
        String wrongKey = "slips/payment.jpg";
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "Wrong prefix"))
                .when(storageService).requireUploadedFor(UploadPurpose.AVATAR_IMAGE, wrongKey);

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\": \"slips/payment.jpg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    // ── Avatar ownership ──

    @Test
    @DisplayName("Avatar key already used by another user returns 409 Conflict")
    void avatarKeyUsedByAnotherReturns409() throws Exception {
        // Insert another user who already has this avatar
        String otherTag = UUID.randomUUID().toString().substring(0, 8);
        dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "other_" + otherTag + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "hash")
                .set(USER_ACCOUNT.USERNAME, "other_" + otherTag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Other")
                .set(USER_ACCOUNT.AVATAR_URL, "avatars/stolen.png")
                .execute();

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\": \"avatars/stolen.png\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.IMAGE_KEY_IN_USE.name()));
    }

    // ── DTO validation ──

    @Test
    @DisplayName("Blank displayName returns 400 VALIDATION_FAILED with violations")
    void blankDisplayNameReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"    \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("Invalid phone format (e.g. '+++') returns 400 VALIDATION_FAILED")
    void invalidPhoneFormatReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"+++\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("Phone with only alphabet characters returns 400 VALIDATION_FAILED")
    void alphabetPhoneReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(userId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"invalid_number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }
}
