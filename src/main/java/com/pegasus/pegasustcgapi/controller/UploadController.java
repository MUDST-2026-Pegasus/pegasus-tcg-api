package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.dto.PresignUploadRequest;
import com.pegasus.pegasustcgapi.dto.PresignUploadResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.UploadService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one way anything gets into object storage.
 *
 * <p>Files do not pass through this API at all: this endpoint returns a URL the
 * browser uploads to directly, and the caller then sends the returned key to
 * whichever endpoint owns the record.
 */
@RestController
@RequestMapping(ApiPaths.UPLOADS)
public class UploadController {

    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/presign")
    public ApiResponse<PresignUploadResponse> presign(
            @Valid @RequestBody PresignUploadRequest request, AuthPrincipal principal) {

        return ApiResponse.success("Upload authorised", uploads.presign(principal, request));
    }
}
