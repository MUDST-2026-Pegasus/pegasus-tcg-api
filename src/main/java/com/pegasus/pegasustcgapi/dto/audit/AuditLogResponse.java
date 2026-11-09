package com.pegasus.pegasustcgapi.dto.audit;

import com.pegasus.pegasustcgapi.repository.audit.AuditLogRepository.Row;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * One audit row as the admin activity page shows it.
 *
 * @param actor null when the system did it, or the account no longer exists
 */
public record AuditLogResponse(
        long id,
        OffsetDateTime occurredAt,
        Actor actor,
        String action,
        String targetType,
        String targetId,
        String summary,
        JsonNode metadata,
        String ipAddress) {

    public record Actor(long userId, String username, String displayName) {
    }

    public static AuditLogResponse from(Row row, JsonNode metadata) {
        Actor actor = row.actorUserId() == null ? null
                : new Actor(row.actorUserId(), row.actorUsername(), row.actorDisplayName());
        return new AuditLogResponse(row.id(), row.occurredAt(), actor, row.action(),
                row.targetType(), row.targetId(), row.summary(), metadata, row.ipAddress());
    }
}
