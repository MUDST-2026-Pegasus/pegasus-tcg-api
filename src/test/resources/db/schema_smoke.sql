-- Schema-level proof for three of the five regression tests the schema guide
-- names, plus the constraints that are easy to break by accident.
--
-- Runs entirely in one transaction and rolls back, so it is safe against any
-- database that has the migrations applied — including one with data in it:
-- every fixture has a smoke_ name, and every lookup is keyed to those.
--
--   docker exec -i pegasus-tcg-postgres psql -U postgres -d pegasus_tcg \
--       -v ON_ERROR_STOP=1 < src/test/resources/db/schema_smoke.sql
--
-- Covers guide tests 2 (quantity_* is derived), 4 (a second listing for the same
-- seller/variant/condition is allowed) and 5 (one price edit reaches every card
-- in the group). Test 3 (20 concurrent buyers, 1 card) needs real concurrent
-- sessions and lives in ListingInventoryIntegrationTest; test 1 (ledger sums to
-- wallet.balance) waits for the payment code.

\set ON_ERROR_STOP on
BEGIN;

-- ---------- fixtures ----------
INSERT INTO user_account (email, password_hash, username, display_name)
VALUES ('smoke_ploy@example.com', 'x', 'smoke_ploy', 'Ploy');

INSERT INTO seller_profile (user_id, status, verified_at)
SELECT id, 'VERIFIED', now() FROM user_account WHERE username = 'smoke_ploy';

INSERT INTO game (code, name, slug) VALUES ('SMOKE_PKM', 'Pokemon TCG', 'smoke-pokemon');
INSERT INTO catalog_category (game_id, code, name, slug)
SELECT id, 'SINGLES', 'Single Cards', 'singles' FROM game WHERE code = 'SMOKE_PKM';
INSERT INTO card_set (game_id, code, name)
SELECT id, 'SV8a', 'Terastal Festival' FROM game WHERE code = 'SMOKE_PKM';

-- Joined on the game: the seeded cross-game categories have a SINGLES too.
INSERT INTO catalog_product (game_id, category_id, card_set_id, name, slug, card_number, attributes)
SELECT g.id, c.id, s.id, 'Pikachu ex', 'smoke-pikachu-ex-sv8a-025', '025/187', '{"hp":200}'
  FROM game g
  JOIN catalog_category c ON c.game_id = g.id AND c.code = 'SINGLES'
  JOIN card_set s ON s.game_id = g.id AND s.code = 'SV8a'
 WHERE g.code = 'SMOKE_PKM';

INSERT INTO catalog_variant (catalog_product_id, sku, language_code, finish)
SELECT id, 'SMOKE-PKM-SV8A-025-EN-NORMAL', 'EN', 'NORMAL' FROM catalog_product WHERE slug = 'smoke-pikachu-ex-sv8a-025';
INSERT INTO catalog_variant (catalog_product_id, sku, language_code, finish)
SELECT id, 'SMOKE-PKM-SV8A-025-JP-FOIL', 'JP', 'FOIL' FROM catalog_product WHERE slug = 'smoke-pikachu-ex-sv8a-025';

\set sp '(SELECT sp.id FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id WHERE u.username = \'smoke_ploy\')'
\set vjp '(SELECT id FROM catalog_variant WHERE sku = \'SMOKE-PKM-SV8A-025-JP-FOIL\')'
\set ven '(SELECT id FROM catalog_variant WHERE sku = \'SMOKE-PKM-SV8A-025-EN-NORMAL\')'

-- ---------- test 4: same seller + variant + condition, a second listing must be allowed ----------
INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, price, status, lot_label)
VALUES (:sp, :vjp, 'NM', 1290.00, 'ACTIVE', 'smoke-lot-a'),
       (:sp, :vjp, 'NM', 1390.00, 'ACTIVE', 'smoke-lot-b');
\echo '[PASS] test 4 — two listings, same seller/variant/condition'

\set la '(SELECT id FROM listing WHERE lot_label = \'smoke-lot-a\')'
\set lb '(SELECT id FROM listing WHERE lot_label = \'smoke-lot-b\')'

-- ---------- six real cards: 3 on lot-a, 2 on lot-b, 1 still in hand ----------
INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code, listing_id, status, acquisition_cost, acquired_at)
VALUES (:sp, :vjp, 'NM', :la, 'LISTED',    900.00, now() - interval '5 days'),
       (:sp, :vjp, 'NM', :la, 'LISTED',    900.00, now() - interval '5 days'),
       (:sp, :vjp, 'NM', :la, 'LISTED',    900.00, now() - interval '4 days'),
       (:sp, :vjp, 'NM', :lb, 'LISTED',   1050.00, now() - interval '3 days'),
       (:sp, :vjp, 'NM', :lb, 'LISTED',   1050.00, now() - interval '3 days'),
       (:sp, :vjp, 'NM', NULL, 'IN_STOCK', 1050.00, now() - interval '1 day');

-- ---------- test 2: quantity_* is derived from the units ----------
SELECT lot_label, quantity_total, quantity_available, quantity_reserved
  FROM listing WHERE lot_label LIKE 'smoke-lot-%' ORDER BY lot_label;
DO $$
DECLARE a record; b record;
BEGIN
    SELECT * INTO a FROM listing WHERE lot_label = 'smoke-lot-a';
    SELECT * INTO b FROM listing WHERE lot_label = 'smoke-lot-b';
    ASSERT a.quantity_available = 3 AND a.quantity_total = 3 AND a.quantity_reserved = 0, 'lot-a wrong';
    ASSERT b.quantity_available = 2 AND b.quantity_total = 2, 'lot-b wrong';
END $$;
\echo '[PASS] test 2 — quantity_available matches COUNT(LISTED)'

-- ---------- moving a card between listings keeps its UUID and re-counts both ----------
\set moved '(SELECT id FROM listing_unit WHERE listing_id = ' :la ' ORDER BY id DESC LIMIT 1)'
CREATE TEMP TABLE moved_uid AS SELECT public_uid FROM listing_unit WHERE id = :moved;
UPDATE listing_unit SET listing_id = :lb WHERE id = :moved;
DO $$
DECLARE a int; b int; same boolean;
BEGIN
    SELECT quantity_available INTO a FROM listing WHERE lot_label = 'smoke-lot-a';
    SELECT quantity_available INTO b FROM listing WHERE lot_label = 'smoke-lot-b';
    SELECT EXISTS (SELECT 1 FROM listing_unit u JOIN moved_uid m ON m.public_uid = u.public_uid) INTO same;
    ASSERT a = 2, 'lot-a should be 2 after the move, got ' || a;
    ASSERT b = 3, 'lot-b should be 3 after the move, got ' || b;
    ASSERT same, 'public_uid must survive the move';
END $$;
\echo '[PASS] move — both listings re-counted, public_uid unchanged'

-- ---------- reserving shifts available into reserved ----------
UPDATE listing_unit SET status = 'RESERVED'
WHERE id = (SELECT id FROM listing_unit WHERE listing_id = :lb ORDER BY acquired_at LIMIT 1);
DO $$
DECLARE b record;
BEGIN
    SELECT * INTO b FROM listing WHERE lot_label = 'smoke-lot-b';
    ASSERT b.quantity_reserved = 1 AND b.quantity_available = 2 AND b.quantity_total = 3,
        'reserve counts wrong: ' || b.quantity_reserved || '/' || b.quantity_available;
END $$;
\echo '[PASS] reserve — quantity_reserved tracks RESERVED units'

-- ---------- test 5: one price edit moves every card in the group ----------
UPDATE listing SET price = 1490.00 WHERE lot_label = 'smoke-lot-b';
DO $$
DECLARE stale int;
BEGIN
    SELECT count(*) INTO stale FROM listing_unit u JOIN listing l ON l.id = u.listing_id
     WHERE l.lot_label = 'smoke-lot-b' AND l.price <> 1490.00;
    ASSERT stale = 0, 'some units still see the old price';
END $$;
\echo '[PASS] test 5 — price edit reaches all units in the group'

-- ---------- trigger: a card cannot join a listing for a different variant ----------
DO $$
BEGIN
    UPDATE listing_unit SET catalog_variant_id =
        (SELECT id FROM catalog_variant WHERE sku = 'SMOKE-PKM-SV8A-025-EN-NORMAL')
     WHERE id = (SELECT id FROM listing_unit
                  WHERE listing_id = (SELECT id FROM listing WHERE lot_label = 'smoke-lot-a')
                  LIMIT 1);
    RAISE EXCEPTION 'should not reach here';
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE '[PASS] trigger — mismatched variant refused';
END $$;

-- ---------- CHECK: LISTED with no listing is impossible ----------
DO $$
BEGIN
    INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code, status)
    VALUES ((SELECT sp.id FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id
              WHERE u.username = 'smoke_ploy'),
            (SELECT id FROM catalog_variant WHERE sku = 'SMOKE-PKM-SV8A-025-JP-FOIL'), 'NM', 'LISTED');
    RAISE EXCEPTION 'should not reach here';
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE '[PASS] check — LISTED requires a listing_id';
END $$;

-- ---------- the last card going flips ACTIVE to SOLD_OUT, and one coming back flips it back ----------
INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, price, status, lot_label)
VALUES (:sp, :vjp, 'NM', 1590.00, 'ACTIVE', 'smoke-lot-c');
\set lc '(SELECT id FROM listing WHERE lot_label = \'smoke-lot-c\')'
INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code, listing_id, status, acquisition_cost, acquired_at)
VALUES (:sp, :vjp, 'NM', :lc, 'LISTED', 900.00, now());

UPDATE listing_unit SET status = 'RESERVED' WHERE listing_id = :lc;
DO $$
DECLARE c record;
BEGIN
    SELECT * INTO c FROM listing WHERE lot_label = 'smoke-lot-c';
    ASSERT c.status = 'SOLD_OUT', 'reserving the last card should leave SOLD_OUT, got ' || c.status;
END $$;

UPDATE listing_unit SET status = 'LISTED' WHERE listing_id = :lc;
DO $$
DECLARE c record;
BEGIN
    SELECT * INTO c FROM listing WHERE lot_label = 'smoke-lot-c';
    ASSERT c.status = 'ACTIVE' AND c.quantity_available = 1,
        'a released card should put the listing back on sale, got ' || c.status;
END $$;
\echo '[PASS] sold out — ACTIVE and SOLD_OUT follow the last card'

-- ---------- only a VERIFIED seller may create a listing [RQ-1] ----------
INSERT INTO user_account (email, password_hash, username, display_name)
VALUES ('smoke_pending@example.com', 'x', 'smoke_pending', 'Pending');
INSERT INTO seller_profile (user_id, status)
SELECT id, 'PENDING' FROM user_account WHERE username = 'smoke_pending';
DO $$
BEGIN
    INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, price)
    VALUES ((SELECT sp.id FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id
              WHERE u.username = 'smoke_pending'),
            (SELECT id FROM catalog_variant WHERE sku = 'SMOKE-PKM-SV8A-025-JP-FOIL'), 'NM', 100.00);
    RAISE EXCEPTION 'should not reach here';
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE '[PASS] seller gate — a PENDING seller cannot create a listing';
END $$;

-- ---------- ledger is append-only ----------
INSERT INTO wallet (kind) VALUES ('PLATFORM');
INSERT INTO ledger_entry (wallet_id, entry_type, direction, amount, balance_after, reference_type, reference_id)
VALUES ((SELECT id FROM wallet WHERE kind='PLATFORM'), 'PAYMENT_IN', 'CREDIT', 100, 100, 'PAYMENT', 1);
DO $$
BEGIN
    UPDATE ledger_entry SET amount = 999;
    RAISE EXCEPTION 'should not reach here';
EXCEPTION WHEN restrict_violation THEN
    RAISE NOTICE '[PASS] ledger — UPDATE refused';
END $$;

-- ---------- only one PLATFORM wallet ----------
DO $$
BEGIN
    INSERT INTO wallet (kind) VALUES ('PLATFORM');
    RAISE EXCEPTION 'should not reach here';
EXCEPTION WHEN unique_violation THEN
    RAISE NOTICE '[PASS] wallet — a second PLATFORM wallet refused';
END $$;

-- ---------- commission scope must match its target ----------
DO $$
BEGIN
    INSERT INTO commission_rule (scope, game_id, rate_percent) VALUES ('GLOBAL', 1, 5);
    RAISE EXCEPTION 'should not reach here';
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE '[PASS] commission — GLOBAL rule may not target a game';
END $$;

ROLLBACK;
