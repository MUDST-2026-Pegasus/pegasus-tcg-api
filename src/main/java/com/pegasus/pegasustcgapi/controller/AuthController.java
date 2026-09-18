package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.LoginRequest;
import com.pegasus.pegasustcgapi.dto.RefreshRequest;
import com.pegasus.pegasustcgapi.dto.RegisterRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.service.AuthService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "User registration, login, token refresh, and session management")
@RestController
@RequestMapping(ApiPaths.AUTH)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register new account", description = "Creates a new buyer or seller user account and returns the initial access and refresh tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account successfully created"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload or validation failed"),
            @ApiResponse(responseCode = "409", description = "Username or email already in use")
    })
    @PostMapping("/register")
    public ResponseEntity<ApiResult<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {

        AuthResponse response = authService.register(request, ClientInfo.from(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Account created", response));
    }

    @Operation(summary = "Sign in to account", description = "Authenticates using username/email and password, returning JWT access and refresh tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Successfully signed in"),
            @ApiResponse(responseCode = "400", description = "Invalid credentials or account suspended")
    })
    @PostMapping("/login")
    public ApiResult<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResult.success("Signed in", authService.login(request, ClientInfo.from(http)));
    }

    /** Exchanges a refresh token for a new pair. The old refresh token stops working. */
    @Operation(summary = "Refresh access token", description = "Exchanges a valid refresh token for a new token pair. The old refresh token is revoked.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token pair refreshed"),
            @ApiResponse(responseCode = "400", description = "Expired, spent, or invalid refresh token")
    })
    @PostMapping("/refresh")
    public ApiResult<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ApiResult.success(
                "Token refreshed", authService.refresh(request.refreshToken(), ClientInfo.from(http)));
    }

    /** Ends this session. Succeeds even for a token that was already dead. */
    @Operation(summary = "Sign out of current session", description = "Revokes the specified refresh token, ending the active session.")
    @ApiResponse(responseCode = "200", description = "Successfully signed out")
    @PostMapping("/logout")
    public ApiResult<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResult.success("Signed out", null);
    }

    /** Ends every session of the signed-in user, this one included. */
    @Operation(summary = "Sign out of all sessions", description = "Revokes all refresh tokens belonging to the authenticated user.")
    @ApiResponse(responseCode = "200", description = "Signed out of all sessions")
    @PostMapping("/logout-all")
    public ApiResult<Void> logoutAll(AuthPrincipal principal) {
        authService.logoutAll(principal.userId());
        return ApiResult.success("Signed out of all sessions", null);
    }

    /** The signed-in account, read fresh from the database rather than from the token. */
    @Operation(summary = "Get current profile", description = "Returns profile information for the authenticated user, fetched live from the database.")
    @ApiResponse(responseCode = "200", description = "Profile retrieved")
    @GetMapping("/me")
    public ApiResult<UserResponse> me(AuthPrincipal principal) {
        return ApiResult.success(authService.currentUser(principal.userId()));
    }
}
