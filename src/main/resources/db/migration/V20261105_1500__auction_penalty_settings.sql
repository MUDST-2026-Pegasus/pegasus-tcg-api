INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('auction.payment_window_hours', '24', 'Hours allowed to pay after an auction win.'),
    ('auction.strike_limit', '3', 'Unpaid auction wins before a bidding ban.'),
    ('auction.ban_days', '30', 'Days a bidding ban lasts after the strike limit is reached.')
ON CONFLICT (setting_key) DO NOTHING;
