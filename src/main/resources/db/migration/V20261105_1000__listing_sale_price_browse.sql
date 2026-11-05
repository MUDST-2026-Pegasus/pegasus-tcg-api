-- Buyer pages read listing_sale_price by sale and by end [TCG-679]: a sale's page
-- (/sales/{slug}) and the home flash sale filter on sale_id and drop rows past
-- ends_at; "ending soon" sorts on ends_at. The new index covers what the old
-- sale_id one did.
CREATE INDEX ix_listing_sale_price_sale_ends ON listing_sale_price (sale_id, ends_at);
DROP INDEX ix_listing_sale_price_sale;
CREATE INDEX ix_listing_sale_price_ends ON listing_sale_price (ends_at);
