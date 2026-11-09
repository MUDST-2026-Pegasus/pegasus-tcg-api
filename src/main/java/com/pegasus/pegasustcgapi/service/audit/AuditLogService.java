package com.pegasus.pegasustcgapi.service.audit;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.audit.AuditLogResponse;
import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.model.audit.AuditLogFilter;
import com.pegasus.pegasustcgapi.repository.audit.AuditLogRepository;
import com.pegasus.pegasustcgapi.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.RecordComponent;
import com.pegasus.pegasustcgapi.dto.audit.AuditLogSummaryResponse;
import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * The admin audit trail (TCG-431): who did what to which thing, and when.
 *
 * <p>{@link #record} joins the caller's transaction and refuses to run without
 * one, so a row exists exactly when the action it describes was committed. Jobs
 * that work outside a transaction write their one summary row with
 * {@link #recordOnItsOwn} after the run.
 *
 * <p>Metadata holds ids and the values that changed — never names, bank account
 * numbers, passwords, tokens or document keys. Call sites are responsible for
 * that; nothing here can tell a name from any other string.
 */
@Service
public class AuditLogService {

    static final int SUMMARY_MAX = 255;
    static final int TARGET_ID_MAX = 64;

    private final AuditLogRepository logs;
    private final ObjectMapper json;

    public AuditLogService(AuditLogRepository logs, ObjectMapper json) {
        this.logs = logs;
        this.json = json;
    }

    /**
     * Writes one row inside the caller's transaction.
     *
     * @param actorUserId the admin, or {@code null} for the system
     * @param targetId    the target's id; its type comes from the action
     * @param metadata    ids and before/after values, no PII; may be empty
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorUserId, AuditAction action, Object targetId,
            String summary, Map<String, ?> metadata) {
        write(actorUserId, action, targetId, summary, metadata);
    }

    /** As {@link #record}, for a caller that has no transaction of its own (a job's summary row). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOnItsOwn(Long actorUserId, AuditAction action, Object targetId,
            String summary, Map<String, ?> metadata) {
        write(actorUserId, action, targetId, summary, metadata);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(AuditLogFilter filter, Paging paging) {
        var items = logs.search(filter, paging.size(), paging.offset()).stream()
                .map(row -> AuditLogResponse.from(row, json.readTree(row.metadataJson())))
                .toList();
        return PageResponse.of(items, paging.page(), paging.size(), logs.count(filter));
    }

    /** Number of top admins the summary names. */
    static final int TOP_ACTORS = 3;

    /** Counts over {@code [from, to)}; either end may be open. */
    @Transactional(readOnly = true)
    public AuditLogSummaryResponse summary(OffsetDateTime from, OffsetDateTime to) {
        long total = 0;
        long bySystem = 0;
        Map<String, Long> byAction = new TreeMap<>();
        for (AuditLogRepository.ActionCount row : logs.countByAction(from, to)) {
            total += row.count();
            if (row.bySystem()) {
                bySystem += row.count();
            }
            byAction.merge(row.action(), (long) row.count(), Long::sum);
        }
        List<AuditLogSummaryResponse.TopActor> top = logs.topActors(from, to, TOP_ACTORS).stream()
                .map(a -> new AuditLogSummaryResponse.TopActor(
                        a.userId(), a.username(), a.displayName(), a.count()))
                .toList();
        return new AuditLogSummaryResponse(total, total - bySystem, bySystem, byAction, top);
    }

    /**
     * Builds a metadata map that, unlike {@link Map#of}, keeps null values and
     * the order the keys were given in. Takes alternating keys and values.
     */
    public static Map<String, Object> meta(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("meta() takes key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** A before/after pair, for metadata of an update. */
    public static Map<String, Object> change(Object before, Object after) {
        return meta("before", before, "after", after);
    }

    /**
     * The components of two versions of the same record that differ, each as a
     * before/after pair. Timestamps the database maintains are left out. Only use
     * it on records that hold no PII (catalog rows, rules), since every changed
     * component is copied into the log.
     */
    public static Map<String, Object> diff(Record before, Record after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        if (before == null || after == null || before.getClass() != after.getClass()) {
            return changed;
        }
        for (RecordComponent component : before.getClass().getRecordComponents()) {
            String name = component.getName();
            if (IGNORED_IN_DIFF.contains(name)) {
                continue;
            }
            try {
                Object was = component.getAccessor().invoke(before);
                Object now = component.getAccessor().invoke(after);
                if (!Objects.equals(was, now)) {
                    changed.put(name, change(was, now));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + name + " for the audit diff", e);
            }
        }
        return changed;
    }

    private static final Set<String> IGNORED_IN_DIFF = Set.of("createdAt", "updatedAt");

    private void write(Long actorUserId, AuditAction action, Object targetId,
            String summary, Map<String, ?> metadata) {
        logs.insert(
                actorUserId,
                action.name(),
                action.targetType().name(),
                clip(String.valueOf(targetId), TARGET_ID_MAX),
                clip(summary, SUMMARY_MAX),
                json.writeValueAsString(metadata == null ? Map.of() : metadata),
                currentClientAddress());
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /**
     * The caller's address when this runs inside a web request, else null. Only
     * a literal IP is returned: anything else would fail the cast to
     * {@code inet} and take the admin's action down with it.
     */
    private static String currentClientAddress() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String address = ClientAddress.of(request);
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(address).getHostAddress();
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
    }
}
