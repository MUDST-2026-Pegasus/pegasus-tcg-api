-- Demo seed: play the timeline, then what does not move money or stock.

-- ---------- play it all ----------
-- Every listing, price change, order step, review and payout from 03 and 04, in time order.
SELECT pg_temp.run_events();

CREATE FUNCTION pg_temp.primary_image(p_slug text) RETURNS text
    LANGUAGE sql STABLE AS $$
    SELECT ci.image_key FROM catalog_image ci JOIN catalog_product p ON p.id = ci.catalog_product_id
     WHERE p.slug = p_slug AND ci.is_primary
$$;

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
INSERT INTO home_banner (display_order, eyebrow, title, description, image_key, image_alt, theme, primary_label,
                         primary_href, secondary_label, secondary_href, is_active, created_at, updated_at)
SELECT v.ord::smallint, v.eyebrow, replace(v.title, '\n', E'\n'), v.description, v.image_key, v.alt, v.theme,
       v.p_label, v.p_href, v.s_label, v.s_href, true, pg_temp.ago(v.created_days), pg_temp.ago(v.created_days)
  FROM (VALUES
        (1, 'สินค้าใหม่ล่าสุด', 'MEGA EVOLUTION\nมาแล้ว', 'ซองสุ่มชุดใหม่ของโปเกมอนการ์ดเกม พร้อมส่งจากร้านที่ยืนยันตัวตนแล้ว',
         pg_temp.primary_image('mega-evolution-booster-pack'), 'ซองบูสเตอร์ Mega Evolution', 'RELEASE',
         'ช้อป Pokemon', '/search?game=pokemon-tcg', 'ร้าน Demo Seller Shop', '/store/demo_seller', 15),
        (2, 'ONE PIECE WEEK', 'ออกเรือไปกับ\nOP-09 และ OP-10', 'กล่องสุ่ม ลีดเดอร์ และการ์ด SEC จากร้านเฉพาะทาง',
         pg_temp.primary_image('op09-emperors-booster-box'), 'กล่องบูสเตอร์ One Piece OP-09', 'CAMPAIGN',
         'ช้อป One Piece', '/search?game=one-piece-card-game', 'ร้าน TCG Corner', '/store/tcg_corner', 30),
        (3, 'COLLECTOR SPOTLIGHT', 'สำหรับ\nนักสะสมตัวจริง', 'การ์ด Special Illustration Rare จากร้านที่เราไว้ใจ',
         pg_temp.primary_image('charizard-ex-sir-199-165'), 'ลิซาร์ดอน ex SIR', 'COLLECTOR',
         'ดูลิซาร์ดอน ex SIR', '/search?q=Charizard', NULL, NULL, 40)
       ) AS v(ord, eyebrow, title, description, image_key, alt, theme, p_label, p_href, s_label, s_href, created_days);

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

-- ---------- Thai search words ----------
-- The reset empties search_alias, so the rows migration V20261112_1101 ships are put back.
INSERT INTO search_alias (alias, target, created_at) VALUES
    ('โปเกมอน', 'Pokemon', pg_temp.ago(1)), ('โปเกม่อน', 'Pokemon', pg_temp.ago(1)), ('โปเกมม่อน', 'Pokemon', pg_temp.ago(1)),
    ('วันพีซ', 'One Piece', pg_temp.ago(1)), ('วันพีช', 'One Piece', pg_temp.ago(1)),
    ('ยูกิ', 'Yu-Gi-Oh', pg_temp.ago(1)), ('ยูกิโอ', 'Yu-Gi-Oh', pg_temp.ago(1)),
    ('ดิจิมอน', 'Digimon', pg_temp.ago(1)), ('ดราก้อนบอล', 'Dragon Ball', pg_temp.ago(1)),
    ('ปิกาจู', 'Pikachu', pg_temp.ago(1)), ('ชาริซาร์ด', 'Charizard', pg_temp.ago(1)), ('ลิซาร์ดอน', 'Charizard', pg_temp.ago(1)),
    ('มิวทู', 'Mewtwo', pg_temp.ago(1)), ('อีวุย', 'Eevee', pg_temp.ago(1)), ('ลูฟี่', 'Luffy', pg_temp.ago(1)),
    ('โซโล', 'Zoro', pg_temp.ago(1)), ('โซโร', 'Zoro', pg_temp.ago(1)),
    ('บูสเตอร์บ็อกซ์', 'Booster Box', pg_temp.ago(1)), ('กล่องบูสเตอร์', 'Booster Box', pg_temp.ago(1)),
    ('ซอง', 'Booster Pack', pg_temp.ago(1)), ('การ์ดเกรด', 'graded', pg_temp.ago(1)),
    ('สลีฟ', 'Sleeves', pg_temp.ago(1)), ('ซองใส่การ์ด', 'Sleeves', pg_temp.ago(1)),
    ('พิคาชู', 'Pikachu', pg_temp.ago(1)), ('แบล็กกี', 'Umbreon', pg_temp.ago(1)), ('แชงคูส', 'Shanks', pg_temp.ago(1))
ON CONFLICT DO NOTHING;
