package com.pegasus.pegasustcgapi.auth.controller;

import com.pegasus.pegasustcgapi.auth.dto.AuthResponse;
import com.pegasus.pegasustcgapi.auth.dto.ChangePasswordRequest;
import com.pegasus.pegasustcgapi.auth.dto.DevTokenResponse;
import com.pegasus.pegasustcgapi.auth.dto.ForgotPasswordRequest;
import com.pegasus.pegasustcgapi.auth.dto.ResetPasswordRequest;
import com.pegasus.pegasustcgapi.auth.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.auth.security.ClientInfo;
import com.pegasus.pegasustcgapi.auth.service.PasswordService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Forgotten, reset and changed passwords. */
@RestController
@RequestMapping(ApiPaths.AUTH + "/password")
public class PasswordController {

    private final PasswordService passwordService;

    public PasswordController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }


    @PostMapping("/reset")
    public ApiResponse<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.reset(request);
        return ApiResponse.success("Password reset; sign in again", null);
    }

    /** Returns a fresh token pair, since changing the password drops the old sessions. */
    @PostMapping("/change")
    public ApiResponse<AuthResponse> change(
            AuthPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest http) {

        return ApiResponse.success(
                "Password changed",
                passwordService.change(principal.userId(), request, ClientInfo.from(http)));
    }
}
