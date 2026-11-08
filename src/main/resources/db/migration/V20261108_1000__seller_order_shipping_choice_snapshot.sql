-- TCG-503: freeze the delivery choice the buyer picked at checkout, so renaming,
-- re-carriering or deleting the shop's option later never rewrites old orders.
ALTER TABLE seller_order
    ADD COLUMN shipping_option_name  varchar(100),
    ADD COLUMN shipping_carrier_code varchar(32),
    ADD COLUMN shipping_est_days_min smallint,
    ADD COLUMN shipping_est_days_max smallint;

-- Best effort for orders placed before this: copy from the option while it still exists.
UPDATE seller_order so
SET shipping_option_name  = o.name,
    shipping_carrier_code = o.carrier_code,
    shipping_est_days_min = o.est_days_min,
    shipping_est_days_max = o.est_days_max
FROM seller_shipping_option o
WHERE so.shipping_option_id = o.id
  AND so.shipping_option_name IS NULL;
