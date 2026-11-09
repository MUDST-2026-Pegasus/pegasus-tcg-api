-- TCG-431: the admin audit trail, brought back from the deferred list in
-- V20260912_0005__platform_ops.sql. That file says it belongs in "V10-V19";
-- migrations are named by timestamp now, so it lands here as its own file.
--
-- One row per admin action (or per scheduler run that changed something),
-- written in the same transaction as the action, so a rolled-back action
-- leaves no row behind.

CREATE TABLE audit_log (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   timestamptz  NOT NULL DEFAULT now(),
    -- NULL means the system did it (a scheduled job), or the account is gone.
    actor_user_id bigint       REFERENCES user_account (id) ON DELETE SET NULL,
    action        varchar(64)  NOT NULL,
    target_type   varchar(32)  NOT NULL,
    target_id     varchar(64)  NOT NULL,
    summary       varchar(255) NOT NULL,
    -- Before/after of what changed, by reference. Never names, bank account
    -- numbers, passwords, tokens or document images.
    metadata      jsonb        NOT NULL DEFAULT '{}',
    ip_address    inet
);

CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX ix_audit_log_target ON audit_log (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_user_id, occurred_at DESC);

COMMENT ON TABLE audit_log IS
    'Append-only record of admin and system actions (TCG-431). The API never updates or deletes rows. '
    'metadata must not hold PII: store ids, not names, bank details or credentials.';
