ALTER TABLE refund
    ADD COLUMN bank_name varchar(100),
    ADD COLUMN account_name varchar(100),
    ADD COLUMN account_number varchar(34),
    ADD COLUMN transfer_reference varchar(64);

COMMENT ON COLUMN refund.account_number IS
    'Buyer-provided destination captured for this refund; do not update after processing.';
