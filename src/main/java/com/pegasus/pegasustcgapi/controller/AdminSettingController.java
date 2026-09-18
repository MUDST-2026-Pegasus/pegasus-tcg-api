package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.SettingUpdateRequest;
import com.pegasus.pegasustcgapi.repository.PlatformSettingRepository.Setting;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PlatformSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime configuration [RQ-14] — escrow hold time, the fallback commission
 * rate, the cancellation window.
 *
 * <p>Only existing keys can be written. A new setting arrives through a
 * migration, next to the code that reads it, so a typo here cannot invent one
 * that nothing will ever look at.
 */
@Tag(name = "Platform Settings (Admin)", description = "Admin platform runtime configurations")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/settings")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingController {

    private final PlatformSettingService settings;

    public AdminSettingController(PlatformSettingService settings) {
        this.settings = settings;
    }

    @Operation(summary = "List platform settings", description = "Retrieves all runtime platform configuration settings.")
    @ApiResponse(responseCode = "200", description = "Settings listed")
    @GetMapping
    public ApiResult<List<Setting>> list() {
        return ApiResult.success(settings.list());
    }

    @Operation(summary = "Get platform setting", description = "Retrieves a specific runtime setting by key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Setting retrieved"),
            @ApiResponse(responseCode = "404", description = "Setting key not found")
    })
    @GetMapping("/{key}")
    public ApiResult<Setting> get(@PathVariable String key) {
        return ApiResult.success(settings.get(key));
    }

    @Operation(summary = "Update platform setting", description = "Updates the value of an existing platform setting.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Setting updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "404", description = "Setting key not found")
    })
    @PutMapping("/{key}")
    public ApiResult<Setting> update(
            @PathVariable String key,
            @Valid @RequestBody SettingUpdateRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Setting updated",
                settings.update(key, request.value(), principal.userId()));
    }
}
