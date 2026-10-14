-- Local development data for every active catalog product that has an active variant.
-- Run after the catalog migrations/seeds. Re-run after adding products; existing
-- real or demo market/collector data is left alone. Never run in production.
-- PowerShell: Get-Content -Raw src/test/resources/db/catalog_market_collection_seed.sql |
--             docker exec -i pegasus-tcg-postgres psql -v ON_ERROR_STOP=1 -U postgres -d pegasus_tcg
BEGIN;

INSERT INTO user_account (email, password_hash, username, display_name, bio, status)
SELECT v.email,
       '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU',
       v.username, v.display_name, 'Local catalog demo account', 'ACTIVE'
FROM (VALUES
    ('catalog-shop-a@example.invalid', 'demo_catalog_shop_a', 'Demo Catalog Shop A'),
    ('catalog-shop-b@example.invalid', 'demo_catalog_shop_b', 'Demo Catalog Shop B'),
    ('catalog-collector-a@example.invalid', 'demo_catalog_collector_a', 'Demo Collector A'),
    ('catalog-collector-b@example.invalid', 'demo_catalog_collector_b', 'Demo Collector B')
) AS v(email, username, display_name)
WHERE NOT EXISTS (SELECT 1 FROM user_account u WHERE u.username = v.username OR u.email = v.email);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM user_account u
CROSS JOIN app_role r
WHERE u.username IN ('demo_catalog_shop_a', 'demo_catalog_shop_b',
                     'demo_catalog_collector_a', 'demo_catalog_collector_b')
  AND u.bio = 'Local catalog demo account'
  AND (r.code = 'BUYER' OR (r.code = 'SELLER' AND u.username LIKE 'demo_catalog_shop_%'))
  AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO seller_profile (user_id, status, verified_at)
SELECT u.id, 'VERIFIED', now()
FROM user_account u
WHERE u.username IN ('demo_catalog_shop_a', 'demo_catalog_shop_b')
  AND u.bio = 'Local catalog demo account'
  AND NOT EXISTS (SELECT 1 FROM seller_profile sp WHERE sp.user_id = u.id);

-- A listing groups a price, while listing_unit represents the physical stock.
-- Choose one active variant per product. Products with an existing active, stocked
-- listing (including real seller data) are not given synthetic offers.
WITH target AS (
    SELECT p.id AS product_id, p.product_type, v.id AS variant_id
    FROM catalog_product p
    JOIN LATERAL (
        SELECT cv.id FROM catalog_variant cv
        WHERE cv.catalog_product_id = p.id AND cv.is_active
        ORDER BY cv.id LIMIT 1
    ) v ON true
    WHERE p.is_active
      AND NOT EXISTS (
          SELECT 1 FROM listing l
          JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
          WHERE cv.catalog_product_id = p.id
            AND l.status = 'ACTIVE' AND l.deleted_at IS NULL
            AND l.quantity_available > 0
      )
)
INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code,
                     price, currency, pricing_mode, status, lot_label, public_note, published_at)
SELECT sp.id, t.variant_id,
       CASE WHEN t.product_type = 'SINGLE_CARD' THEN 'NM' ELSE 'SEALED' END,
       CASE WHEN t.product_type = 'SINGLE_CARD' THEN 500 + t.product_id * 20 + s.price_offset
            ELSE 1800 + t.product_id * 100 + s.price_offset END,
       'THB', 'MANUAL', 'ACTIVE', 'seed:catalog-market-v1',
       'Local development sample listing', now()
FROM target t
CROSS JOIN (VALUES ('demo_catalog_shop_a', 0), ('demo_catalog_shop_b', 150)) AS s(username, price_offset)
JOIN user_account u ON u.username = s.username AND u.bio = 'Local catalog demo account'
JOIN seller_profile sp ON sp.user_id = u.id
WHERE NOT EXISTS (
    SELECT 1 FROM listing l
    WHERE l.seller_profile_id = sp.id AND l.catalog_variant_id = t.variant_id
      AND l.lot_label = 'seed:catalog-market-v1'
);

-- The database trigger calculates listing.quantity_available from these units.
INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code,
                          listing_id, status, unit_note)
SELECT l.seller_profile_id, l.catalog_variant_id, l.condition_code,
       l.id, 'LISTED', 'seed:catalog-market-v1'
FROM listing l
JOIN seller_profile sp ON sp.id = l.seller_profile_id
JOIN user_account u ON u.id = sp.user_id AND u.bio = 'Local catalog demo account'
WHERE l.lot_label = 'seed:catalog-market-v1'
  AND l.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM listing_unit lu
      WHERE lu.listing_id = l.id AND lu.unit_note = 'seed:catalog-market-v1'
        AND lu.deleted_at IS NULL
  );

-- Only public collections appear in the product's collector endpoint.
-- Products with any existing public collector are left alone.
WITH target AS (
    SELECT p.id AS product_id, p.product_type, v.id AS variant_id
    FROM catalog_product p
    JOIN LATERAL (
        SELECT cv.id FROM catalog_variant cv
        WHERE cv.catalog_product_id = p.id AND cv.is_active
        ORDER BY cv.id LIMIT 1
    ) v ON true
    WHERE p.is_active
      AND NOT EXISTS (
          SELECT 1 FROM collection_item ci
          JOIN catalog_variant cv ON cv.id = ci.catalog_variant_id
          WHERE cv.catalog_product_id = p.id
            AND ci.is_public AND ci.deleted_at IS NULL
      )
)
INSERT INTO collection_item (user_id, catalog_variant_id, condition_code,
                             quantity, source, is_public, personal_note)
SELECT u.id, t.variant_id,
       CASE WHEN t.product_type = 'SINGLE_CARD' THEN 'NM' ELSE 'SEALED' END,
       c.quantity, 'MANUAL', true, 'seed:catalog-market-v1'
FROM target t
CROSS JOIN (VALUES ('demo_catalog_collector_a', 1),
                   ('demo_catalog_collector_b', 2)) AS c(username, quantity)
JOIN user_account u ON u.username = c.username AND u.bio = 'Local catalog demo account'
WHERE NOT EXISTS (
    SELECT 1 FROM collection_item ci
    WHERE ci.user_id = u.id AND ci.catalog_variant_id = t.variant_id
      AND ci.personal_note = 'seed:catalog-market-v1' AND ci.deleted_at IS NULL
);


-- ORD-10: demo checkout always needs an active tariff and a usable default address.
INSERT INTO seller_shipping_option (seller_profile_id, name, carrier_code, base_fee, per_item_fee,
                                    est_days_min, est_days_max, is_active, display_order)
SELECT sp.id, 'Standard delivery', 'THAILAND_POST', 50.00, 5.00, 1, 3, true, 0
FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id
WHERE (u.bio = 'Local catalog demo account' AND u.username LIKE 'demo_catalog_%') AND sp.status = 'VERIFIED'
  AND NOT EXISTS (SELECT 1 FROM seller_shipping_option o WHERE o.seller_profile_id = sp.id AND o.is_active);

INSERT INTO address (user_id, label, recipient_name, phone, line1, subdistrict, district, province,
                     postal_code, country_code, is_default_shipping, is_default_billing)
SELECT u.id, 'Home', u.display_name, '0812345678', '123 Pegasus Lane', 'Khlong Toei', 'Khlong Toei',
       'Bangkok', '10110', 'TH', true, true
FROM user_account u
WHERE (u.bio = 'Local catalog demo account' AND u.username LIKE 'demo_catalog_%')
  AND NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = u.id AND a.deleted_at IS NULL);

-- Preserve existing demo address details, selecting a default if none was set.
UPDATE address SET is_default_shipping = true
WHERE id IN (
    SELECT DISTINCT ON (a.user_id) a.id FROM address a JOIN user_account u ON u.id = a.user_id
    WHERE (u.bio = 'Local catalog demo account' AND u.username LIKE 'demo_catalog_%') AND a.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM address d WHERE d.user_id = a.user_id
                      AND d.deleted_at IS NULL AND d.is_default_shipping)
    ORDER BY a.user_id, a.id
);
COMMIT;
