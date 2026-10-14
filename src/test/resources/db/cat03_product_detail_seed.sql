-- CAT-03 local demo data for Pikachu ex. Run manually against a development DB only.
-- Idempotent: every demo row has a dedicated username or seed marker.
BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM catalog_product WHERE slug = 'pikachu-ex-025-187') THEN
        RAISE EXCEPTION 'Pikachu ex product is missing; load the catalogue seed first';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM catalog_variant WHERE sku = 'POKEMON-SV8A-025-187-EN-NORMAL')
       OR NOT EXISTS (SELECT 1 FROM catalog_variant WHERE sku = 'POKEMON-SV8A-025-187-JP-FOIL') THEN
        RAISE EXCEPTION 'Pikachu ex variants are missing';
    END IF;
END;
$$;

INSERT INTO user_account (email, password_hash, username, display_name, bio, status)
SELECT v.email,
       '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU',
       v.username, v.display_name, 'CAT-03 local demo account', 'ACTIVE'
FROM (VALUES
    ('cat03-shop-a@example.invalid', 'demo_cat03_shop_a', 'Demo Card Shop'),
    ('cat03-shop-b@example.invalid', 'demo_cat03_shop_b', 'Demo TCG Corner'),
    ('cat03-shop-c@example.invalid', 'demo_cat03_shop_c', 'Demo Mint Vault'),
    ('cat03-collector-a@example.invalid', 'demo_cat03_collector_a', 'Demo Collector A'),
    ('cat03-collector-b@example.invalid', 'demo_cat03_collector_b', 'Demo Collector B'),
    ('cat03-collector-c@example.invalid', 'demo_cat03_collector_c', 'Demo Collector C')
) AS v(email, username, display_name)
WHERE NOT EXISTS (SELECT 1 FROM user_account u WHERE u.username = v.username OR u.email = v.email);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM user_account u
JOIN app_role r ON r.code = 'BUYER'
WHERE u.username LIKE 'demo_cat03_%' AND u.bio = 'CAT-03 local demo account'
  AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM user_account u
JOIN app_role r ON r.code = 'SELLER'
WHERE u.username IN ('demo_cat03_shop_a', 'demo_cat03_shop_b', 'demo_cat03_shop_c')
  AND u.bio = 'CAT-03 local demo account'
  AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO seller_profile (user_id, status, verified_at)
SELECT u.id, 'VERIFIED', now()
FROM user_account u
WHERE u.username IN ('demo_cat03_shop_a', 'demo_cat03_shop_b', 'demo_cat03_shop_c')
  AND u.bio = 'CAT-03 local demo account'
  AND NOT EXISTS (SELECT 1 FROM seller_profile sp WHERE sp.user_id = u.id);

INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code,
                     price, currency, pricing_mode, status, lot_label, published_at)
SELECT sp.id, cv.id, v.condition_code, v.price, 'THB', 'MANUAL', 'ACTIVE', 'seed:cat03', now()
FROM (VALUES
    ('demo_cat03_shop_a', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', 890.00),
    ('demo_cat03_shop_b', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', 950.00),
    ('demo_cat03_shop_c', 'POKEMON-SV8A-025-187-EN-NORMAL', 'LP', 740.00),
    ('demo_cat03_shop_c', 'POKEMON-SV8A-025-187-JP-FOIL', 'NM', 1290.00)
) AS v(username, sku, condition_code, price)
JOIN user_account u ON u.username = v.username AND u.bio = 'CAT-03 local demo account'
JOIN seller_profile sp ON sp.user_id = u.id
JOIN catalog_variant cv ON cv.sku = v.sku
WHERE NOT EXISTS (
    SELECT 1 FROM listing l
    WHERE l.seller_profile_id = sp.id AND l.catalog_variant_id = cv.id
      AND l.condition_code = v.condition_code AND l.lot_label = 'seed:cat03'
);

-- A listing is visible only after physical units make quantity_available positive.
-- The listing_unit trigger maintains the quantity columns on listing.
INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code,
                          listing_id, status, unit_note)
SELECT l.seller_profile_id, l.catalog_variant_id, l.condition_code,
       l.id, 'LISTED', 'seed:cat03:' || gs.n
FROM (VALUES
    ('demo_cat03_shop_a', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', 1),
    ('demo_cat03_shop_b', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', 2),
    ('demo_cat03_shop_c', 'POKEMON-SV8A-025-187-EN-NORMAL', 'LP', 1),
    ('demo_cat03_shop_c', 'POKEMON-SV8A-025-187-JP-FOIL', 'NM', 1)
) AS v(username, sku, condition_code, units)
JOIN user_account u ON u.username = v.username AND u.bio = 'CAT-03 local demo account'
JOIN seller_profile sp ON sp.user_id = u.id
JOIN catalog_variant cv ON cv.sku = v.sku
JOIN listing l ON l.seller_profile_id = sp.id AND l.catalog_variant_id = cv.id
              AND l.condition_code = v.condition_code AND l.lot_label = 'seed:cat03'
CROSS JOIN LATERAL generate_series(1, v.units) AS gs(n)
WHERE NOT EXISTS (SELECT 1 FROM listing_unit lu
                  WHERE lu.listing_id = l.id AND lu.unit_note = 'seed:cat03:' || gs.n);

INSERT INTO collection_item (user_id, catalog_variant_id, condition_code,
                             quantity, source, is_public, personal_note)
SELECT u.id, cv.id, v.condition_code, v.quantity, 'MANUAL', true, 'seed:cat03'
FROM (VALUES
    ('demo_cat03_collector_a', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', 2),
    ('demo_cat03_collector_b', 'POKEMON-SV8A-025-187-JP-FOIL', 'NM', 1),
    ('demo_cat03_collector_c', 'POKEMON-SV8A-025-187-EN-NORMAL', 'LP', 1)
) AS v(username, sku, condition_code, quantity)
JOIN user_account u ON u.username = v.username AND u.bio = 'CAT-03 local demo account'
JOIN catalog_variant cv ON cv.sku = v.sku
WHERE NOT EXISTS (SELECT 1 FROM collection_item ci
                  WHERE ci.user_id = u.id AND ci.catalog_variant_id = cv.id
                    AND ci.personal_note = 'seed:cat03' AND ci.deleted_at IS NULL);

COMMIT;
