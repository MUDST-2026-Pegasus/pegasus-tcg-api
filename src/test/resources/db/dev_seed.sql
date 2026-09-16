-- Sample catalogue data for a local machine. NOT a migration, on purpose: this
-- is a Pokemon game with four made-up-ish cards, and it has no business
-- reaching production the way the seeded roles and settings do.
--
-- Run it against a migrated database:
--
--   docker exec -i pegasus-tcg-postgres psql -U postgres -d pegasus_tcg \
--       -v ON_ERROR_STOP=1 < src/test/resources/db/dev_seed.sql
--
-- Safe to run twice: every insert skips what is already there, so it tops up a
-- database rather than duplicating it. It does not create an admin account —
-- register through the API, then grant the role:
--
--   INSERT INTO user_role (user_id, role_id)
--   SELECT u.id, r.id FROM user_account u, app_role r
--    WHERE u.username = 'yourname' AND r.code = 'ADMIN';

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
-- The Pikachu has two: an English plain print and a Japanese foil. They are
-- separate rows because they are separate markets, which is the whole reason
-- listings point at a variant rather than at a product.

INSERT INTO catalog_variant (catalog_product_id, sku, language_code, finish, edition)
SELECT p.id, v.sku, v.language_code, v.finish, v.edition
  FROM catalog_product p
  CROSS JOIN LATERAL (VALUES
        ('pikachu-ex-025-187',   'POKEMON-SV8A-025-187-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('pikachu-ex-025-187',   'POKEMON-SV8A-025-187-JP-FOIL',   'JP', 'FOIL',   'UNLIMITED'),
        ('charizard-ex-006-165', 'POKEMON-SV8A-006-165-EN-FOIL',   'EN', 'FOIL',   'UNLIMITED'),
        ('mew-ex-151-165',       'POKEMON-SV8A-151-165-JP-NORMAL', 'JP', 'NORMAL', 'UNLIMITED'),
        ('terastal-festival-booster-box',
         'POKEMON-SV8A-BOX-JP', 'JP', 'NOT_APPLICABLE', 'NOT_APPLICABLE')
       ) AS v(slug, sku, language_code, finish, edition)
 WHERE p.slug = v.slug
   AND NOT EXISTS (SELECT 1 FROM catalog_variant cv WHERE cv.sku = v.sku);

COMMIT;

-- What came out of it.
SELECT (SELECT count(*) FROM game)             AS games,
       (SELECT count(*) FROM game_attribute)   AS attributes,
       (SELECT count(*) FROM catalog_category) AS categories,
       (SELECT count(*) FROM card_set)         AS card_sets,
       (SELECT count(*) FROM catalog_product)  AS products,
       (SELECT count(*) FROM catalog_variant)  AS variants;
