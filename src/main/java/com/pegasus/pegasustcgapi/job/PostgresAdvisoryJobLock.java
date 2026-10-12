package com.pegasus.pegasustcgapi.job;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link JobLock} on a Postgres transaction-scoped advisory lock.
 *
 * <p>Transaction-scoped rather than session-scoped: a session lock is held by the
 * connection that took it, and with a pool the unlock can easily run on a different
 * one and leave the lock held forever. {@code pg_try_advisory_xact_lock} releases
 * itself when the surrounding transaction ends, however the job ends.
 */
@Component
public class PostgresAdvisoryJobLock implements JobLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryJobLock.class);

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public PostgresAdvisoryJobLock(DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean runExclusively(String name, Runnable task) {
        long key = keyFor(name);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Boolean acquired = dsl
                    .select(DSL.field("pg_try_advisory_xact_lock({0})", Boolean.class, DSL.val(key)))
                    .fetchOne(0, Boolean.class);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("Job {} is already running on another instance; skipping this tick", name);
                return false;
            }
            task.run();
            return true;
        }));
    }

    /** Advisory locks are keyed by number, so the job's name becomes one. */
    private static long keyFor(String name) {
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
