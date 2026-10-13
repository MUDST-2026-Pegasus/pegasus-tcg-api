package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.UpdateUserStatusRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin users table: search, read, suspend. Roles stay on {@code /users/{id}/roles}. */
@Tag(name = "Users (Admin)", description = "List, search and suspend user accounts (Admin only)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @Operation(summary = "List users", description = "Paginated, newest first. q matches email, username or display name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page retrieved"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role")
    })
    @GetMapping
    public ApiResult<PageResponse<UserResponse>> list(
            @Parameter(description = "Search text") @RequestParam(required = false) String q,
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(service.search(q, role, status, page, size));
    }

    @Operation(summary = "Get user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User retrieved"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{userId}")
    public ApiResult<UserResponse> get(@PathVariable long userId) {
        return ApiResult.success(service.get(userId));
    }

    @Operation(summary = "Change user status",
            description = "Suspends, restores or closes an account. Leaving ACTIVE revokes all the user's refresh tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status changed"),
            @ApiResponse(responseCode = "400", description = "Request payload failed validation"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role, or targets themselves"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PatchMapping("/{userId}/status")
    public ApiResult<UserResponse> changeStatus(
            @PathVariable long userId,
            @Valid @RequestBody UpdateUserStatusRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Status updated",
                service.changeStatus(userId, request.status(), principal.userId()));
    }
}
