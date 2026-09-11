package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.dto.SettingUpdateRequest;
import com.pegasus.pegasustcgapi.repository.PlatformSettingRepository.Setting;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PlatformSettingService;
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
@RestController
@RequestMapping(ApiPaths.ADMIN + "/settings")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingController {

    private final PlatformSettingService settings;

    public AdminSettingController(PlatformSettingService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ApiResponse<List<Setting>> list() {
        return ApiResponse.success(settings.list());
    }

    @GetMapping("/{key}")
    public ApiResponse<Setting> get(@PathVariable String key) {
        return ApiResponse.success(settings.get(key));
    }

    @PutMapping("/{key}")
    public ApiResponse<Setting> update(
            @PathVariable String key,
            @Valid @RequestBody SettingUpdateRequest request,
            AuthPrincipal principal) {

        return ApiResponse.success("Setting updated",
                settings.update(key, request.value(), principal.userId()));
    }
}
