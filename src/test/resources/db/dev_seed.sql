-- Sample development data for a local machine. NOT a migration, on purpose:
-- this seeds a Pokemon game catalogue with cards, test accounts (buyer, seller & admin),
-- active listings, and inventory units for local module development and manual testing.
--
-- All seeded test accounts share the password: Password123!
--
-- Run it against a migrated database:
--
--   docker exec -i pegasus-tcg-postgres psql -U postgres -d pegasus_tcg \
--       -v ON_ERROR_STOP=1 < src/test/resources/db/dev_seed.sql
--
-- Safe to run multiple times: every insert skips what is already there using natural
-- business keys and WHERE NOT EXISTS, so it tops up a database rather than duplicating it.

\set ON_ERROR_STOP on
BEGIN;

-- ---------- the game ----------

INSERT INTO game (code, name, name_local, slug, display_order)
SELECT 'POKEMON', 'Pokemon TCG', 'โปเกมอนการ์ด', 'pokemon-tcg', 1
 WHERE NOT EXISTS (SELECT 1 FROM game WHERE code = 'POKEMON');

-- What this game's cards are described by. The browse sidebar is built from
-- these, and catalog_product.attributes is validated against them.
INSERT INTO game_attribute (game_id, attr_key, label, data_type, options, is_filterable, display_order)
SELECT g.id, v.attr_key, v.label, v.data_type, v.options, true, v.display_order
  FROM game g,
       (VALUES
        ('hp',        'HP',          'NUMBER', NULL::jsonb, 1::smallint),
        ('card_type', 'Energy type', 'ENUM',
         '["Lightning","Fire","Water","Grass","Psychic"]'::jsonb, 2::smallint),
        ('stage',     'Stage',       'ENUM',
         '["Basic","Stage 1","Stage 2"]'::jsonb, 3::smallint)
       ) AS v(attr_key, label, data_type, options, display_order)
 WHERE g.code = 'POKEMON'
   AND NOT EXISTS (
        SELECT 1 FROM game_attribute a
         WHERE a.game_id = g.id AND a.attr_key = v.attr_key);

-- ---------- the release ----------

INSERT INTO card_set (game_id, code, name, name_local, release_date, total_cards)
SELECT g.id, 'SV8A', 'Terastal Festival', 'เทศกาลเทระสตัล', DATE '2024-10-18', 187
  FROM game g
 WHERE g.code = 'POKEMON'
   AND NOT EXISTS (SELECT 1 FROM card_set s WHERE s.game_id = g.id AND s.code = 'SV8A');

-- ---------- the cards ----------
--
-- Four entries covering what the catalogue has to handle: a card with two
-- printings, cards with different attribute values, and sealed product that
-- belongs to no card number and carries no attributes at all.

INSERT INTO catalog_product (
        game_id, category_id, card_set_id, product_type, name, name_local, slug,
        card_number, rarity_code, attributes)
SELECT g.id, c.id, s.id, v.product_type, v.name, v.name_local, v.slug,
       v.card_number, v.rarity_code, v.attributes
  FROM game g
  JOIN card_set s ON s.game_id = g.id AND s.code = 'SV8A'
  CROSS JOIN LATERAL (VALUES
        ('SINGLES', 'SINGLE_CARD', 'Pikachu ex',   'พิคาชู ex',  'pikachu-ex-025-187',
         '025/187', 'RR',  '{"hp":200,"card_type":"Lightning","stage":"Basic"}'::jsonb),
        ('SINGLES', 'SINGLE_CARD', 'Charizard ex', 'ลิซาร์ดอน ex', 'charizard-ex-006-165',
         '006/165', 'SAR', '{"hp":330,"card_type":"Fire","stage":"Stage 2"}'::jsonb),
        ('SINGLES', 'SINGLE_CARD', 'Mew ex',       'มิว ex',     'mew-ex-151-165',
         '151/165', 'SR',  '{"hp":180,"card_type":"Psychic","stage":"Basic"}'::jsonb),
        ('SEALED',  'BOOSTER_BOX', 'Terastal Festival Booster Box', NULL,
         'terastal-festival-booster-box', NULL, NULL, '{}'::jsonb)
       ) AS v(category_code, product_type, name, name_local, slug,
              card_number, rarity_code, attributes)
  JOIN catalog_category c ON c.code = v.category_code AND c.game_id IS NULL
 WHERE g.code = 'POKEMON'
   AND NOT EXISTS (SELECT 1 FROM catalog_product p WHERE p.slug = v.slug);

-- ---------- the printings ----------
--
-- Separate rows because they are separate markets, which is the whole reason
-- listings point at a variant rather than at a product.

INSERT INTO catalog_variant (catalog_product_id, sku, language_code, finish, edition)
SELECT p.id, v.sku, v.language_code, v.finish, v.edition
  FROM catalog_product p
  CROSS JOIN LATERAL (VALUES
        ('pikachu-ex-025-187',   'POKEMON-SV8A-025-187-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('pikachu-ex-025-187',   'POKEMON-SV8A-025-187-JP-FOIL',   'JP', 'FOIL',   'UNLIMITED'),
        ('charizard-ex-006-165', 'POKEMON-SV8A-006-165-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('charizard-ex-006-165', 'POKEMON-SV8A-006-165-EN-FOIL',   'EN', 'FOIL',   'UNLIMITED'),
        ('mew-ex-151-165',       'POKEMON-SV8A-151-165-JP-NORMAL', 'JP', 'NORMAL', 'UNLIMITED'),
        ('terastal-festival-booster-box',
         'POKEMON-SV8A-BOX-JP', 'JP', 'NOT_APPLICABLE', 'NOT_APPLICABLE')
       ) AS v(slug, sku, language_code, finish, edition)
 WHERE p.slug = v.slug
   AND NOT EXISTS (SELECT 1 FROM catalog_variant cv WHERE cv.sku = v.sku);

-- ---------- users and roles ----------
--
-- Test buyer, test seller, and admin with their respective app roles.
-- Password for all test users: Password123!
-- Argon2id hash parameters: m=16384, t=2, p=1

INSERT INTO user_account (email, password_hash, username, display_name, status)
SELECT 'admin@example.com', '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU', 'admin_user', 'Platform Admin', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE email = 'admin@example.com' OR username = 'admin_user');

INSERT INTO user_account (email, password_hash, username, display_name, status)
SELECT 'buyer@example.com', '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU', 'buyer_user', 'Test Buyer', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE email = 'buyer@example.com' OR username = 'buyer_user');

INSERT INTO user_account (email, password_hash, username, display_name, status)
SELECT 'seller@example.com', '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU', 'seller_user', 'Test Seller', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE email = 'seller@example.com' OR username = 'seller_user');

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM user_account u, app_role r
 WHERE u.username = 'admin_user' AND r.code = 'ADMIN'
   AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM user_account u, app_role r
 WHERE u.username = 'buyer_user' AND r.code = 'BUYER'
   AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM user_account u, app_role r
 WHERE u.username = 'seller_user' AND r.code = 'SELLER'
   AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

-- ---------- seller profile and shipping address ----------

INSERT INTO seller_profile (user_id, status, verified_at, handling_days, vacation_mode, auto_accept_orders)
SELECT u.id, 'VERIFIED', now(), 2, false, true
  FROM user_account u
 WHERE u.username = 'seller_user'
   AND NOT EXISTS (SELECT 1 FROM seller_profile sp WHERE sp.user_id = u.id);

INSERT INTO address (user_id, label, recipient_name, phone, line1, subdistrict, district, province, postal_code, country_code, is_default_shipping, is_default_billing)
SELECT u.id, 'Home', 'Test Buyer', '+66812345678', '123 Pegasus Lane', 'Khlong Toei', 'Khlong Toei', 'Bangkok', '10110', 'TH', true, true
  FROM user_account u
 WHERE u.username = 'buyer_user'
   AND NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = u.id AND a.line1 = '123 Pegasus Lane');

-- ---------- listings ----------
--
-- Two active listings for the seller covering the Normal and Foil variants above.

INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, price, currency, pricing_mode, status, published_at)
SELECT sp.id, cv.id, 'NM', 150.00, 'THB', 'MANUAL', 'ACTIVE', now()
  FROM seller_profile sp
  JOIN user_account u ON u.id = sp.user_id AND u.username = 'seller_user'
  JOIN catalog_variant cv ON cv.sku = 'POKEMON-SV8A-006-165-EN-NORMAL'
 WHERE NOT EXISTS (
     SELECT 1 FROM listing l
      WHERE l.seller_profile_id = sp.id
        AND l.catalog_variant_id = cv.id
        AND l.condition_code = 'NM'
        AND l.status = 'ACTIVE'
 );

INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, price, currency, pricing_mode, status, published_at)
SELECT sp.id, cv.id, 'NM', 350.00, 'THB', 'MANUAL', 'ACTIVE', now()
  FROM seller_profile sp
  JOIN user_account u ON u.id = sp.user_id AND u.username = 'seller_user'
  JOIN catalog_variant cv ON cv.sku = 'POKEMON-SV8A-006-165-EN-FOIL'
 WHERE NOT EXISTS (
     SELECT 1 FROM listing l
      WHERE l.seller_profile_id = sp.id
        AND l.catalog_variant_id = cv.id
        AND l.condition_code = 'NM'
        AND l.status = 'ACTIVE'
 );

-- ---------- listing units (inventory) ----------
--
-- Physical units linked to the listings. The database trigger updates quantity_available automatically.

INSERT INTO listing_unit (public_uid, seller_profile_id, catalog_variant_id, condition_code, listing_id, status, acquisition_cost, acquired_at)
SELECT v.public_uid::uuid, sp.id, cv.id, 'NM', l.id, 'LISTED', 75.00, now()
  FROM seller_profile sp
  JOIN user_account u ON u.id = sp.user_id AND u.username = 'seller_user'
  JOIN catalog_variant cv ON cv.sku = 'POKEMON-SV8A-006-165-EN-NORMAL'
  JOIN listing l ON l.seller_profile_id = sp.id AND l.catalog_variant_id = cv.id AND l.status = 'ACTIVE'
  CROSS JOIN (VALUES
      ('a0000000-0000-0000-0000-000000000001'),
      ('a0000000-0000-0000-0000-000000000002')
  ) AS v(public_uid)
 WHERE NOT EXISTS (
     SELECT 1 FROM listing_unit lu WHERE lu.public_uid = v.public_uid::uuid
 );

INSERT INTO listing_unit (public_uid, seller_profile_id, catalog_variant_id, condition_code, listing_id, status, acquisition_cost, acquired_at)
SELECT v.public_uid::uuid, sp.id, cv.id, 'NM', l.id, 'LISTED', 180.00, now()
  FROM seller_profile sp
  JOIN user_account u ON u.id = sp.user_id AND u.username = 'seller_user'
  JOIN catalog_variant cv ON cv.sku = 'POKEMON-SV8A-006-165-EN-FOIL'
  JOIN listing l ON l.seller_profile_id = sp.id AND l.catalog_variant_id = cv.id AND l.status = 'ACTIVE'
  CROSS JOIN (VALUES
      ('a0000000-0000-0000-0000-000000000003'),
      ('a0000000-0000-0000-0000-000000000004')
  ) AS v(public_uid)
 WHERE NOT EXISTS (
     SELECT 1 FROM listing_unit lu WHERE lu.public_uid = v.public_uid::uuid
 );

-- ---------- sequence resynchronization ----------

SELECT setval(pg_get_serial_sequence('user_account', 'id'), COALESCE((SELECT max(id) FROM user_account), 1));
SELECT setval(pg_get_serial_sequence('seller_profile', 'id'), COALESCE((SELECT max(id) FROM seller_profile), 1));
SELECT setval(pg_get_serial_sequence('game', 'id'), COALESCE((SELECT max(id) FROM game), 1));
SELECT setval(pg_get_serial_sequence('catalog_category', 'id'), COALESCE((SELECT max(id) FROM catalog_category), 1));
SELECT setval(pg_get_serial_sequence('card_set', 'id'), COALESCE((SELECT max(id) FROM card_set), 1));
SELECT setval(pg_get_serial_sequence('catalog_product', 'id'), COALESCE((SELECT max(id) FROM catalog_product), 1));
SELECT setval(pg_get_serial_sequence('catalog_variant', 'id'), COALESCE((SELECT max(id) FROM catalog_variant), 1));
SELECT setval(pg_get_serial_sequence('listing', 'id'), COALESCE((SELECT max(id) FROM listing), 1));
SELECT setval(pg_get_serial_sequence('listing_unit', 'id'), COALESCE((SELECT max(id) FROM listing_unit), 1));
SELECT setval(pg_get_serial_sequence('address', 'id'), COALESCE((SELECT max(id) FROM address), 1));

COMMIT;

-- What came out of it.
SELECT (SELECT count(*) FROM game)             AS games,
       (SELECT count(*) FROM game_attribute)   AS attributes,
       (SELECT count(*) FROM catalog_category) AS categories,
       (SELECT count(*) FROM card_set)         AS card_sets,
       (SELECT count(*) FROM catalog_product)  AS products,
       (SELECT count(*) FROM catalog_variant)  AS variants,
       (SELECT count(*) FROM user_account WHERE username IN ('buyer_user', 'seller_user', 'admin_user')) AS test_users,
       (SELECT count(*) FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id WHERE u.username = 'seller_user') AS seller_profiles,
       (SELECT count(*) FROM address a JOIN user_account u ON u.id = a.user_id WHERE u.username = 'buyer_user') AS buyer_addresses,
       (SELECT count(*) FROM listing l JOIN seller_profile sp ON sp.id = l.seller_profile_id JOIN user_account u ON u.id = sp.user_id WHERE u.username = 'seller_user') AS active_listings,
       (SELECT count(*) FROM listing_unit lu JOIN seller_profile sp ON sp.id = lu.seller_profile_id JOIN user_account u ON u.id = sp.user_id WHERE u.username = 'seller_user') AS listed_units;
