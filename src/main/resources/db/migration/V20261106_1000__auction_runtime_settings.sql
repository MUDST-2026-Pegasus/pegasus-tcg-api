-- Keep the existing payment deadline when upgrading from the earlier key.
INSERT INTO platform_setting (setting_key, setting_value, description)
SELECT 'auction.payment_hours', setting_value, 'Hours allowed to pay after an auction win.'
FROM platform_setting WHERE setting_key = 'auction.payment_window_hours'
ON CONFLICT (setting_key) DO NOTHING;

DELETE FROM platform_setting WHERE setting_key = 'auction.payment_window_hours';

INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('auction.extension_seconds', '120', 'Seconds remaining that trigger a bid extension, and the new time remaining.'),
    ('auction.min_duration_minutes', '60', 'Minimum auction duration in minutes.'),
    ('auction.max_duration_days', '30', 'Maximum auction duration in days.')
ON CONFLICT (setting_key) DO NOTHING;
