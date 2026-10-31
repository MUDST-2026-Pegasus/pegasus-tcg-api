package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.ChangePasswordRequest;
import com.pegasus.pegasustcgapi.dto.DevTokenResponse;
import com.pegasus.pegasustcgapi.dto.ForgotPasswordRequest;
import com.pegasus.pegasustcgapi.dto.ResetPasswordRequest;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.security.RefreshCookie;
import com.pegasus.pegasustcgapi.service.PasswordService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Forgotten, reset and changed passwords. */
@Tag(name = "Password Management", description = "Endpoints for password reset and credential change")
@RestController
@RequestMapping(ApiPaths.AUTH + "/password")
public class PasswordController {

    private final PasswordService passwordService;
    private final RefreshCookie refreshCookie;

    public PasswordController(PasswordService passwordService, RefreshCookie refreshCookie) {
        this.passwordService = passwordService;
        this.refreshCookie = refreshCookie;
    }

    @Operation(summary = "Reset password", description = "Resets user password using a valid password reset token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successfully"),
            @ApiResponse(responseCode = "400", description = "Request payload failed validation"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired reset token")
    })
    @PostMapping("/reset")
    public ApiResult<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.reset(request);
        return ApiResult.success("Password reset; sign in again", null);
    }

    /** Returns a fresh access token and refresh cookie, since changing the password drops old sessions. */
    @Operation(summary = "Change password", description = "Changes password for the currently signed-in user, returns a fresh access token, and sets a new HttpOnly refresh cookie.",
            security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Request payload failed validation"),
            @ApiResponse(responseCode = "401", description = "Current password is incorrect or unauthenticated")
    })
    @PostMapping("/change")
    public ApiResult<AuthResponse> change(
            AuthPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest http, HttpServletResponse servletResponse) {

        AuthResponse response = passwordService.change(principal.userId(), request, ClientInfo.from(http));
        refreshCookie.set(servletResponse, response.tokens().refreshToken());
        return ApiResult.success("Password changed", response);
    }
}
