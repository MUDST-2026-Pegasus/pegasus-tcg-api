ALTER TABLE refund DROP CONSTRAINT ck_refund_status;
ALTER TABLE refund ADD CONSTRAINT ck_refund_status CHECK (status IN
    ('PENDING', 'APPROVED', 'PROCESSING', 'PROCESSED', 'REJECTED'));

CREATE TABLE refund_transfer_attempt (
    id bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    refund_id bigint NOT NULL REFERENCES refund (id) ON DELETE RESTRICT,
    bank_name varchar(100) NOT NULL,
    account_name varchar(100) NOT NULL,
    account_number varchar(34) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PROCESSING',
    started_by bigint NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    transfer_reference varchar(64),
    failure_reason varchar(255),
    CONSTRAINT ck_refund_transfer_attempt_status CHECK (status IN ('PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_refund_transfer_attempt_result CHECK (
        (status = 'PROCESSING' AND completed_at IS NULL AND transfer_reference IS NULL AND failure_reason IS NULL)
        OR (status = 'PROCESSED' AND completed_at IS NOT NULL AND transfer_reference IS NOT NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND transfer_reference IS NULL AND failure_reason IS NOT NULL))
);

CREATE UNIQUE INDEX ux_refund_transfer_attempt_active
    ON refund_transfer_attempt (refund_id) WHERE status = 'PROCESSING';
CREATE INDEX ix_refund_transfer_attempt_refund
    ON refund_transfer_attempt (refund_id, started_at DESC);
