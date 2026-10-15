-- Everything that does not move money or stock: baskets, wish lists, cards
-- people added to their collection by hand, price statistics, home page slides,
-- review summaries.

CREATE FUNCTION pg_temp.primary_image(p_slug text) RETURNS text
    LANGUAGE sql STABLE AS $$
    SELECT ci.image_key FROM catalog_image ci JOIN catalog_product p ON p.id = ci.catalog_product_id
     WHERE p.slug = p_slug AND ci.is_primary
$$;

-- ---------- baskets ----------
-- One per account; one signed-out basket keyed by a browser session. Stock is not
-- held for a basket, and one line still carries an older price, which checkout
-- reports as "the price changed".
INSERT INTO cart (user_id, session_key, created_at, updated_at, expires_at)
SELECT pg_temp.uid(v.username), NULL, pg_temp.ago(v.days), pg_temp.ago(v.days), NULL
  FROM (VALUES ('collector_mai', 2), ('ploy_collects', 1), ('deckbuilder_ton', 0.5),
               ('demo_buyer', 3), ('nattapong_tcg', 0.3)) AS v(username, days);
INSERT INTO cart (user_id, session_key, created_at, updated_at, expires_at)
VALUES (NULL, 'guest-' || md5('pegasus-seed-guest-basket'), pg_temp.ago(0.2), pg_temp.ago(0.2), pg_temp.ago(-7));

INSERT INTO cart_item (cart_id, listing_id, quantity, unit_price_at_add, added_at, updated_at)
SELECT c.id, l.id, v.qty, coalesce(v.price_at_add, l.price), pg_temp.ago(v.days), pg_temp.ago(v.days)
  FROM (VALUES
        ('collector_mai',   'ck-luc179-nm',     1, NULL::numeric, 2),
        ('collector_mai',   'po-megbox',        1, 7200,          12),
        ('ploy_collects',   'mv-umb161-psa10',  1, NULL,          1),
        ('ploy_collects',   'gl-nezuko-nm',     2, NULL,          1),
        ('deckbuilder_ton', 'tc-op09-119-nm',   1, NULL,          0.5),
        ('deckbuilder_ton', 'po-op11box',       1, NULL,          0.5),
        ('nattapong_tcg',   'kt-syl156-nm',     1, NULL,          0.3)
       ) AS v(username, lref, qty, price_at_add, days)
  JOIN cart c ON c.user_id = pg_temp.uid(v.username)
  JOIN listing l ON l.id = pg_temp.lid(v.lref);

INSERT INTO cart_item (cart_id, listing_id, quantity, unit_price_at_add, added_at, updated_at)
SELECT c.id, l.id, 2, l.price, pg_temp.ago(0.2), pg_temp.ago(0.2)
  FROM cart c, listing l
 WHERE c.session_key = 'guest-' || md5('pegasus-seed-guest-basket') AND l.id = pg_temp.lid('po-151pack');

-- ---------- wish lists ----------
INSERT INTO wishlist_item (user_id, catalog_variant_id, target_price, created_at)
SELECT pg_temp.uid(v.username), pg_temp.vid(v.sku), v.target, pg_temp.ago(v.days)
  FROM (VALUES
        ('collector_mai',   'PKM-SSP-238-EN-H',             11000::numeric, 60),
        ('collector_mai',   'PKM-PRE-161-EN-H',             32000,          40),
        ('collector_mai',   'PKM-MEG-178-EN-H',             NULL,           10),
        ('ploy_collects',   'PKM-PRE-156-EN-H',             3000,           50),
        ('ploy_collects',   'PKM-PRE-155-EN-H',             4000,           50),
        ('ploy_collects',   'PKM-PRE-167-EN-H',             NULL,           70),
        ('deckbuilder_ton', 'OPC-OP01-120-EN-F-PAR',        6000,           90),
        ('deckbuilder_ton', 'MTG-CMM-150-EN-N',             2000,           30),
        ('nattapong_tcg',   'DBF-FB07-122-EN-F',            1800,           15),
        ('nattapong_tcg',   'DGM-BT21-102-EN-F',            NULL,           45),
        ('demo_buyer',      'YGO-RA03-EN123-EN-F-1E-QCSR',  1200,           25)
       ) AS v(username, sku, target, days);

-- ---------- collections ----------
-- Bought cards arrived on their own when each order completed (06). These are
-- cards people already owned and typed in themselves; one was removed later.
INSERT INTO collection_item (user_id, catalog_variant_id, condition_code, quantity, source, grading_company, grade_value,
                             cert_number, acquired_price, acquired_at, image_key, personal_note, is_public,
                             created_at, updated_at, deleted_at)
SELECT pg_temp.uid(v.username), cv.id, v.cond, v.qty, 'MANUAL', v.grader, v.grade, v.cert, v.price,
       pg_temp.ago(v.acquired_days), pg_temp.primary_image(p.slug), v.note, v.public,
       pg_temp.ago(v.created_days), pg_temp.ago(v.created_days),
       CASE WHEN v.deleted_days IS NOT NULL THEN pg_temp.ago(v.deleted_days) END
  FROM (VALUES
        ('collector_mai',   'PKM-MEW-199-EN-H',       'NM', 1, 'PSA', 10::numeric, 'PSA 70011223', 15000::numeric, 700, 200, 'ใบแรกที่ส่งเกรดได้ 10', true,  NULL::numeric),
        ('collector_mai',   'PKM-MEW-151-EN-H',       'NM', 3, NULL,  NULL,        NULL,           90,             650, 200, NULL,                      false, NULL),
        ('deckbuilder_ton', 'OPC-OP01-025-EN-F',      'NM', 4, NULL,  NULL,        NULL,           300,            400, 180, 'ใช้ในเด็คแข่ง',             true,  NULL),
        ('deckbuilder_ton', 'MTG-CMM-410-EN-N',       'LP', 2, NULL,  NULL,        NULL,           60,             380, 180, NULL,                      true,  NULL),
        ('ploy_collects',   'PKM-PRE-167-EN-H',       'NM', 1, 'BGS', 9.5,         'BGS 0011223344', 2600,          150, 140, 'อีวุยตัวโปรด',              true,  NULL),
        ('ploy_collects',   'PKM-MEW-025-EN-RH',      'NM', 1, NULL,  NULL,        NULL,           30,             300, 140, NULL,                      false, 20),
        ('nattapong_tcg',   'YGO-LOB-EN124-EN-H',     'LP', 1, NULL,  NULL,        NULL,           150,            500, 120, 'ได้มาจากงานเทรด',          false, NULL)
       ) AS v(username, sku, cond, qty, grader, grade, cert, price, acquired_days, created_days, note, public, deleted_days)
  JOIN catalog_variant cv ON cv.sku = v.sku
  JOIN catalog_product p ON p.id = cv.catalog_product_id;

-- Collectors show off a few of the cards they bought.
UPDATE collection_item ci
   SET is_public = true, personal_note = 'ซื้อจาก Pegasus', updated_at = ci.acquired_at + interval '1 day'
  FROM catalog_variant cv
 WHERE cv.id = ci.catalog_variant_id AND ci.source = 'PURCHASE'
   AND cv.sku IN ('PKM-SSP-238-EN-H', 'PKM-PRE-161-EN-H', 'PKM-MEW-199-EN-H', 'PKM-PRE-155-EN-H', 'DBF-FB05-119-EN-F');

-- ---------- price statistics ----------
-- Today's row is computed exactly as the nightly job computes it (ACTIVE listings
-- of verified sellers not on vacation, sales in the last 30 days). The 29 days
-- before it plus the 30-day comparison day are a gentle, repeatable drift
-- around today's prices.
CREATE TEMP TABLE seed_market_today ON COMMIT DROP AS
SELECT l.catalog_variant_id, l.condition_code,
       round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY l.price) AS numeric), 2)  AS median_price,
       round(avg(l.price), 2)                                                          AS avg_price,
       min(l.price)                                                                    AS min_price,
       max(l.price)                                                                    AS max_price,
       round(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY l.price) AS numeric), 2) AS p25_price,
       round(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY l.price) AS numeric), 2) AS p75_price,
       count(*)::int                                                                   AS listings
  FROM listing l
  JOIN seller_profile sp ON sp.id = l.seller_profile_id
 WHERE l.status = 'ACTIVE' AND l.deleted_at IS NULL AND sp.status = 'VERIFIED' AND NOT sp.vacation_mode
 GROUP BY l.catalog_variant_id, l.condition_code;

INSERT INTO variant_market_stat (catalog_variant_id, condition_code, stat_date, median_price, avg_price, min_price,
                                 max_price, p25_price, p75_price, active_listing_count, sold_quantity_30d,
                                 sample_size, computed_at)
SELECT t.catalog_variant_id, t.condition_code,
       ((SELECT anchor FROM seed_clock) AT TIME ZONE 'Asia/Bangkok')::date - d.n,
       round(t.median_price * f.k, 2), round(t.avg_price * f.k, 2), round(t.min_price * f.k, 2),
       round(t.max_price * f.k, 2), round(t.p25_price * f.k, 2), round(t.p75_price * f.k, 2),
       CASE WHEN d.n = 0 THEN t.listings ELSE greatest(1, t.listings + (d.n % 3) - 1) END,
       coalesce((SELECT sum(oi.quantity)::int
                   FROM order_item oi JOIN seller_order so ON so.id = oi.seller_order_id
                  WHERE oi.catalog_variant_id = t.catalog_variant_id
                    AND oi.condition_snapshot = t.condition_code
                    AND so.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
                    AND oi.created_at >= pg_temp.ago(30 + d.n)
                    AND oi.created_at < pg_temp.ago(d.n)), 0),
       CASE WHEN d.n = 0 THEN t.listings ELSE greatest(1, t.listings + (d.n % 3) - 1) END,
       pg_temp.ago(d.n) - interval '2 hours'
  FROM seed_market_today t
 CROSS JOIN generate_series(0, 30) AS d(n)
 CROSS JOIN LATERAL (
        SELECT CASE WHEN d.n = 0 THEN 1::numeric
                    ELSE round((1 + 0.0035 * d.n * CASE WHEN t.catalog_variant_id % 2 = 0 THEN 1 ELSE -1 END
                                  + 0.03 * sin(d.n / 3.0 + t.catalog_variant_id))::numeric, 4) END AS k
       ) AS f;

-- ---------- home page slides ----------
-- Official share images where the publisher has one; otherwise the product art.
-- Two slides are not live: one scheduled for later, one whose campaign ended,
-- and one is switched off.
INSERT INTO home_banner (display_order, eyebrow, title, description, image_key, image_alt, theme, primary_label,
                         primary_href, secondary_label, secondary_href, is_active, starts_at, ends_at,
                         created_at, updated_at)
SELECT v.ord::smallint, v.eyebrow, replace(v.title, '\n', E'\n'), v.description, v.image_key, v.alt, v.theme,
       v.p_label, v.p_href, v.s_label, v.s_href, v.active,
       CASE WHEN v.starts_days IS NOT NULL THEN pg_temp.ago(v.starts_days) END,
       CASE WHEN v.ends_days IS NOT NULL THEN pg_temp.ago(v.ends_days) END,
       pg_temp.ago(v.created_days), pg_temp.ago(v.created_days)
  FROM (VALUES
        (1, 'สินค้าใหม่ล่าสุด', 'MEGA EVOLUTION\nมาแล้ว', 'เปิดยุคใหม่ของโปเกมอนการ์ดเกม กล่องสุ่มและซองพร้อมส่งจากร้านที่ยืนยันตัวตนแล้ว',
         pg_temp.primary_image('mega-evolution-booster-box'), 'กล่องบูสเตอร์ Mega Evolution', 'RELEASE',
         'ช้อป Mega Evolution', '/search?q=Mega%20Evolution', 'โปเกมอนทั้งหมด', '/search?game=pokemon-tcg', true, NULL::numeric, NULL::numeric, 18),
        (2, 'MAGIC: THE GATHERING', 'FINAL FANTASY\nIN MAGIC', 'คลาวด์ วีวี่ และตัวละครในตำนานในรูปแบบการ์ดเมจิก',
         'seed/banners/mtg-final-fantasy.jpg', 'ภาพโปรโมต Magic: The Gathering FINAL FANTASY', 'RELEASE',
         'ช้อป Magic', '/search?game=magic-the-gathering', 'การ์ดซิงเกิล', '/search?category=single-cards', true, NULL, NULL, 70),
        (3, 'ONE PIECE WEEK', 'ออกเรือไปกับ\nOP-09 และ OP-10', 'กล่องสุ่ม ลีดเดอร์ และการ์ด SEC จากร้านเฉพาะทาง',
         pg_temp.primary_image('op09-emperors-booster-box'), 'กล่องบูสเตอร์ One Piece OP-09', 'CAMPAIGN',
         'ช้อป One Piece', '/search?game=one-piece-card-game', 'ร้าน TCG Corner', '/store/tcg_corner', true, NULL, NULL, 40),
        (4, 'UNION ARENA', 'การ์ดอนิเมะ\nที่คุณรัก', 'มหาเวทย์ผนึกมาร ดาบพิฆาตอสูร วันพันช์แมน รวมไว้ในที่เดียว',
         'seed/banners/union-arena.png', 'ภาพโปรโมต Union Arena', 'COLLECTOR',
         'ช้อป Union Arena', '/search?game=union-arena', 'ร้าน Grand Line Cards', '/store/grand_line_cards', true, NULL, NULL, 30),
        (5, 'DIGIMON CARD GAME', 'ดิจิมอน\nพร้อมลุย', 'ชุด World Convergence และ Cyber Eden ราคาดีจากผู้ขายในไทย',
         'seed/banners/digimon-card-game.jpg', 'ภาพโปรโมต Digimon Card Game', 'CAMPAIGN',
         'ช้อป Digimon', '/search?game=digimon-card-game', NULL, NULL, true, NULL, NULL, 25),
        (6, 'เร็ว ๆ นี้', 'FUSION WORLD\nสัปดาห์หน้า', 'แคมเปญ Dragon Ball Super Fusion World เริ่มเร็ว ๆ นี้',
         'seed/banners/dragon-ball-fusion-world.png', 'ภาพโปรโมต Dragon Ball Super Card Game Fusion World', 'RELEASE',
         'ช้อป Fusion World', '/search?game=dragon-ball-fusion-world', NULL, NULL, true, -3, -17, 2),
        (7, 'จบแคมเปญแล้ว', 'EDGE OF\nETERNITIES', 'แคมเปญเปิดตัว Edge of Eternities (สิ้นสุดแล้ว)',
         'seed/banners/mtg-edge-of-eternities.png', 'ภาพโปรโมต Edge of Eternities', 'RELEASE',
         'ช้อป Magic', '/search?game=magic-the-gathering', NULL, NULL, true, 60, 30, 60),
        (8, 'COLLECTOR SPOTLIGHT', 'สำหรับ\nนักสะสมตัวจริง', 'การ์ดเกรดและ Special Illustration Rare จากร้านที่เราไว้ใจ',
         pg_temp.primary_image('charizard-ex-sir-199-165'), 'ลิซาร์ดอน ex SIR', 'COLLECTOR',
         'ดูการ์ดเกรด', '/store/mint_vault', NULL, NULL, false, NULL, NULL, 90)
       ) AS v(ord, eyebrow, title, description, image_key, alt, theme, p_label, p_href, s_label, s_href, active,
              starts_days, ends_days, created_days);

-- ---------- review summaries (ReviewRepository.refreshSummary) ----------
INSERT INTO seller_rating_summary (seller_profile_id, rating_count, rating_sum, rating_avg,
                                   count_1, count_2, count_3, count_4, count_5, updated_at)
SELECT sp.id,
       count(r.id) FILTER (WHERE r.is_visible),
       coalesce(sum(r.rating) FILTER (WHERE r.is_visible), 0),
       coalesce(round(avg(r.rating) FILTER (WHERE r.is_visible), 2), 0),
       count(*) FILTER (WHERE r.is_visible AND r.rating = 1),
       count(*) FILTER (WHERE r.is_visible AND r.rating = 2),
       count(*) FILTER (WHERE r.is_visible AND r.rating = 3),
       count(*) FILTER (WHERE r.is_visible AND r.rating = 4),
       count(*) FILTER (WHERE r.is_visible AND r.rating = 5),
       max(r.updated_at)
  FROM seller_profile sp
  JOIN seller_review r ON r.seller_profile_id = sp.id
 GROUP BY sp.id;

-- An admin once tuned a setting (to the same value), so updated_by is filled in.
UPDATE platform_setting SET updated_by = pg_temp.uid('admin'), updated_at = pg_temp.ago(45)
 WHERE setting_key IN ('order.cancel_window_hours', 'escrow.auto_release_days');
