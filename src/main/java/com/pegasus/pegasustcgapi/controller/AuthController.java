package com.pegasus.pegasustcgapi.controller;

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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping(ApiPaths.AUTH)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResult<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {

        AuthResponse response = authService.register(request, ClientInfo.from(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Account created", response));
    }

    @PostMapping("/login")
    public ApiResult<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResult.success("Signed in", authService.login(request, ClientInfo.from(http)));
    }

    /** Exchanges a refresh token for a new pair. The old refresh token stops working. */
    @PostMapping("/refresh")
    public ApiResult<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ApiResult.success(
                "Token refreshed", authService.refresh(request.refreshToken(), ClientInfo.from(http)));
    }

    /** Ends this session. Succeeds even for a token that was already dead. */
    @PostMapping("/logout")
    public ApiResult<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResult.success("Signed out", null);
    }

    /** Ends every session of the signed-in user, this one included. */
    @PostMapping("/logout-all")
    public ApiResult<Void> logoutAll(AuthPrincipal principal) {
        authService.logoutAll(principal.userId());
        return ApiResult.success("Signed out of all sessions", null);
    }

    /** The signed-in account, read fresh from the database rather than from the token. */
    @GetMapping("/me")
    public ApiResult<UserResponse> me(AuthPrincipal principal) {
        return ApiResult.success(authService.currentUser(principal.userId()));
    }
}
