-- Discount codes [TCG-575]: one of each kind QA needs. Codes the e2e run creates
-- itself (PEGASUS10, MINMIN50) are left out so it can create them. The only order
-- with a code is the one whose payment expired, so its redemption is RELEASED and
-- used_count stays 0 everywhere; a used-up code is made by the e2e run (scenario 4).

INSERT INTO promotion (code, name, description, owner_type, seller_profile_id, discount_type, discount_value,
                       max_discount, min_spend, scope_type, scope_ids, visibility, new_buyer_only,
                       usage_limit_total, usage_limit_per_user, claimable, claim_required, claim_limit,
                       featured_on_home, home_sort, starts_at, ends_at, paused_at, paused_by, pause_reason,
                       created_by, created_at, updated_at)
SELECT v.code, v.name, v.description, v.owner_type,
       CASE WHEN v.shop IS NOT NULL THEN pg_temp.spid(v.shop) END,
       v.discount_type, v.discount_value, v.max_discount, v.min_spend,
       CASE WHEN v.game IS NULL THEN 'ALL' ELSE 'GAME' END,
       CASE WHEN v.game IS NULL THEN '{}'::bigint[] ELSE ARRAY[(SELECT g.id FROM game g WHERE g.code = v.game)]::bigint[] END,
       v.visibility, v.new_buyer_only, v.usage_limit_total, v.per_user, v.claimable, v.claim_required,
       v.claim_limit, v.home_sort IS NOT NULL, v.home_sort,
       pg_temp.ago(v.starts_days), pg_temp.ago(v.ends_days),
       CASE WHEN v.paused_days IS NOT NULL THEN pg_temp.ago(v.paused_days) END,
       CASE WHEN v.paused_days IS NOT NULL THEN pg_temp.uid(v.paused_by) END,
       v.pause_reason,
       pg_temp.uid(coalesce(v.shop, 'admin')),
       pg_temp.ago(greatest(v.starts_days, 0) + 1), pg_temp.ago(greatest(v.starts_days, 0) + 1)
  FROM (VALUES
        -- Pegasus codes. Featured ones are claimable and PUBLIC, as the table requires.
        ('WELCOME100', 'ส่วนลดต้อนรับ ฿100', 'ลด ฿100 เมื่อซื้อครบ ฿1,000', 'PLATFORM', NULL::text,
         'FIXED', 100::numeric, NULL::numeric, 1000::numeric, NULL::text, 'PUBLIC', false, NULL::int, 1,
         true, false, 500, 1, 10::numeric, -30::numeric, NULL::numeric, NULL::text, NULL::text),
        ('PEGASUS15', 'ลด 15% สูงสุด ฿300', 'ลด 15% เมื่อซื้อครบ ฿1,500 สูงสุด ฿300', 'PLATFORM', NULL,
         'PERCENT', 15, 300, 1500, NULL, 'PUBLIC', false, 1000, 1,
         true, true, NULL, 2, 5, -25, NULL, NULL, NULL),
        ('FREESHIP', 'ส่งฟรี สูงสุด ฿60 ต่อร้าน', 'ลดค่าส่งได้ร้านละไม่เกิน ฿60 เมื่อซื้อครบ ฿500', 'PLATFORM', NULL,
         'FREE_SHIPPING', 0, 60, 500, NULL, 'PUBLIC', false, NULL, 2,
         true, false, NULL, 3, 7, -21, NULL, NULL, NULL),
        -- claim_limit 1, already taken by demo_buyer: everyone else sees "หมดแล้ว".
        ('FLASH30', 'แจก 1 ใบแรก ลด ฿30', 'โค้ดจำนวนจำกัด ใบเดียว', 'PLATFORM', NULL,
         'FIXED', 30, NULL, 0, NULL, 'PUBLIC', false, NULL, 1,
         true, false, 1, 4, 2, -5, NULL, NULL, NULL),
        ('NEWBIE50', 'ผู้ซื้อใหม่ลด ฿50', 'เฉพาะบัญชีที่ยังไม่เคยมีคำสั่งซื้อที่ชำระแล้ว', 'PLATFORM', NULL,
         'FIXED', 50, NULL, 300, NULL, 'PUBLIC', true, NULL, 1,
         false, false, NULL, NULL, 20, -60, NULL, NULL, NULL),
        ('POKEMON10', 'โปเกมอนลด 10%', 'ลด 10% เฉพาะสินค้า Pokemon TCG สูงสุด ฿200', 'PLATFORM', NULL,
         'PERCENT', 10, 200, 0, 'POKEMON', 'PUBLIC', false, NULL, 2,
         false, false, NULL, NULL, 3, -27, NULL, NULL, NULL),
        -- PRIVATE and claim_required: typing it without collecting it first gives PROMO_NOT_CLAIMED.
        ('VIP200', 'ลูกค้า VIP ลด ฿200', 'ต้องเก็บโค้ดก่อนใช้', 'PLATFORM', NULL,
         'FIXED', 200, NULL, 3000, NULL, 'PRIVATE', false, 50, 1,
         true, true, 50, NULL, 4, -26, NULL, NULL, NULL),
        ('SUMMER25', 'ซัมเมอร์ลด 25%', 'หยุดไว้ระหว่างตรวจงบ', 'PLATFORM', NULL,
         'PERCENT', 25, 250, 1000, NULL, 'PUBLIC', false, 200, 1,
         false, false, NULL, NULL, 6, -14, 1, 'admin', NULL),
        ('SOON10', 'เปิดสัปดาห์หน้า ลด ฿10', 'ยังไม่เริ่ม', 'PLATFORM', NULL,
         'FIXED', 10, NULL, 0, NULL, 'PUBLIC', false, NULL, 1,
         false, false, NULL, NULL, -5, -35, NULL, NULL, NULL),
        ('OLDDEAL20', 'โปรเดือนที่แล้ว ลด 20%', 'หมดอายุแล้ว', 'PLATFORM', NULL,
         'PERCENT', 20, 200, 500, NULL, 'PUBLIC', false, NULL, 1,
         false, false, NULL, NULL, 40, 3, NULL, NULL, NULL),
        -- Shop codes (both shops are VERIFIED).
        ('KINGDOM50', 'Card Kingdom ลด ฿50', 'ลด ฿50 เมื่อซื้อในร้านครบ ฿500', 'SELLER', 'card_kingdom_th',
         'FIXED', 50, NULL, 500, NULL, 'PUBLIC', false, 300, 1,
         true, false, 200, NULL, 8, -22, NULL, NULL, NULL),
        ('KINGDOM10P', 'Card Kingdom ลด 10% (ลับ)', 'แจกในกลุ่ม Facebook ของร้าน', 'SELLER', 'card_kingdom_th',
         'PERCENT', 10, 150, 0, NULL, 'PRIVATE', false, NULL, 1,
         false, false, NULL, NULL, 8, -22, NULL, NULL, NULL),
        -- Used on the order whose payment expired (below), so it started before that order.
        ('CORNER5', 'TCG Corner ลด 5%', 'ลด 5% ทุกสินค้าในร้าน', 'SELLER', 'tcg_corner',
         'PERCENT', 5, 100, 0, NULL, 'PUBLIC', false, NULL, 2,
         false, false, NULL, NULL, 21, -21, NULL, NULL, NULL),
        -- Paused by Pegasus: the shop sees the reason and cannot resume it.
        ('CORNER100', 'TCG Corner ลด ฿100', 'ลด ฿100 เมื่อซื้อครบ ฿800', 'SELLER', 'tcg_corner',
         'FIXED', 100, NULL, 800, NULL, 'PUBLIC', false, 100, 1,
         true, false, NULL, NULL, 9, -21, 2, 'admin', 'มูลค่าส่วนลดไม่ตรงกับที่ประกาศในหน้าร้าน')
       ) AS v(code, name, description, owner_type, shop, discount_type, discount_value, max_discount, min_spend,
              game, visibility, new_buyer_only, usage_limit_total, per_user, claimable, claim_required,
              claim_limit, home_sort, starts_days, ends_days, paused_days, paused_by, pause_reason);

-- Collected codes ("My coupons"). claimed_count is the number of rows here.
INSERT INTO promotion_claim (promotion_id, user_id, source, claimed_at)
SELECT p.id, pg_temp.uid(v.username), v.source, pg_temp.ago(v.days)
  FROM (VALUES ('WELCOME100', 'collector_mai', 'HOME',         4),
               ('WELCOME100', 'ploy_collects', 'HOME',         2),
               ('PEGASUS15',  'ploy_collects', 'HOME',         1),
               ('FLASH30',    'demo_buyer',    'HOME',         1.5),
               ('VIP200',     'demo_buyer',    'WALLET_INPUT', 3),
               ('KINGDOM50',  'collector_mai', 'STORE',        2),
               ('KINGDOM50',  'demo_buyer',    'STORE',        1)
       ) AS v(code, username, source, days)
  JOIN promotion p ON p.code = v.code;

UPDATE promotion p
   SET claimed_count = (SELECT count(*) FROM promotion_claim c WHERE c.promotion_id = p.id);

-- The order whose payment expired used CORNER5 (5% of its TCG Corner part). The
-- timeout released the code, so the buyer can use it again. Totals and the
-- expired payment are lowered by the discount, as checkout would have done.
DO $$
DECLARE
    o   record;
    off numeric(14,2);
BEGIN
    SELECT so.id AS seller_order_id, so.sales_order_id, so.items_subtotal, s.buyer_id, so.created_at,
           p.id AS payment_id, s.updated_at AS expired_at
      INTO STRICT o
      FROM payment p
      JOIN sales_order s ON s.id = p.sales_order_id
      JOIN seller_order so ON so.sales_order_id = s.id
     WHERE p.status = 'EXPIRED' AND so.seller_profile_id = pg_temp.spid('tcg_corner');
    off := round(o.items_subtotal * 0.05, 2);
    IF (SELECT count(*) FROM order_item WHERE seller_order_id = o.seller_order_id) <> 1 THEN
        RAISE EXCEPTION 'seed: the expired order should have one line; spread the discount over its lines';
    END IF;

    UPDATE order_item SET discount_allocated = off, seller_discount_allocated = off
     WHERE seller_order_id = o.seller_order_id;
    -- A shop code lowers the commission base too: subtotal minus the shop discount.
    UPDATE seller_order
       SET seller_discount_amount = off, discount_amount = off, grand_total = grand_total - off,
           commission_amount = round((items_subtotal - off) * commission_rate_percent / 100, 2),
           seller_net_amount = grand_total - off
                               - round((items_subtotal - off) * commission_rate_percent / 100, 2)
     WHERE id = o.seller_order_id;
    UPDATE sales_order SET discount_total = off, grand_total = grand_total - off WHERE id = o.sales_order_id;
    UPDATE payment SET amount = amount - off WHERE id = o.payment_id;

    INSERT INTO promotion_redemption (promotion_id, buyer_id, sales_order_id, seller_order_id, amount, status,
                                      created_at, updated_at)
    SELECT p.id, o.buyer_id, o.sales_order_id, o.seller_order_id, off, 'RELEASED', o.created_at, o.expired_at
      FROM promotion p WHERE p.code = 'CORNER5';
END;
$$;

-- Figma PRM-B1/C1 basket fixture for collector_mai
DO $$
BEGIN
    UPDATE listing SET price = 450 WHERE id = pg_temp.lid('tc-char006-nm');
    UPDATE listing SET price = 380 WHERE id = pg_temp.lid('tc-char006-lp');
    UPDATE listing SET price = 2400 WHERE id = pg_temp.lid('po-megbox');

    DELETE FROM cart_item WHERE cart_id = (SELECT id FROM cart WHERE user_id = pg_temp.uid('collector_mai'));
    INSERT INTO cart_item (cart_id, listing_id, quantity, unit_price_at_add, added_at, updated_at)
    VALUES ((SELECT id FROM cart WHERE user_id = pg_temp.uid('collector_mai')), pg_temp.lid('tc-char006-nm'), 1, 450, now(), now()),
           ((SELECT id FROM cart WHERE user_id = pg_temp.uid('collector_mai')), pg_temp.lid('tc-char006-lp'), 1, 380, now(), now()),
           ((SELECT id FROM cart WHERE user_id = pg_temp.uid('collector_mai')), pg_temp.lid('po-megbox'), 1, 2400, now(), now());
END;
$$;
