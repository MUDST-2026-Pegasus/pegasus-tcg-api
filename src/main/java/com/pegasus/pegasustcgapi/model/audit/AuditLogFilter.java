package com.pegasus.pegasustcgapi.model.audit;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What {@code GET /admin/audit-logs} narrows by. Every field is optional; an
 * empty list means "any".
 *
 * @param from inclusive
 * @param to   exclusive
 * @param q    a substring of the summary, matched case-insensitively
 */
public record AuditLogFilter(
        Long actorUserId,
        List<AuditAction> actions,
        List<AuditTargetType> targetTypes,
        String targetId,
        OffsetDateTime from,
        OffsetDateTime to,
        String q) {

    public AuditLogFilter {
        actions = actions == null ? List.of() : List.copyOf(actions);
        targetTypes = targetTypes == null ? List.of() : List.copyOf(targetTypes);
    }
}
