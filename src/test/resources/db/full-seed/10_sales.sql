-- Direct price discounts [TCG-684, SAL-09]: the campaigns on Figma SAL-A1 and one
-- shop sale per status the seller and admin screens show. Dates are relative to
-- now, so each row lands in the same status whenever the seed runs:
--
--   11.11 Flash Sale     PLATFORM FLASH_SALE     SCHEDULED          starts in 6 days, 24 h
--   Pokémon Month        PLATFORM CATEGORY_SALE  ACTIVE             Pokémon, 10% (SAL-B3 A)
--   OP-09 Launch         PLATFORM CATEGORY_SALE  ACTIVE             card set OP09, 15%
--   Midnight Flash       PLATFORM FLASH_SALE     SOLD_OUT           MTG, every listing's 1 card taken
--   Halloween Yu-Gi-Oh!  PLATFORM CATEGORY_SALE  BUDGET_EXHAUSTED   Yu-Gi-Oh!, budget hit
--   OP-01 Week           tcg_corner      LISTINGS     ACTIVE        two listings at a fixed price
--   ลดทั้งร้าน 5%          card_kingdom_th SHOP         PAUSED        paused by admin, with a reason
--   Graded weekend       mint_vault      SHOP         SCHEDULED     starts in 3 days
--
-- No order in 06_orders.sql was bought at a sale price, so nothing here holds
-- quota or subsidy for an order: Midnight Flash's sold_count and Halloween's
-- budget_exhausted_at only set the status. listing_sale_price is left empty; the
-- app's SalePriceRefresher fills it within a minute of starting.

INSERT INTO price_sale (name, slug, owner_type, seller_profile_id, sale_type, discount_type, discount_value,
                        max_discount_per_unit, scope_type, scope_ids, conditions, min_unit_price,
                        per_listing_quota, per_buyer_limit, budget_cap, subsidy_reserved, budget_exhausted_at,
                        banner_image_url, featured_on_home, home_sort, starts_at, ends_at,
                        paused_at, paused_by, pause_reason, created_by, created_at, updated_at)
SELECT v.name, v.slug, v.owner_type,
       CASE WHEN v.shop IS NOT NULL THEN pg_temp.spid(v.shop) END,
       v.sale_type, v.discount_type, v.discount_value, v.max_per_unit, v.scope_type,
       CASE v.scope_type
            WHEN 'GAME' THEN ARRAY[(SELECT g.id FROM game g WHERE g.code = v.scope_code)]::bigint[]
            WHEN 'CARD_SET' THEN ARRAY[(SELECT s.id FROM card_set s WHERE s.code = v.scope_code)]::bigint[]
            ELSE '{}'::bigint[] END,
       '{}', v.min_unit_price, v.quota, v.per_buyer, v.budget, v.reserved,
       CASE WHEN v.exhausted_days IS NOT NULL THEN pg_temp.ago(v.exhausted_days) END,
       NULL, v.home_sort IS NOT NULL, v.home_sort,
       pg_temp.ago(v.start_days, v.start_hours), pg_temp.ago(v.end_days, v.end_hours),
       CASE WHEN v.paused_days IS NOT NULL THEN pg_temp.ago(v.paused_days) END,
       CASE WHEN v.paused_days IS NOT NULL THEN pg_temp.uid('admin') END,
       v.pause_reason,
       pg_temp.uid(coalesce(v.shop, 'admin')),
       pg_temp.ago(greatest(v.start_days, 0) + 1), pg_temp.ago(greatest(v.start_days, 0) + 1)
  FROM (VALUES
        -- name, slug, owner, shop, type, discount, value, max/unit, scope, scope code, min price,
        -- quota, per buyer, budget, reserved, exhausted (days ago), home sort,
        -- start days, start hours, end days, end hours, paused (days ago), pause reason
        ('11.11 Flash Sale', '1111-flash', 'PLATFORM', NULL::text, 'FLASH_SALE', 'PERCENT', 30::numeric,
         1500::numeric, 'ALL', NULL::text, 100::numeric, 20, 1, 50000::numeric, 0::numeric, NULL::numeric, 1,
         -6::numeric, 0::numeric, -7::numeric, 0::numeric, NULL::numeric, NULL::text),
        ('Pokémon Month', 'pokemon-month', 'PLATFORM', NULL, 'CATEGORY_SALE', 'PERCENT', 10,
         NULL, 'GAME', 'POKEMON', NULL, NULL, NULL, 100000, 0, NULL, 2,
         5, 0, -25, 0, NULL, NULL),
        ('OP-09 Launch', 'op-09-launch', 'PLATFORM', NULL, 'CATEGORY_SALE', 'PERCENT', 15,
         500, 'CARD_SET', 'OP09', NULL, NULL, NULL, NULL, 0, NULL, 3,
         2, 0, -12, 0, NULL, NULL),
        ('Midnight Flash', 'midnight-flash', 'PLATFORM', NULL, 'FLASH_SALE', 'PERCENT', 40,
         1000, 'GAME', 'MTG', NULL, 1, 1, NULL, 0, NULL, 4,
         0, 2, 0, -4, NULL, NULL),
        ('Halloween Yu-Gi-Oh!', 'halloween-yugioh', 'PLATFORM', NULL, 'CATEGORY_SALE', 'PERCENT', 20,
         NULL, 'GAME', 'YUGIOH', NULL, NULL, NULL, 20000, 0, 1, NULL,
         8, 0, -3, 0, NULL, NULL),
        -- Shop sales: the shop pays, so no budget, never on the home page.
        ('OP-01 Week', 'op-01-week-tcg-corner', 'SELLER', 'tcg_corner', 'SHOP_SALE', 'FIXED_PRICE', 0,
         NULL, 'LISTINGS', NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL,
         1, 0, -6, 0, NULL, NULL),
        ('ลดทั้งร้าน 5%', 'shop-5-card-kingdom', 'SELLER', 'card_kingdom_th', 'SHOP_SALE', 'PERCENT', 5,
         NULL, 'SHOP', NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL,
         3, 0, -10, 0, 1, 'ราคาตั้งต้นของหลายรายการสูงกว่าราคาตลาดผิดปกติ รอร้านตรวจสอบ'),
        ('Graded weekend', 'graded-weekend-mint-vault', 'SELLER', 'mint_vault', 'SHOP_SALE', 'PERCENT', 8,
         NULL, 'SHOP', NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL,
         -3, 0, -5, 0, NULL, NULL)
       ) AS v(name, slug, owner_type, shop, sale_type, discount_type, discount_value, max_per_unit,
              scope_type, scope_code, min_unit_price, quota, per_buyer, budget, reserved, exhausted_days,
              home_sort, start_days, start_hours, end_days, end_hours, paused_days, pause_reason);

-- OP-01 Week: its two listings at about 15% under their asking price.
INSERT INTO price_sale_listing (sale_id, listing_id, sale_price)
SELECT (SELECT id FROM price_sale WHERE slug = 'op-01-week-tcg-corner'), l.id, round(l.price * 0.85)
  FROM listing l
 WHERE l.id IN (pg_temp.lid('tc-op01-120-nm'), pg_temp.lid('tc-op01-025-nm'));

-- Midnight Flash: the one card of every MTG listing it reaches is taken, so it reads SOLD_OUT.
INSERT INTO price_sale_listing (sale_id, listing_id, quota, sold_count)
SELECT s.id, l.id, 1, 1
  FROM price_sale s
  JOIN listing l ON l.status = 'ACTIVE'
  JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
  JOIN catalog_product cp ON cp.id = cv.catalog_product_id
 WHERE s.slug = 'midnight-flash'
   AND cp.game_id = (SELECT id FROM game WHERE code = 'MTG');
