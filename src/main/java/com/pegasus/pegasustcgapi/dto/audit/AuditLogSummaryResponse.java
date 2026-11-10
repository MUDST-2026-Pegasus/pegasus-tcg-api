package com.pegasus.pegasustcgapi.dto.audit;

import java.util.List;
import java.util.Map;

/**
 * Counts for the activity log's side panel over one time window.
 *
 * @param byAction  rows per action name; the page decides which ones count as important
 * @param topActors the admins with the most rows, most first
 */
public record AuditLogSummaryResponse(
        long total,
        long byAdmins,
        long bySystem,
        Map<String, Long> byAction,
        List<TopActor> topActors) {

    public record TopActor(long userId, String username, String displayName, long count) {
    }
}
