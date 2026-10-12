-- Money invariants that must also hold when requests race.
INSERT INTO wallet (kind)
SELECT 'PLATFORM'
WHERE NOT EXISTS (SELECT 1 FROM wallet WHERE kind = 'PLATFORM');

ALTER TABLE wallet ADD CONSTRAINT ck_wallet_non_negative
    CHECK (balance_available >= 0 AND balance_pending >= 0);

CREATE UNIQUE INDEX ux_ledger_entry_once
    ON ledger_entry (entry_type, direction, reference_type, reference_id)
    WHERE entry_type <> 'ADJUSTMENT';

CREATE UNIQUE INDEX ux_payment_active_per_order
    ON payment (sales_order_id)
    WHERE status IN ('PENDING', 'AWAITING_VERIFICATION', 'SUCCEEDED');

CREATE UNIQUE INDEX ux_payment_slip_key
    ON payment (slip_image_key)
    WHERE slip_image_key IS NOT NULL;

INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('payment.slip_auto_approve', 'false'::jsonb,
     'Automatically approve uploaded payment slips in demo environments only.'),
    ('payment.transfer_account',
     '{"bankName":"TEST BANK","accountName":"PEGASUS TCG","accountNumber":"000-0-00000-0","promptPayId":"0000000000"}'::jsonb,
     'Bank account shown to buyers for bank transfers; replace before use.')
ON CONFLICT (setting_key) DO NOTHING;
