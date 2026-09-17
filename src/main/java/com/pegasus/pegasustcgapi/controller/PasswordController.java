package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.AuthResponse;
import com.pegasus.pegasustcgapi.dto.ChangePasswordRequest;
import com.pegasus.pegasustcgapi.dto.DevTokenResponse;
import com.pegasus.pegasustcgapi.dto.ForgotPasswordRequest;
import com.pegasus.pegasustcgapi.dto.ResetPasswordRequest;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.service.PasswordService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
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
    public ApiResult<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.reset(request);
        return ApiResult.success("Password reset; sign in again", null);
    }

    /** Returns a fresh token pair, since changing the password drops the old sessions. */
    @PostMapping("/change")
    public ApiResult<AuthResponse> change(
            AuthPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest http) {

        return ApiResult.success(
                "Password changed",
                passwordService.change(principal.userId(), request, ClientInfo.from(http)));
    }
}
