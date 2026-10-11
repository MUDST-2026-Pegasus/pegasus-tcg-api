-- Transfers are never approved by software: an admin checks the actual bank account.
UPDATE platform_setting
SET setting_value = 'false'::jsonb,
    description = 'Bank-transfer slip approval is manual; this legacy switch cannot be enabled.'
WHERE setting_key = 'payment.slip_auto_approve';
