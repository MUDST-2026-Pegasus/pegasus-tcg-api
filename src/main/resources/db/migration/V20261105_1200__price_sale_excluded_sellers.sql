-- Shops a Pegasus campaign leaves out [TCG-675 review, SAL-A2 "ยกเว้นร้าน"]: a
-- campaign over a game, category or set reaches every shop's listings in it
-- except these. A shop's own sale covers only that shop, so it has none.
ALTER TABLE price_sale
    ADD COLUMN excluded_seller_ids bigint[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_price_sale_excluded_sellers CHECK (
        owner_type = 'PLATFORM' OR cardinality(excluded_seller_ids) = 0);

COMMENT ON COLUMN price_sale.excluded_seller_ids IS
    'seller_profile.id of shops a PLATFORM campaign leaves out; always empty for a SELLER sale.';
