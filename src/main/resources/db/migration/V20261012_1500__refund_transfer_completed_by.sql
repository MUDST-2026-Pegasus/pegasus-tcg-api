ALTER TABLE refund_transfer_attempt
    ADD COLUMN completed_by bigint REFERENCES user_account (id) ON DELETE RESTRICT;

ALTER TABLE refund_transfer_attempt
    ADD CONSTRAINT ck_refund_transfer_attempt_actor CHECK (
        (status = 'PROCESSING' AND completed_by IS NULL)
        OR (status IN ('PROCESSED', 'FAILED') AND completed_by IS NOT NULL));
