-- Demo seed: a small amount of real trading between the two buyers and the two shops.
--
-- Orders are not inserted as finished rows. Each one is placed, paid, accepted,
-- shipped and completed as events, and full-seed/04_engine.sql plays them the way
-- the services do: stock is reserved and sold card by card, the payment goes into
-- escrow, completion releases it to the seller minus commission, and refunds and
-- payouts move the wallets through the ledger.
--
-- The planning helpers (plan_order, plan_part, plan_review) are the same as in
-- full-seed/06_orders.sql.

-- ---------- commission ----------
-- 5% on everything, 4% on One Piece (the more specific rule wins).
INSERT INTO commission_rule (scope, game_id, category_id, seller_profile_id, rate_percent, fixed_fee, min_fee, max_fee,
                             priority, effective_from, effective_to, is_active, created_by, created_at)
VALUES
    ('GLOBAL', NULL, NULL, NULL, 5.000, 0, NULL, NULL, 0, pg_temp.ago(130), NULL, true, pg_temp.uid('admin'), pg_temp.ago(130)),
    ('GAME', (SELECT id FROM game WHERE code = 'ONE_PIECE'), NULL, NULL, 4.000, 0, NULL, NULL, 0,
             pg_temp.ago(130), NULL, true, pg_temp.uid('admin'), pg_temp.ago(130));

-- ---------- planning helpers ----------

CREATE TEMP TABLE seed_paid (okey text PRIMARY KEY, paid_at timestamptz) ON COMMIT DROP;
CREATE TEMP TABLE seed_done (okey text, seller text, done_at timestamptz, PRIMARY KEY (okey, seller)) ON COMMIT DROP;

CREATE FUNCTION pg_temp.e(p_at timestamptz, p_kind text, p_okey text, p_seller text, p_extra jsonb DEFAULT '{}')
    RETURNS void LANGUAGE sql AS $$
    INSERT INTO seed_event (at, kind, a)
    VALUES (p_at, p_kind, jsonb_build_object('okey', p_okey, 'seller', p_seller) || p_extra)
$$;

-- Where a buyer wants a refund sent. Made-up account numbers.
CREATE FUNCTION pg_temp.buyer_bank(p_okey text) RETURNS jsonb
    LANGUAGE sql STABLE AS $$
    SELECT CASE d.buyer
        WHEN 'collector_mai'   THEN '{"bank":"ธนาคารกสิกรไทย","name":"ใหม่ ศรีสุข","account":"0451234567"}'
        WHEN 'deckbuilder_ton' THEN '{"bank":"ธนาคารไทยพาณิชย์","name":"ต้น วงศ์ษา","account":"1112223334"}'
        WHEN 'demo_buyer'      THEN '{"bank":"ธนาคารกรุงเทพ","name":"เดโม ผู้ซื้อ","account":"2223334445"}'
        WHEN 'ploy_collects'   THEN '{"bank":"ธนาคารกรุงไทย","name":"พลอย ใจงาม","account":"3334445556"}'
        ELSE                        '{"bank":"ธนาคารออมสิน","name":"ณัฐพงศ์ ทองดี","account":"0209876543"}'
    END::jsonb
    FROM seed_order_def d WHERE d.okey = p_okey
$$;

-- Placement and payment. Returns nothing; records when the money landed.
CREATE FUNCTION pg_temp.plan_order(p_okey text, p_buyer text, p_days numeric, p_pay text, p_note text DEFAULT NULL)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    t0  timestamptz := pg_temp.ago(p_days);
    m   interval := interval '1 minute';
    ref text := 'FT' || upper(substr(md5(p_okey), 1, 10));
BEGIN
    INSERT INTO seed_order_def VALUES (p_okey, p_buyer, p_note);
    PERFORM pg_temp.e(t0, 'place', p_okey, NULL);
    CASE p_pay
        WHEN 'bank' THEN
            PERFORM pg_temp.e(t0 + 6 * m, 'pay_create', p_okey, NULL);
            PERFORM pg_temp.e(t0 + 25 * m, 'pay_slip', p_okey, NULL, jsonb_build_object('ref', ref));
            PERFORM pg_temp.e(t0 + 120 * m, 'pay_verify', p_okey, NULL);
            INSERT INTO seed_paid VALUES (p_okey, t0 + 120 * m);
        WHEN 'mock' THEN
            PERFORM pg_temp.e(t0 + 3 * m, 'pay_mock', p_okey, NULL);
            INSERT INTO seed_paid VALUES (p_okey, t0 + 3 * m);
        WHEN 'slip_waiting' THEN
            PERFORM pg_temp.e(t0 + 5 * m, 'pay_create', p_okey, NULL);
            PERFORM pg_temp.e(t0 + 20 * m, 'pay_slip', p_okey, NULL, jsonb_build_object('ref', ref));
        WHEN 'unpaid' THEN
            PERFORM pg_temp.e(t0 + 3 * m, 'pay_create', p_okey, NULL);
        WHEN 'cancelled_unpaid' THEN
            PERFORM pg_temp.e(t0 + 3 * m, 'pay_create', p_okey, NULL);
            PERFORM pg_temp.e(t0 + 30 * m, 'cancel_buyer', p_okey, NULL,
                              '{"reason":"เปลี่ยนใจ จะสั่งพร้อมรายการอื่นทีหลัง"}');
        WHEN 'slip_rejected' THEN
            PERFORM pg_temp.e(t0 + 5 * m, 'pay_create', p_okey, NULL);
            PERFORM pg_temp.e(t0 + 20 * m, 'pay_slip', p_okey, NULL, jsonb_build_object('ref', ref));
            PERFORM pg_temp.e(t0 + 180 * m, 'pay_reject', p_okey, NULL,
                              '{"reason":"ยอดเงินในสลิปไม่ตรงกับยอดคำสั่งซื้อ"}');
            PERFORM pg_temp.e(t0 + 185 * m, 'expire', p_okey, NULL);
        WHEN 'expired' THEN
            PERFORM pg_temp.e(t0 + 3 * m, 'pay_create', p_okey, NULL);
            PERFORM pg_temp.e(t0 + 65 * m, 'expire', p_okey, NULL);
        ELSE
            RAISE EXCEPTION 'seed: unknown payment plan %', p_pay;
    END CASE;
END;
$$;

-- What happened to one seller's part after payment.
CREATE FUNCTION pg_temp.plan_part(p_okey text, p_seller text, p_scenario text, p_extra jsonb DEFAULT '{}')
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    p     timestamptz := (SELECT paid_at FROM seed_paid WHERE okey = p_okey);
    h     interval := interval '1 hour';
    d     interval := interval '1 day';
    ship  timestamptz;
    req   timestamptz;
    appr  timestamptz;
    back  timestamptz;
    recv  timestamptz;
    bank  jsonb := pg_temp.buyer_bank(p_okey);
    ref   text := 'RF' || upper(substr(md5(p_okey || p_seller), 1, 10));
    ret   jsonb;
BEGIN
    IF p IS NULL THEN
        RAISE EXCEPTION 'seed: % is not paid, so % cannot follow', p_okey, p_scenario;
    END IF;
    ship := p + 20 * h;
    req  := ship + 2 * d;
    appr := req + 12 * h;
    back := appr + d;
    recv := back + 2 * d;
    ret  := jsonb_build_object(
                'reason', coalesce(p_extra->>'reason', 'NOT_AS_DESCRIBED'),
                'description', coalesce(p_extra->>'description', 'สภาพการ์ดไม่ตรงกับที่ลงขายไว้'),
                'evidence', jsonb_build_array('returns/seed/' || p_okey || '-1.png', 'returns/seed/' || p_okey || '-2.png'));

    CASE p_scenario
        WHEN 'paid' THEN
            NULL;
        WHEN 'preparing' THEN
            PERFORM pg_temp.e(p + 3 * h, 'accept', p_okey, p_seller);
        WHEN 'shipped' THEN
            PERFORM pg_temp.e(p + 3 * h, 'accept', p_okey, p_seller);
            PERFORM pg_temp.e(ship, 'ship', p_okey, p_seller);
        WHEN 'delivered' THEN
            PERFORM pg_temp.e(p + 3 * h, 'accept', p_okey, p_seller);
            PERFORM pg_temp.e(ship, 'ship', p_okey, p_seller);
            PERFORM pg_temp.e(p + 3 * d, 'deliver', p_okey, p_seller);
        WHEN 'completed' THEN
            PERFORM pg_temp.e(p + 3 * h, 'accept', p_okey, p_seller);
            PERFORM pg_temp.e(ship, 'ship', p_okey, p_seller);
            PERFORM pg_temp.e(p + 3 * d, 'complete', p_okey, p_seller, '{"by_buyer":true}');
            INSERT INTO seed_done VALUES (p_okey, p_seller, p + 3 * d);
        WHEN 'completed_fast' THEN
            PERFORM pg_temp.e(p + 5 * h, 'ship', p_okey, p_seller, '{"proof":false}');
            PERFORM pg_temp.e(p + 2 * d, 'complete', p_okey, p_seller, '{"by_buyer":true}');
            INSERT INTO seed_done VALUES (p_okey, p_seller, p + 2 * d);
        WHEN 'completed_auto' THEN
            PERFORM pg_temp.e(p + d, 'ship', p_okey, p_seller);
            PERFORM pg_temp.e(p + 8 * d + interval '10 minutes', 'complete', p_okey, p_seller, '{"by_buyer":false}');
            INSERT INTO seed_done VALUES (p_okey, p_seller, p + 8 * d + interval '10 minutes');
        WHEN 'completed_delivered' THEN
            PERFORM pg_temp.e(p + 3 * h, 'accept', p_okey, p_seller);
            PERFORM pg_temp.e(ship, 'ship', p_okey, p_seller);
            PERFORM pg_temp.e(p + 60 * h, 'deliver', p_okey, p_seller);
            PERFORM pg_temp.e(p + 4 * d, 'complete', p_okey, p_seller, '{"by_buyer":true}');
            INSERT INTO seed_done VALUES (p_okey, p_seller, p + 4 * d);
        WHEN 'cancel_seller_refunded' THEN
            PERFORM pg_temp.e(p + 5 * h, 'cancel_seller', p_okey, p_seller, jsonb_build_object('reason', p_extra->>'cancel'));
            PERFORM pg_temp.e(p + d, 'refund_dest', p_okey, p_seller, bank);
            PERFORM pg_temp.e(p + 36 * h, 'refund_start', p_okey, p_seller);
            PERFORM pg_temp.e(p + 36 * h + interval '15 minutes', 'refund_done', p_okey, p_seller, jsonb_build_object('ref', ref));
        WHEN 'cancel_seller_mock' THEN
            PERFORM pg_temp.e(p + 5 * h, 'cancel_seller', p_okey, p_seller, jsonb_build_object('reason', p_extra->>'cancel'));
        WHEN 'cancel_seller_retry' THEN
            PERFORM pg_temp.e(p + 4 * h, 'cancel_seller', p_okey, p_seller, jsonb_build_object('reason', p_extra->>'cancel'));
            PERFORM pg_temp.e(p + d, 'refund_dest', p_okey, p_seller, bank);
            PERFORM pg_temp.e(p + 25 * h, 'refund_start', p_okey, p_seller);
            PERFORM pg_temp.e(p + 26 * h, 'refund_fail', p_okey, p_seller,
                              '{"reason":"เลขบัญชีปลายทางไม่ถูกต้อง ธนาคารตีกลับรายการ"}');
            PERFORM pg_temp.e(p + 2 * d, 'refund_start', p_okey, p_seller);
        WHEN 'cancel_buyer_refunded' THEN
            PERFORM pg_temp.e(p + 3 * h, 'cancel_buyer', p_okey, p_seller, '{"reason":"สั่งซ้ำโดยไม่ได้ตั้งใจ"}');
            PERFORM pg_temp.e(p + d, 'refund_dest', p_okey, p_seller, bank);
            PERFORM pg_temp.e(p + 36 * h, 'refund_start', p_okey, p_seller);
            PERFORM pg_temp.e(p + 36 * h + interval '15 minutes', 'refund_done', p_okey, p_seller, jsonb_build_object('ref', ref));
        WHEN 'return_requested', 'return_rejected', 'return_awaiting', 'return_in_transit', 'return_received',
             'return_refunded', 'return_partial' THEN
            PERFORM pg_temp.e(p + 3 * h, 'accept', p_okey, p_seller);
            PERFORM pg_temp.e(ship, 'ship', p_okey, p_seller);
            PERFORM pg_temp.e(req, 'return_request', p_okey, p_seller, ret);
            IF p_scenario = 'return_rejected' THEN
                PERFORM pg_temp.e(req + d, 'return_reject', p_okey, p_seller);
                PERFORM pg_temp.e(ship + 7 * d + interval '10 minutes', 'complete', p_okey, p_seller, '{"by_buyer":false}');
                INSERT INTO seed_done VALUES (p_okey, p_seller, ship + 7 * d + interval '10 minutes');
            ELSIF p_scenario = 'return_partial' THEN
                PERFORM pg_temp.e(appr, 'return_approve', p_okey, p_seller,
                                  jsonb_build_object('resolution', 'PARTIAL_REFUND', 'amount', p_extra->'amount', 'ship_back', false));
                PERFORM pg_temp.e(appr + d, 'refund_dest', p_okey, p_seller, bank);
                PERFORM pg_temp.e(appr + 36 * h, 'refund_start', p_okey, p_seller);
                PERFORM pg_temp.e(appr + 36 * h + interval '15 minutes', 'refund_done', p_okey, p_seller, jsonb_build_object('ref', ref));
                INSERT INTO seed_done VALUES (p_okey, p_seller, appr + 36 * h + interval '15 minutes');
            ELSIF p_scenario <> 'return_requested' THEN
                PERFORM pg_temp.e(appr, 'return_approve', p_okey, p_seller,
                                  '{"resolution":"FULL_REFUND","ship_back":true}');
                IF p_scenario <> 'return_awaiting' THEN
                    PERFORM pg_temp.e(back, 'return_ship', p_okey, p_seller,
                                      jsonb_build_object('tracking', 'EB' || lpad((abs(hashtext(p_okey)) % 1000000000)::text, 9, '0') || 'TH'));
                END IF;
                IF p_scenario IN ('return_received', 'return_refunded') THEN
                    PERFORM pg_temp.e(recv, 'return_receive', p_okey, p_seller,
                                      jsonb_build_object('restock', coalesce((p_extra->>'restock')::boolean, true)));
                    PERFORM pg_temp.e(recv + 12 * h, 'refund_dest', p_okey, p_seller, bank);
                END IF;
                IF p_scenario = 'return_refunded' THEN
                    PERFORM pg_temp.e(recv + d, 'refund_start', p_okey, p_seller);
                    PERFORM pg_temp.e(recv + d + interval '15 minutes', 'refund_done', p_okey, p_seller, jsonb_build_object('ref', ref));
                END IF;
            END IF;
        ELSE
            RAISE EXCEPTION 'seed: unknown scenario %', p_scenario;
    END CASE;
END;
$$;

CREATE FUNCTION pg_temp.plan_review(p_okey text, p_seller text, p_hours numeric, p_rating int, p_title text,
                                    p_comment text, p_reply text DEFAULT NULL, p_visible boolean DEFAULT true)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE done timestamptz := (SELECT done_at FROM seed_done WHERE okey = p_okey AND seller = p_seller);
BEGIN
    IF done IS NULL THEN
        RAISE EXCEPTION 'seed: % % never completes, so it cannot be reviewed', p_okey, p_seller;
    END IF;
    PERFORM pg_temp.e(done + make_interval(secs => (p_hours * 3600)::double precision), 'review', p_okey, p_seller,
                      jsonb_build_object('rating', p_rating, 'title', p_title, 'comment', p_comment,
                                         'reply', p_reply, 'visible', p_visible));
END;
$$;

-- ---------- the orders ----------
-- (order, seller, listing ref from 03_market.sql, quantity)
INSERT INTO seed_line_def (okey, seller, lref, qty) VALUES
    ('d01', 'demo_seller', 'ds-char199',   1), ('d01', 'demo_seller', 'ds-toploader', 1),
    ('d02', 'tcg_corner',  'tc-op09-118',  1), ('d02', 'tcg_corner',  'tc-op09-001',  2),
    ('d03', 'demo_seller', 'ds-151pack',   6), ('d03', 'tcg_corner',  'tc-op09pack',  4),
    ('d04', 'demo_seller', 'ds-eevee167',  1),
    ('d05', 'tcg_corner',  'tc-op10-118',  1),
    ('d06', 'demo_seller', 'ds-pikaex057', 2), ('d06', 'demo_seller', 'ds-sleeves',   1),
    ('d07', 'tcg_corner',  'tc-eb02-061',  1),
    ('d08', 'demo_seller', 'ds-luc179',    1),
    ('d09', 'demo_seller', 'ds-mew151',    2),
    ('d10', 'tcg_corner',  'tc-op11-067',  2),
    ('d11', 'demo_seller', 'ds-preetb',    1);

-- One buyer picked the shop's second tariff instead of its default.
INSERT INTO seed_sub_def (okey, seller, option_name) VALUES ('d06', 'demo_seller', 'Kerry Express');

-- When each order was placed and how it was paid:
--   bank         = bank transfer, slip uploaded, approved by the admin
--   mock         = the test payment method, paid at once
--   slip_waiting = slip uploaded, waiting in the admin's payment queue
SELECT pg_temp.plan_order('d01', 'collector_mai', 80,   'bank', 'ฝากใส่ท็อปโหลดเดอร์ให้ด้วยนะคะ');
SELECT pg_temp.plan_order('d02', 'demo_buyer',    65,   'bank');
SELECT pg_temp.plan_order('d03', 'demo_buyer',    45,   'bank');
SELECT pg_temp.plan_order('d04', 'collector_mai', 35,   'mock');
SELECT pg_temp.plan_order('d05', 'collector_mai', 28,   'bank');
SELECT pg_temp.plan_order('d06', 'demo_buyer',    20,   'bank');
SELECT pg_temp.plan_order('d07', 'collector_mai', 10,   'bank');
SELECT pg_temp.plan_order('d08', 'demo_buyer',    4,    'bank');
SELECT pg_temp.plan_order('d09', 'collector_mai', 1.5,  'bank');
SELECT pg_temp.plan_order('d10', 'demo_buyer',    1,    'bank');
SELECT pg_temp.plan_order('d11', 'demo_buyer',    0.15, 'slip_waiting');

-- What happened to each shop's part after payment.
SELECT pg_temp.plan_part('d01', 'demo_seller', 'completed');
SELECT pg_temp.plan_part('d02', 'tcg_corner',  'completed');
SELECT pg_temp.plan_part('d03', 'demo_seller', 'completed');
SELECT pg_temp.plan_part('d03', 'tcg_corner',  'completed');
SELECT pg_temp.plan_part('d04', 'demo_seller', 'completed_auto');
SELECT pg_temp.plan_part('d05', 'tcg_corner',  'cancel_seller_refunded',
                         '{"cancel":"ตรวจสภาพก่อนส่งแล้วพบรอยที่ผิวการ์ด ไม่ตรงกับที่ลงขาย ขออภัยครับ"}');
SELECT pg_temp.plan_part('d06', 'demo_seller', 'completed');
SELECT pg_temp.plan_part('d07', 'tcg_corner',  'completed_delivered');
SELECT pg_temp.plan_part('d08', 'demo_seller', 'shipped');      -- buyer can confirm receipt
SELECT pg_temp.plan_part('d09', 'demo_seller', 'paid');         -- seller has to accept and ship
SELECT pg_temp.plan_part('d10', 'tcg_corner',  'preparing');    -- accepted, seller has to ship

-- ---------- reviews ----------
SELECT pg_temp.plan_review('d01', 'demo_seller', 5,  5, 'การ์ดสวยมาก', 'ลิซาร์ดอน SIR สภาพดีตามรูป ใส่ท็อปโหลดเดอร์มาให้ด้วย ส่งไวค่ะ', 'ขอบคุณที่อุดหนุนครับ');
SELECT pg_temp.plan_review('d02', 'tcg_corner',  3,  5, 'ของแท้ ส่งไว', 'โรเจอร์ SEC สวยมาก แพ็กมาแน่นหนา');
SELECT pg_temp.plan_review('d03', 'demo_seller', 20, 4, 'ซองครบ', 'ซอง 151 ซีลครบทุกซอง แต่กล่องพัสดุบุบนิดหน่อย', 'ขออภัยครับ รอบหน้าจะเสริมกันกระแทกให้ครับ');
SELECT pg_temp.plan_review('d03', 'tcg_corner',  8,  5, 'ประทับใจ', 'ซอง OP-09 ส่งเร็ว ได้ของภายในสองวัน');
SELECT pg_temp.plan_review('d06', 'demo_seller', 4,  5, 'ร้านประจำ', 'สั่งกี่ครั้งก็ไม่ผิดหวัง');

-- ---------- payouts ----------
-- demo_seller was paid out once; tcg_corner's request waits for the admin.
SELECT pg_temp.ev(v.days, 0, v.kind, v.args::jsonb)
  FROM (VALUES
    (38,  'payout_request', '{"pkey":"demo-pd1","seller":"demo_seller","share":100}'),
    (37.5,'payout',         '{"pkey":"demo-pd1","to":"APPROVED"}'),
    (37,  'payout',         '{"pkey":"demo-pd1","to":"PROCESSING"}'),
    (36.5,'payout',         '{"pkey":"demo-pd1","to":"PAID","ref":"BAY0033344455"}'),
    (1,   'payout_request', '{"pkey":"demo-pt1","seller":"tcg_corner","share":80}')
  ) AS v(days, kind, args);
