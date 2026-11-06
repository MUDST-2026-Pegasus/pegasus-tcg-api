-- Thresholds behind the stock status on the admin weekly report. Evaluated per table row.
INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('report.low_stock_threshold', '500', 'Weekly report: remaining units below this read as low stock.'),
    ('report.fast_moving_percent', '30', 'Weekly report: percent of the week''s stock sold, above which a row reads as fast moving.')
ON CONFLICT (setting_key) DO NOTHING;
