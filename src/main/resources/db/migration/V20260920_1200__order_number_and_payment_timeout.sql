-- Order module follow-up: a readable order number, and a deadline for unpaid orders.
--
-- Both belong to the order flow, and neither changes an existing column.

-- sales_order.order_number is PGS-YYYYMMDD-NNNNNN. The date is for the human
-- reading it; the counter is what makes it unique, and a sequence is the only
-- way to get one without two concurrent checkouts picking the same number.
-- It is never reset: uq_sales_order_number is on the whole column, not per day.
CREATE SEQUENCE order_number_seq AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

COMMENT ON SEQUENCE order_number_seq IS
    'Counter behind sales_order.order_number. nextval() is outside transaction control on purpose: a rolled-back checkout burns a number rather than handing it to somebody else.';


-- Without a deadline an unpaid order holds its cards RESERVED for good, and the
-- seller cannot delist them (LISTING_HAS_RESERVATIONS). The sweep that cancels
-- them reads this.
INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('order.payment_timeout_minutes', '60',
     'Minutes an unpaid order may hold its reserved cards before it is cancelled.')
ON CONFLICT (setting_key) DO NOTHING;
