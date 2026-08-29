package com.pegasus.pegasustcgapi.auth.controller;

import com.pegasus.pegasustcgapi.auth.dto.AuthResponse;
import com.pegasus.pegasustcgapi.auth.dto.LoginRequest;
import com.pegasus.pegasustcgapi.auth.dto.RefreshRequest;
import com.pegasus.pegasustcgapi.auth.dto.RegisterRequest;
import com.pegasus.pegasustcgapi.auth.dto.UserResponse;
import com.pegasus.pegasustcgapi.auth.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.auth.security.ClientInfo;
import com.pegasus.pegasustcgapi.auth.service.AuthService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-up, sign-in and session lifetime.
 *
 * <p>{@code /register}, {@code /login}, {@code /refresh} and {@code /logout} are
 * open; everything else on this controller needs a bearer token. Logout is open
 * on purpose, so a client whose access token has already expired can still hand
 * its refresh token back to be revoked.
 */
@RestController
@RequestMapping(ApiPaths.AUTH)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Creates the account and signs it in, so the client needs no second call. */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {

        AuthResponse response = authService.register(request, ClientInfo.from(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Account created", response));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponse.success("Signed in", authService.login(request, ClientInfo.from(http)));
    }

    /** Exchanges a refresh token for a new pair. The old refresh token stops working. */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ApiResponse.success(
                "Token refreshed", authService.refresh(request.refreshToken(), ClientInfo.from(http)));
    }

    /** Ends this session. Succeeds even for a token that was already dead. */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.success("Signed out", null);
    }

    /** Ends every session of the signed-in user, this one included. */
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(AuthPrincipal principal) {
        authService.logoutAll(principal.userId());
        return ApiResponse.success("Signed out of all sessions", null);
    }

    /** The signed-in account, read fresh from the database rather than from the token. */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(AuthPrincipal principal) {
        return ApiResponse.success(authService.currentUser(principal.userId()));
    }
}
