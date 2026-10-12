package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.LoginRequest;
import com.pegasus.pegasustcgapi.dto.RefreshRequest;
import com.pegasus.pegasustcgapi.dto.RegisterRequest;
import com.pegasus.pegasustcgapi.dto.TokenResponse;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.service.AuthService;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller API unit & MockMvc test suite for AuthController (TCG-389).
 * Tests request validation, status codes (200, 201, 400, 401, 403, 409),
 * and payload mappings.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController MockMvc Tests")
class AuthControllerTest {

    private static final String BASE_URL = "/api/v1/auth";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuthResponse sampleAuthResponse(long userId, String email, String username) {
        AuthUser user = anActiveUser()
                .withId(userId)
                .withEmail(email)
                .withUsername(username)
                .withRoles(RoleCode.BUYER)
                .build();
        UserResponse userResponse = UserResponse.from(user);
        TokenResponse tokens = TokenResponse.bearer("access.jwt.token", 900L, "refresh.jwt.token", 2592000L);
        return new AuthResponse(userResponse, tokens);
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class RegisterTests {

        @Test
        @DisplayName("Positive: Register returns 201 Created and token envelope on valid request")
        void registerSuccessReturns201() throws Exception {
            RegisterRequest request = new RegisterRequest(
                    "newbuyer@example.com", "newbuyer", "New Buyer", "validPassword123", "+66812345678", Set.of(RoleCode.BUYER));
            AuthResponse authResponse = sampleAuthResponse(10L, "newbuyer@example.com", "newbuyer");

            given(authService.register(any(RegisterRequest.class), any(ClientInfo.class)))
                    .willReturn(authResponse);

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.tokens.accessToken").value("access.jwt.token"))
                    .andExpect(jsonPath("$.data.tokens.refreshToken").value("refresh.jwt.token"))
                    .andExpect(jsonPath("$.data.tokens.expiresIn").value(900));
        }

        @Test
        @DisplayName("Negative: Register returns 400 Bad Request when password shorter than 8 characters")
        void registerWithShortPasswordReturns400() throws Exception {
            RegisterRequest request = new RegisterRequest(
                    "shortpass@example.com", "shortuser", "Valid Name", "short", null, Set.of(RoleCode.BUYER));

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
        }

        @Test
        @DisplayName("Negative: Register returns 400 Bad Request when email format is invalid")
        void registerWithInvalidEmailReturns400() throws Exception {
            RegisterRequest request = new RegisterRequest(
                    "invalid-email-format", "validuser", "Valid Name", "password123", null, Set.of(RoleCode.BUYER));

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
        }

        @Test
        @DisplayName("Negative: Register returns 409 Conflict when email or username is already in use")
        void registerWithExistingEmailReturns409() throws Exception {
            RegisterRequest request = new RegisterRequest(
                    "taken@example.com", "takenuser", "Taken Name", "password123", null, Set.of(RoleCode.BUYER));

            given(authService.register(any(RegisterRequest.class), any(ClientInfo.class)))
                    .willThrow(new ConflictException(ErrorCode.EMAIL_ALREADY_USED));

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.EMAIL_ALREADY_USED.name()));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginTests {

        @Test
        @DisplayName("Positive: Login returns 200 OK and token pair on matching credentials")
        void loginSuccessReturns200() throws Exception {
            LoginRequest request = new LoginRequest("user@example.com", "correctPassword123");
            AuthResponse authResponse = sampleAuthResponse(20L, "user@example.com", "validuser");

            given(authService.login(any(LoginRequest.class), any(ClientInfo.class)))
                    .willReturn(authResponse);

            mockMvc.perform(post(BASE_URL + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.tokens.accessToken").value("access.jwt.token"))
                    .andExpect(jsonPath("$.data.tokens.refreshToken").value("refresh.jwt.token"));
        }

        @Test
        @DisplayName("Negative: Login returns 401 Unauthorized on invalid email or password")
        void loginWithInvalidCredentialsReturns401() throws Exception {
            LoginRequest request = new LoginRequest("user@example.com", "wrongPassword");

            given(authService.login(any(LoginRequest.class), any(ClientInfo.class)))
                    .willThrow(new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(post(BASE_URL + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.INVALID_CREDENTIALS.name()));
        }

        @Test
        @DisplayName("Negative: Login returns 403 Forbidden when account is locked after excessive failed attempts")
        void loginWithLockedAccountReturns403() throws Exception {
            LoginRequest request = new LoginRequest("locked@example.com", "anyPassword");

            given(authService.login(any(LoginRequest.class), any(ClientInfo.class)))
                    .willThrow(new ForbiddenException(ErrorCode.ACCOUNT_LOCKED));

            mockMvc.perform(post(BASE_URL + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.ACCOUNT_LOCKED.name()));
        }

        @Test
        @DisplayName("Negative: Login returns 400 Bad Request on empty or missing email/password")
        void loginWithEmptyFieldsReturns400() throws Exception {
            LoginRequest request = new LoginRequest("", "");

            mockMvc.perform(post(BASE_URL + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshTests {

        @Test
        @DisplayName("Positive: Refresh returns 200 OK and rotated token pair")
        void refreshSuccessReturns200() throws Exception {
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            AuthResponse response = sampleAuthResponse(30L, "refresh@example.com", "refresher");

            given(authService.refresh(eq("valid-refresh-token"), any(ClientInfo.class)))
                    .willReturn(response);

            mockMvc.perform(post(BASE_URL + "/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.tokens.accessToken").value("access.jwt.token"))
                    .andExpect(jsonPath("$.data.tokens.refreshToken").value("refresh.jwt.token"));
        }

        @Test
        @DisplayName("Negative: Refresh returns 401 Unauthorized when token is invalid or expired")
        void refreshWithInvalidTokenReturns401() throws Exception {
            RefreshRequest request = new RefreshRequest("expired-token");

            given(authService.refresh(eq("expired-token"), any(ClientInfo.class)))
                    .willThrow(new UnauthorizedException(ErrorCode.INVALID_TOKEN));

            mockMvc.perform(post(BASE_URL + "/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.INVALID_TOKEN.name()));
        }

        @Test
        @DisplayName("Negative: Refresh returns 400 Bad Request when token string is blank")
        void refreshWithBlankTokenReturns400() throws Exception {
            RefreshRequest request = new RefreshRequest("");

            mockMvc.perform(post(BASE_URL + "/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout and /logout-all")
    class LogoutTests {

        @Test
        @DisplayName("Positive: Logout revokes session and returns 200 OK")
        void logoutReturns200() throws Exception {
            RefreshRequest request = new RefreshRequest("session-refresh-token");

            mockMvc.perform(post(BASE_URL + "/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(authService).logout("session-refresh-token");
        }

        @Test
        @DisplayName("Positive: Logout-all revokes all user sessions and returns 200 OK")
        void logoutAllWithPrincipalReturns200() throws Exception {
            Jwt jwt = Jwt.withTokenValue("mock-access-token")
                    .header("alg", "HS256")
                    .subject("42")
                    .claim(AuthClaims.EMAIL, "user@example.com")
                    .claim(AuthClaims.USERNAME, "user42")
                    .claim(AuthClaims.ROLES, java.util.List.of("BUYER"))
                    .issuedAt(java.time.Instant.now())
                    .expiresAt(java.time.Instant.now().plusSeconds(3600))
                    .build();
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));

            mockMvc.perform(post(BASE_URL + "/logout-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(authService).logoutAll(42L);
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("Negative: Logout-all without authentication returns 401 Unauthorized")
        void logoutAllUnauthenticatedReturns401() throws Exception {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();

            mockMvc.perform(post(BASE_URL + "/logout-all"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.UNAUTHENTICATED.name()));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/me")
    class CurrentUserTests {

        @Test
        @DisplayName("Positive: Me returns 200 OK and UserResponse for authenticated user")
        void meReturnsUserProfile() throws Exception {
            Jwt jwt = Jwt.withTokenValue("mock-access-token")
                    .header("alg", "HS256")
                    .subject("55")
                    .claim(AuthClaims.EMAIL, "me@example.com")
                    .claim(AuthClaims.USERNAME, "myme")
                    .claim(AuthClaims.ROLES, java.util.List.of("BUYER"))
                    .issuedAt(java.time.Instant.now())
                    .expiresAt(java.time.Instant.now().plusSeconds(3600))
                    .build();
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));

            AuthUser user = anActiveUser()
                    .withId(55L)
                    .withEmail("me@example.com")
                    .withUsername("myme")
                    .withRoles(RoleCode.BUYER)
                    .build();
            UserResponse userResponse = UserResponse.from(user);

            given(authService.currentUser(55L)).willReturn(userResponse);

            mockMvc.perform(get(BASE_URL + "/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(55))
                    .andExpect(jsonPath("$.data.email").value("me@example.com"))
                    .andExpect(jsonPath("$.data.username").value("myme"))
                    .andExpect(jsonPath("$.data.roles[0]").value("BUYER"));

            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("Negative: Me without authentication returns 401 Unauthorized")
        void meUnauthenticatedReturns401() throws Exception {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();

            mockMvc.perform(get(BASE_URL + "/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value(ErrorCode.UNAUTHENTICATED.name()));
        }
    }
}
