package com.pegasus.pegasustcgapi.controller.audit;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.audit.AuditLogResponse;
import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.model.audit.AuditLogFilter;
import com.pegasus.pegasustcgapi.model.audit.AuditTargetType;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin activity log (TCG-431). Read-only: rows are written by the actions
 * themselves and there is no endpoint to change or remove one.
 */
@Tag(name = "Audit Log (Admin)", description = "Who did what to which record, newest first (Admin only)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditLogController {

    private final AuditLogService audit;

    public AdminAuditLogController(AuditLogService audit) {
        this.audit = audit;
    }

    @Operation(summary = "Search the audit log",
            description = "Newest first. action and targetType may be repeated to match any of several values.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of audit rows"),
            @ApiResponse(responseCode = "400", description = "Unknown action or target type, or a malformed date"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    })
    @GetMapping
    public ApiResult<PageResponse<AuditLogResponse>> search(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) List<AuditAction> action,
            @RequestParam(required = false) List<AuditTargetType> targetType,
            @RequestParam(required = false) String targetId,
            @Parameter(description = "Inclusive, ISO-8601")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive, ISO-8601")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Case-insensitive substring of the summary")
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "At most 100")
            @RequestParam(defaultValue = "20") int size) {

        AuditLogFilter filter = new AuditLogFilter(actorUserId, action, targetType, targetId, from, to, q);
        return ApiResult.success(audit.search(filter, Paging.of(page, size)));
    }
}
