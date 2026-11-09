package com.pegasus.pegasustcgapi.repository.audit;

import static com.pegasus.pegasustcgapi.jooq.tables.AuditLog.AUDIT_LOG;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.audit.AuditLogFilter;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Writes and reads {@code audit_log}. There is deliberately no update or delete:
 * the table is append-only.
 */
@Repository
public class AuditLogRepository {

    /**
     * {@code inet} has no Java type in the generated code, so it goes in and out
     * as text and Postgres does the conversion.
     */
    private static final Field<String> IP_ADDRESS = DSL.field(DSL.name("ip_address"), String.class);
    private static final Field<String> IP_TEXT =
            DSL.field("host({0})", String.class, DSL.field(DSL.name("a", "ip_address")));

    private final DSLContext dsl;

    public AuditLogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(Long actorUserId, String action, String targetType, String targetId,
            String summary, String metadataJson, String ipAddress) {
        return dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.ACTOR_USER_ID, actorUserId)
                .set(AUDIT_LOG.ACTION, action)
                .set(AUDIT_LOG.TARGET_TYPE, targetType)
                .set(AUDIT_LOG.TARGET_ID, targetId)
                .set(AUDIT_LOG.SUMMARY, summary)
                .set(AUDIT_LOG.METADATA, JSONB.valueOf(metadataJson))
                .set(IP_ADDRESS, DSL.field("cast({0} as inet)", String.class, DSL.val(ipAddress, String.class)))
                .returning(AUDIT_LOG.ID)
                .fetchOne(AUDIT_LOG.ID);
    }

    /** Newest first, with the actor joined in so a page is one query. */
    public List<Row> search(AuditLogFilter filter, int limit, int offset) {
        var a = AUDIT_LOG.as("a");
        var u = USER_ACCOUNT.as("u");
        return dsl.select(a.ID, a.OCCURRED_AT, a.ACTOR_USER_ID, u.USERNAME, u.DISPLAY_NAME,
                        a.ACTION, a.TARGET_TYPE, a.TARGET_ID, a.SUMMARY, a.METADATA, IP_TEXT)
                .from(a)
                .leftJoin(u).on(u.ID.eq(a.ACTOR_USER_ID))
                .where(condition(filter))
                .orderBy(a.OCCURRED_AT.desc(), a.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(r -> new Row(
                        r.get(a.ID),
                        r.get(a.OCCURRED_AT),
                        r.get(a.ACTOR_USER_ID),
                        r.get(u.USERNAME),
                        r.get(u.DISPLAY_NAME),
                        r.get(a.ACTION),
                        r.get(a.TARGET_TYPE),
                        r.get(a.TARGET_ID),
                        r.get(a.SUMMARY),
                        r.get(a.METADATA).data(),
                        r.get(IP_TEXT)));
    }

    public long count(AuditLogFilter filter) {
        return dsl.fetchCount(AUDIT_LOG.as("a"), condition(filter));
    }

    private static Condition condition(AuditLogFilter f) {
        var a = AUDIT_LOG.as("a");
        Condition c = DSL.noCondition();
        if (f.actorUserId() != null) {
            c = c.and(a.ACTOR_USER_ID.eq(f.actorUserId()));
        }
        if (!f.actions().isEmpty()) {
            c = c.and(a.ACTION.in(f.actions().stream().map(Enum::name).toList()));
        }
        if (!f.targetTypes().isEmpty()) {
            c = c.and(a.TARGET_TYPE.in(f.targetTypes().stream().map(Enum::name).toList()));
        }
        if (f.targetId() != null && !f.targetId().isBlank()) {
            c = c.and(a.TARGET_ID.eq(f.targetId().trim()));
        }
        if (f.from() != null) {
            c = c.and(a.OCCURRED_AT.ge(f.from()));
        }
        if (f.to() != null) {
            c = c.and(a.OCCURRED_AT.lt(f.to()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            // Wildcards in the input are escaped, so "50%" is searched literally.
            String pattern = "%" + f.q().trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%";
            c = c.and(DSL.condition("{0} ILIKE {1} ESCAPE '\\'", a.SUMMARY, DSL.val(pattern)));
        }
        return c;
    }

    /** One audit row with its actor's names; the names are null when the actor is the system or gone. */
    public record Row(
            long id,
            OffsetDateTime occurredAt,
            Long actorUserId,
            String actorUsername,
            String actorDisplayName,
            String action,
            String targetType,
            String targetId,
            String summary,
            String metadataJson,
            String ipAddress) {
    }
}
