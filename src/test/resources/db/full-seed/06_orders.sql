-- Orders, money and after-sales, as events on the same timeline as the market.
--
-- Each order says when it was placed, how it was paid and what then happened to
-- each seller's part of it. The scenarios below expand into the individual steps
-- (accept, ship, deliver, confirm, cancel, refund transfer, return...) with
-- realistic gaps, and 04_engine.sql plays them in time order.

-- ---------- commission rules ----------
-- Most specific wins: SELLER > CATEGORY > GAME > GLOBAL. One expired rule and one
-- switched-off rule show those states without affecting any order.
INSERT INTO commission_rule (scope, game_id, category_id, seller_profile_id, rate_percent, fixed_fee, min_fee, max_fee,
                             priority, effective_from, effective_to, is_active, created_by, created_at)
VALUES
    ('GLOBAL',   NULL, NULL, NULL, 7.000, 0, NULL, NULL, 0, pg_temp.ago(420), pg_temp.ago(300), true, pg_temp.uid('admin'), pg_temp.ago(420)),
    ('GLOBAL',   NULL, NULL, NULL, 5.000, 0, NULL, NULL, 0, pg_temp.ago(300), NULL, true, pg_temp.uid('admin'), pg_temp.ago(300)),
    ('GAME',     (SELECT id FROM game WHERE code = 'ONE_PIECE'), NULL, NULL, 4.000, 0, NULL, NULL, 0,
                 pg_temp.ago(300), NULL, true, pg_temp.uid('admin'), pg_temp.ago(300)),
    ('GAME',     (SELECT id FROM game WHERE code = 'POKEMON'), NULL, NULL, 6.000, 0, NULL, NULL, 0,
                 pg_temp.ago(200), NULL, false, pg_temp.uid('admin'), pg_temp.ago(200)),
    ('CATEGORY', NULL, (SELECT id FROM catalog_category WHERE code = 'BOOSTER_BOXES' AND game_id IS NULL), NULL,
                 3.000, 0, 30.00, 250.00, 0, pg_temp.ago(300), NULL, true, pg_temp.uid('admin'), pg_temp.ago(300)),
    ('SELLER',   NULL, NULL, pg_temp.spid('pegasus_official'), 0.000, 0, NULL, NULL, 10,
                 pg_temp.ago(300), NULL, true, pg_temp.uid('admin'), pg_temp.ago(300)),
    ('SELLER',   NULL, NULL, pg_temp.spid('mint_vault'), 4.000, 0, NULL, NULL, 0,
                 pg_temp.ago(30), NULL, true, pg_temp.uid('admin'), pg_temp.ago(31));

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
-- seed_line_def: (order, seller, listing ref from 05_market.sql, quantity)
INSERT INTO seed_line_def (okey, seller, lref, qty) VALUES
    ('o64', 'kanto_cards',      'kt-pika025-nm', 4), ('o64', 'kanto_cards', 'kt-char006-nm', 1),
    ('o01', 'card_kingdom_th',  'ck-alak201-nm', 1), ('o01', 'card_kingdom_th', 'ck-erika203-nm', 1),
    ('o02', 'tcg_corner',       'tc-op01-025-nm', 2), ('o02', 'tcg_corner', 'tc-op01-120-nm', 1),
    ('o03', 'demo_seller',      'ds-sol-nm', 2), ('o03', 'demo_seller', 'ds-exodia', 1),
    ('o04', 'pegasus_official', 'po-151pack', 6),
    ('o05', 'mint_vault',       'mv-mew205-nm', 1),
    ('o06', 'pegasus_official', 'po-151etb', 1), ('o06', 'pegasus_official', 'po-up-toploader', 2),
    ('o07', 'demo_seller',      'ds-bewd-qcsr', 1), ('o07', 'demo_seller', 'ds-reborn-qcsr', 1),
    ('o08', 'card_kingdom_th',  'ck-char006-nm', 2),
    ('o10', 'card_kingdom_th',  'ck-pika238-nm', 1),
    ('o11', 'pegasus_official', 'po-sspbox', 1),
    ('o13', 'grand_line_cards', 'gl-gojo-nm', 2), ('o13', 'grand_line_cards', 'gl-yuji-nm', 1),
    ('o14', 'mint_vault',       'mv-char199-psa10', 1),
    ('o09', 'tcg_corner',       'tc-op09-004-nm', 1), ('o09', 'tcg_corner', 'tc-op09-001-nm', 2),
    ('o15', 'pegasus_official', 'po-op09box', 1),
    ('o12', 'tcg_corner',       'tc-op09-118-nm', 1), ('o12', 'grand_line_cards', 'gl-op09-119-nm', 1),
    ('o17', 'demo_seller',      'ds-cloud-nm', 1), ('o17', 'demo_seller', 'ds-buster-nm', 1),
    ('o18', 'pegasus_official', 'po-preetb', 2),
    ('o16', 'card_kingdom_th',  'ck-eevee167-nm', 1),
    ('o20', 'mint_vault',       'mv-espeon155-nm', 1),
    ('o21', 'grand_line_cards', 'gl-tanjiro-nm', 2), ('o21', 'grand_line_cards', 'gl-nezuko-nm', 2),
    ('o22', 'pegasus_official', 'po-st21', 2), ('o22', 'tcg_corner', 'tc-st21-014-nm', 2),
    ('o19', 'tcg_corner',       'tc-op10-118-nm', 1),
    ('o23', 'card_kingdom_th',  'ck-umb161-nm', 1),
    ('o24', 'grand_line_cards', 'gl-tai-nm', 1), ('o24', 'grand_line_cards', 'gl-davis-nm', 2),
    ('o25', 'card_kingdom_th',  'ck-syl156-nm', 1),
    ('o26', 'demo_seller',      'ds-riddler-nm', 1),
    ('o65', 'banned_trader',    'bt-umb161-nm', 1),
    ('o27', 'tcg_corner',       'tc-op10-071-nm', 2), ('o27', 'tcg_corner', 'tc-op10-022-nm', 4),
    ('o28', 'pegasus_official', 'po-drietb', 1),
    ('o31', 'pegasus_official', 'po-alinbox', 1), ('o31', 'pegasus_official', 'po-alinpack', 6),
    ('o29', 'card_kingdom_th',  'ck-mewtwo231-nm', 1),
    ('o30', 'tcg_corner',       'tc-ho230-nm', 1),
    ('o32', 'grand_line_cards', 'gl-op11-040-nm', 1), ('o32', 'tcg_corner', 'tc-op11-067-nm', 2),
    ('o33', 'mint_vault',       'mv-latias239-nm', 1),
    ('o34', 'pegasus_official', 'po-fincol', 3),
    ('o63', 'pegasus_official', 'po-op10box', 1),
    ('o35', 'grand_line_cards', 'gl-omni-nm', 1), ('o35', 'grand_line_cards', 'gl-kyoko-nm', 1),
    ('o36', 'pegasus_official', 'po-cmmdeck', 1),
    ('o37', 'demo_seller',      'ds-dbl-nm', 1),
    ('o38', 'pegasus_official', 'po-151upc', 1),
    ('o39', 'tcg_corner',       'tc-gar232-nm', 1),
    ('o40', 'pegasus_official', 'po-pretin', 3),
    ('o41', 'grand_line_cards', 'gl-goku119-nm', 1),
    ('o42', 'demo_seller',      'ds-tezz-nm', 1),
    ('o43', 'card_kingdom_th',  'ck-luc179-nm', 1), ('o43', 'card_kingdom_th', 'ck-gard178-nm', 1),
    ('o61', 'grand_line_cards', 'gl-frieza-nm', 1),
    ('o44', 'card_kingdom_th',  'ck-pikaex057-nm', 2),
    ('o45', 'pegasus_official', 'po-megpack', 10),
    ('o49', 'tcg_corner',       'tc-zap202-nm', 1),
    ('o60', 'tcg_corner',       'tc-op09pack', 4),
    ('o46', 'tcg_corner',       'tc-char006-nm', 2),
    ('o47', 'mint_vault',       'mv-kang182-nm', 1),
    ('o48', 'grand_line_cards', 'gl-gokugt-nm', 1),
    ('o56', 'pegasus_official', 'po-fb07pack', 6), ('o56', 'grand_line_cards', 'gl-fb07box', 1),
    ('o51', 'pegasus_official', 'po-megbox', 1),
    ('o50', 'grand_line_cards', 'gl-gohan-nm', 1), ('o50', 'grand_line_cards', 'gl-syn-nm', 2),
    ('o59', 'card_kingdom_th',  'ck-151pack', 2),
    ('o52', 'card_kingdom_th',  'ck-gard178-nm', 1),
    ('o53', 'pegasus_official', 'po-up-mat', 1), ('o53', 'card_kingdom_th', 'ck-151pack', 3),
    ('o54', 'demo_seller',      'ds-vivi-nm', 1),
    ('o55', 'grand_line_cards', 'gl-sayo-nm', 1),
    ('o62', 'card_kingdom_th',  'ck-char199-nm', 1),
    ('o57', 'pegasus_official', 'po-fb07box', 1),
    ('o58', 'demo_seller',      'ds-niv-nm', 1);

-- A buyer who picked the second tariff instead of the seller's default.
INSERT INTO seed_sub_def (okey, seller, option_name) VALUES
    ('o13', 'grand_line_cards', 'Kerry Express'),
    ('o27', 'tcg_corner', 'ไปรษณีย์ไทย EMS'),
    ('o44', 'card_kingdom_th', 'Flash Express');

SELECT pg_temp.plan_order('o64', 'collector_mai',   125,  'bank');
SELECT pg_temp.plan_order('o01', 'collector_mai',   105,  'bank', 'ฝากใส่ท็อปโหลดเดอร์ให้ด้วยนะคะ');
SELECT pg_temp.plan_order('o02', 'deckbuilder_ton', 102,  'bank');
SELECT pg_temp.plan_order('o03', 'demo_buyer',      100,  'mock');
SELECT pg_temp.plan_order('o04', 'ploy_collects',   98,   'bank');
SELECT pg_temp.plan_order('o05', 'nattapong_tcg',   96,   'bank');
SELECT pg_temp.plan_order('o06', 'collector_mai',   94,   'bank');
SELECT pg_temp.plan_order('o07', 'deckbuilder_ton', 91,   'bank');
SELECT pg_temp.plan_order('o08', 'suspended_user',  90,   'bank');
SELECT pg_temp.plan_order('o10', 'collector_mai',   85,   'bank', 'ของขวัญวันเกิด ห่อกันกระแทกดี ๆ นะคะ');
SELECT pg_temp.plan_order('o11', 'ploy_collects',   84,   'mock');
SELECT pg_temp.plan_order('o13', 'nattapong_tcg',   80,   'bank');
SELECT pg_temp.plan_order('o14', 'collector_mai',   78,   'bank');
SELECT pg_temp.plan_order('o09', 'demo_buyer',      77,   'bank');
SELECT pg_temp.plan_order('o15', 'demo_buyer',      75,   'bank');
SELECT pg_temp.plan_order('o12', 'deckbuilder_ton', 74,   'bank');
SELECT pg_temp.plan_order('o17', 'deckbuilder_ton', 70,   'bank');
SELECT pg_temp.plan_order('o18', 'collector_mai',   68,   'bank');
SELECT pg_temp.plan_order('o16', 'ploy_collects',   65,   'bank');
SELECT pg_temp.plan_order('o20', 'ploy_collects',   64,   'bank');
SELECT pg_temp.plan_order('o21', 'demo_buyer',      62,   'mock');
SELECT pg_temp.plan_order('o22', 'deckbuilder_ton', 60,   'bank');
SELECT pg_temp.plan_order('o19', 'nattapong_tcg',   59,   'bank');
SELECT pg_temp.plan_order('o23', 'collector_mai',   58,   'bank');
SELECT pg_temp.plan_order('o24', 'nattapong_tcg',   55,   'bank');
SELECT pg_temp.plan_order('o25', 'ploy_collects',   54,   'bank');
SELECT pg_temp.plan_order('o26', 'demo_buyer',      52,   'bank');
SELECT pg_temp.plan_order('o65', 'nattapong_tcg',   50,   'bank');
SELECT pg_temp.plan_order('o27', 'deckbuilder_ton', 49.5, 'bank');
SELECT pg_temp.plan_order('o28', 'collector_mai',   43,   'bank');
SELECT pg_temp.plan_order('o31', 'demo_buyer',      42.5, 'bank');
SELECT pg_temp.plan_order('o29', 'nattapong_tcg',   42,   'bank');
SELECT pg_temp.plan_order('o30', 'ploy_collects',   41,   'bank');
SELECT pg_temp.plan_order('o33', 'collector_mai',   38,   'mock');
SELECT pg_temp.plan_order('o32', 'deckbuilder_ton', 37,   'bank');
SELECT pg_temp.plan_order('o34', 'nattapong_tcg',   36,   'bank');
SELECT pg_temp.plan_order('o63', 'tcg_corner',      35,   'bank');
SELECT pg_temp.plan_order('o35', 'ploy_collects',   32,   'bank');
SELECT pg_temp.plan_order('o36', 'demo_buyer',      30,   'bank');
SELECT pg_temp.plan_order('o37', 'deckbuilder_ton', 28,   'bank');
SELECT pg_temp.plan_order('o38', 'collector_mai',   26,   'bank');
SELECT pg_temp.plan_order('o39', 'nattapong_tcg',   24,   'bank');
SELECT pg_temp.plan_order('o40', 'ploy_collects',   22,   'bank');
SELECT pg_temp.plan_order('o41', 'demo_buyer',      20,   'bank');
SELECT pg_temp.plan_order('o42', 'deckbuilder_ton', 18,   'bank');
SELECT pg_temp.plan_order('o43', 'collector_mai',   16,   'bank');
SELECT pg_temp.plan_order('o61', 'deckbuilder_ton', 15,   'cancelled_unpaid');
SELECT pg_temp.plan_order('o44', 'nattapong_tcg',   14,   'bank');
SELECT pg_temp.plan_order('o45', 'ploy_collects',   13,   'mock');
SELECT pg_temp.plan_order('o49', 'nattapong_tcg',   12,   'bank');
SELECT pg_temp.plan_order('o60', 'ploy_collects',   11,   'expired');
SELECT pg_temp.plan_order('o46', 'demo_buyer',      10,   'bank');
SELECT pg_temp.plan_order('o47', 'collector_mai',   9,    'bank');
SELECT pg_temp.plan_order('o48', 'ploy_collects',   8,    'bank');
SELECT pg_temp.plan_order('o56', 'deckbuilder_ton', 6,    'bank');
SELECT pg_temp.plan_order('o51', 'demo_buyer',      5.8,  'bank');
SELECT pg_temp.plan_order('o50', 'deckbuilder_ton', 5,    'bank');
SELECT pg_temp.plan_order('o59', 'nattapong_tcg',   4.5,  'slip_rejected');
SELECT pg_temp.plan_order('o52', 'collector_mai',   4,    'bank');
SELECT pg_temp.plan_order('o53', 'ploy_collects',   3,    'bank');
SELECT pg_temp.plan_order('o54', 'nattapong_tcg',   2,    'bank');
SELECT pg_temp.plan_order('o55', 'demo_buyer',      1.5,  'mock');
SELECT pg_temp.plan_order('o62', 'collector_mai',   0.8,  'bank');
SELECT pg_temp.plan_order('o57', 'collector_mai',   0.6,  'slip_waiting');
-- Placed a few minutes before the seed ran and never paid: the payment-timeout
-- job cancels it an hour after it was placed, as it would any unpaid order.
SELECT pg_temp.plan_order('o58', 'demo_buyer',      0.01, 'unpaid');

SELECT pg_temp.plan_part('o64', 'kanto_cards',      'completed');
SELECT pg_temp.plan_part('o01', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o02', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o03', 'demo_seller',      'completed_auto');
SELECT pg_temp.plan_part('o04', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o05', 'mint_vault',       'completed_delivered');
SELECT pg_temp.plan_part('o06', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o07', 'demo_seller',      'completed');
SELECT pg_temp.plan_part('o08', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o10', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o11', 'pegasus_official', 'completed_auto');
SELECT pg_temp.plan_part('o13', 'grand_line_cards', 'completed');
SELECT pg_temp.plan_part('o14', 'mint_vault',       'completed');
SELECT pg_temp.plan_part('o09', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o15', 'pegasus_official', 'completed_fast');
SELECT pg_temp.plan_part('o12', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o12', 'grand_line_cards', 'completed_delivered');
SELECT pg_temp.plan_part('o17', 'demo_seller',      'completed');
SELECT pg_temp.plan_part('o18', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o16', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o20', 'mint_vault',       'completed');
SELECT pg_temp.plan_part('o21', 'grand_line_cards', 'completed_auto');
SELECT pg_temp.plan_part('o22', 'pegasus_official', 'completed_fast');
SELECT pg_temp.plan_part('o22', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o19', 'tcg_corner',       'return_rejected', '{"reason":"DAMAGED","description":"มุมการ์ดยับตั้งแต่แกะพัสดุ"}');
SELECT pg_temp.plan_part('o23', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o24', 'grand_line_cards', 'completed');
SELECT pg_temp.plan_part('o25', 'card_kingdom_th',  'return_partial', '{"reason":"NOT_AS_DESCRIBED","description":"มีรอยขาวที่ขอบด้านหลังแต่ประกาศว่า NM","amount":500}');
SELECT pg_temp.plan_part('o26', 'demo_seller',      'completed');
SELECT pg_temp.plan_part('o65', 'banned_trader',    'return_refunded', '{"reason":"COUNTERFEIT","description":"ฟอยล์และตัวอักษรต่างจากของแท้ สงสัยเป็นการ์ดปลอม","restock":false}');
SELECT pg_temp.plan_part('o27', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o28', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o31', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o29', 'card_kingdom_th',  'return_refunded', '{"reason":"WRONG_ITEM","description":"ได้รับการ์ดคนละใบกับที่สั่ง"}');
SELECT pg_temp.plan_part('o30', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o33', 'mint_vault',       'cancel_seller_mock', '{"cancel":"การ์ดเสียหายระหว่างจัดเก็บ ขออภัยครับ"}');
SELECT pg_temp.plan_part('o32', 'grand_line_cards', 'completed');
SELECT pg_temp.plan_part('o32', 'tcg_corner',       'cancel_seller_refunded', '{"cancel":"ของหมดสต็อก ขอยกเลิกและคืนเงินครับ"}');
SELECT pg_temp.plan_part('o34', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o63', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o35', 'grand_line_cards', 'completed');
SELECT pg_temp.plan_part('o36', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o37', 'demo_seller',      'cancel_buyer_refunded');
SELECT pg_temp.plan_part('o38', 'pegasus_official', 'completed');
SELECT pg_temp.plan_part('o39', 'tcg_corner',       'completed');
SELECT pg_temp.plan_part('o40', 'pegasus_official', 'completed_auto');
SELECT pg_temp.plan_part('o41', 'grand_line_cards', 'completed');
SELECT pg_temp.plan_part('o42', 'demo_seller',      'cancel_seller_retry', '{"cancel":"การ์ดมีตำหนิที่ไม่ได้ระบุไว้ ขอยกเลิกครับ"}');
SELECT pg_temp.plan_part('o43', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o44', 'card_kingdom_th',  'completed');
SELECT pg_temp.plan_part('o45', 'pegasus_official', 'completed_fast');
SELECT pg_temp.plan_part('o49', 'tcg_corner',       'return_received', '{"reason":"DAMAGED","description":"การ์ดงอระหว่างขนส่ง"}');
SELECT pg_temp.plan_part('o46', 'tcg_corner',       'return_requested', '{"reason":"NOT_AS_DESCRIBED","description":"ใบหนึ่งมีรอยขีดข่วนที่ผิวโฮโล"}');
SELECT pg_temp.plan_part('o47', 'mint_vault',       'return_awaiting', '{"reason":"DAMAGED","description":"เคสด้านนอกแตก การ์ดด้านในมีรอยกด"}');
SELECT pg_temp.plan_part('o48', 'grand_line_cards', 'return_in_transit', '{"reason":"WRONG_ITEM","description":"ได้รับเวอร์ชันธรรมดาแทน SCR"}');
SELECT pg_temp.plan_part('o56', 'pegasus_official', 'completed_fast');
SELECT pg_temp.plan_part('o56', 'grand_line_cards', 'shipped');
SELECT pg_temp.plan_part('o51', 'pegasus_official', 'shipped');
SELECT pg_temp.plan_part('o50', 'grand_line_cards', 'delivered');
SELECT pg_temp.plan_part('o52', 'card_kingdom_th',  'shipped');
SELECT pg_temp.plan_part('o53', 'pegasus_official', 'preparing');
SELECT pg_temp.plan_part('o53', 'card_kingdom_th',  'paid');
SELECT pg_temp.plan_part('o54', 'demo_seller',      'preparing');
SELECT pg_temp.plan_part('o55', 'grand_line_cards', 'paid');
SELECT pg_temp.plan_part('o62', 'card_kingdom_th',  'paid');

-- ---------- reviews ----------
SELECT pg_temp.plan_review('o01', 'card_kingdom_th',  5,  5, 'ของแท้ แพ็กดีมาก', 'การ์ดสภาพสวย ใส่ท็อปโหลดเดอร์มาอย่างดี ส่งไวมากค่ะ', 'ขอบคุณที่อุดหนุนครับ');
SELECT pg_temp.plan_review('o02', 'tcg_corner',       3,  5, 'ส่งไว', 'ได้ของภายใน 2 วัน การ์ดตรงปก');
SELECT pg_temp.plan_review('o03', 'demo_seller',      20, 4, 'โอเค', 'ของครบ แต่กล่องพัสดุบุบนิดหน่อย');
SELECT pg_temp.plan_review('o04', 'pegasus_official', 2,  5, 'ซองใหม่ ซีลแน่น', 'ซื้อซอง 151 มา 6 ซอง ซีลครบทุกซอง เปิดได้ SIR ด้วย', 'ยินดีด้วยครับ');
SELECT pg_temp.plan_review('o05', 'mint_vault',       6,  4, 'การ์ดสวย', 'สภาพตามที่ลงไว้ แพ็กหนาดีมาก');
SELECT pg_temp.plan_review('o07', 'demo_seller',      8,  3, 'ส่งช้าไปหน่อย', 'ของดีแต่รอเกือบสัปดาห์กว่าจะส่ง', 'ขออภัยครับ ช่วงนั้นร้านปิดนับสต็อก จะปรับปรุงครับ');
SELECT pg_temp.plan_review('o08', 'card_kingdom_th',  1,  1, 'แย่มาก', 'ร้านนี้ห่วยที่สุด อย่าซื้อ!!! (รีวิวนี้ถูกผู้ดูแลซ่อนเพราะใช้ถ้อยคำไม่เหมาะสม)', NULL, false);
SELECT pg_temp.plan_review('o10', 'card_kingdom_th',  4,  5, 'การ์ดในฝัน', 'พิคาชู SIR สวยมาก เซ็นเตอร์ดี', 'ขอบคุณที่ไว้ใจร้านเราครับ');
SELECT pg_temp.plan_review('o13', 'grand_line_cards', 10, 4, 'ดี', 'ได้ครบ ส่งเร็ว');
SELECT pg_temp.plan_review('o14', 'mint_vault',       2,  5, 'PSA 10 ของแท้', 'เช็กเลขเซิร์ตแล้วตรง แพ็กกันกระแทกอย่างดี', 'ขอบคุณครับ ไว้มาอุดหนุนใหม่นะครับ');
SELECT pg_temp.plan_review('o09', 'tcg_corner',       12, 5, 'ครบถ้วน', 'ลีดเดอร์กับแชงคูสสภาพ NM จริง');
SELECT pg_temp.plan_review('o12', 'tcg_corner',       6,  5, 'ประทับใจ', 'โรเจอร์ SEC สวยมาก');
SELECT pg_temp.plan_review('o12', 'grand_line_cards', 7,  4, 'ดี', 'ห่อมาดี แต่ส่งช้ากว่าร้านอื่นนิดหน่อย');
SELECT pg_temp.plan_review('o16', 'card_kingdom_th',  3,  5, 'อีวุยสวยมาก', 'ตรงปก ส่งไว');
SELECT pg_temp.plan_review('o17', 'demo_seller',      9,  4, 'ดี', 'การ์ดครบ');
SELECT pg_temp.plan_review('o19', 'tcg_corner',       24, 2, 'ขอคืนไม่ได้', 'การ์ดมีรอยแต่ร้านไม่รับคืน', 'รูปที่แนบมาไม่ตรงกับสภาพตอนส่ง ทางร้านถ่ายวิดีโอตอนแพ็กไว้แล้วครับ');
SELECT pg_temp.plan_review('o20', 'mint_vault',       5,  5, 'เยี่ยม', 'เอฟี SIR สภาพสมบูรณ์');
SELECT pg_temp.plan_review('o23', 'card_kingdom_th',  1,  5, 'แบล็กกี SIR ในที่สุด', 'การ์ดสภาพสมบูรณ์ ขอบคุณร้านมาก');
SELECT pg_temp.plan_review('o25', 'card_kingdom_th',  12, 4, 'ร้านรับผิดชอบดี', 'มีรอยขาวที่ขอบ ร้านคืนเงินบางส่วนให้ทันที');
SELECT pg_temp.plan_review('o26', 'demo_seller',      4,  5, 'ดีมาก', 'การ์ดตรงปก');
SELECT pg_temp.plan_review('o30', 'tcg_corner',       6,  4, 'โอเค', 'ส่งไว ของสวย');
SELECT pg_temp.plan_review('o34', 'pegasus_official', 3,  5, 'ซองคอลเลกเตอร์แท้', 'ได้การ์ดฟอยล์สวย ๆ');
SELECT pg_temp.plan_review('o38', 'pegasus_official', 2,  5, 'กล่องสวย', 'กล่องไม่บุบ ส่งเร็ว');
SELECT pg_temp.plan_review('o41', 'grand_line_cards', 3,  5, 'โกคู SCR สวยมาก', 'แพ็กมาในท็อปโหลดเดอร์ ดีมาก');
SELECT pg_temp.plan_review('o43', 'card_kingdom_th',  2,  5, 'ร้านประจำ', 'สั่งกี่ครั้งก็ไม่ผิดหวัง');

-- ---------- payouts and an adjustment ----------
SELECT pg_temp.ev(v.days, 0, v.kind, v.args::jsonb)
  FROM (VALUES
    (70,   'payout_request', '{"pkey":"pk1","seller":"card_kingdom_th","share":80}'),
    (69.5, 'payout',         '{"pkey":"pk1","to":"APPROVED"}'),
    (69,   'payout',         '{"pkey":"pk1","to":"PROCESSING"}'),
    (68.5, 'payout',         '{"pkey":"pk1","to":"PAID","ref":"SCB0081234561"}'),
    (20,   'payout_request', '{"pkey":"pk2","seller":"card_kingdom_th","share":70}'),
    (19,   'payout',         '{"pkey":"pk2","to":"APPROVED"}'),
    (18.5, 'payout',         '{"pkey":"pk2","to":"PROCESSING"}'),
    (18,   'payout',         '{"pkey":"pk2","to":"PAID","ref":"SCB0081234562"}'),
    (1,    'payout_request', '{"pkey":"pk3","seller":"card_kingdom_th","share":50}'),
    (60,   'payout_request', '{"pkey":"pt1","seller":"tcg_corner","share":90}'),
    (59.5, 'payout',         '{"pkey":"pt1","to":"APPROVED"}'),
    (59,   'payout',         '{"pkey":"pt1","to":"PROCESSING"}'),
    (58.5, 'payout',         '{"pkey":"pt1","to":"PAID","ref":"BBL0042210001"}'),
    (10,   'payout_request', '{"pkey":"pt2","seller":"tcg_corner","share":60}'),
    (9,    'payout',         '{"pkey":"pt2","to":"APPROVED"}'),
    (70,   'payout_request', '{"pkey":"pm1","seller":"mint_vault","share":90}'),
    (69.5, 'payout',         '{"pkey":"pm1","to":"APPROVED"}'),
    (69,   'payout',         '{"pkey":"pm1","to":"PROCESSING"}'),
    (68.5, 'payout',         '{"pkey":"pm1","to":"PAID","ref":"KTB0098712341"}'),
    (30,   'payout_request', '{"pkey":"pm2","seller":"mint_vault","share":50}'),
    (29.5, 'payout',         '{"pkey":"pm2","to":"REJECTED","reason":"ยอดที่ขอไม่ตรงกับรายการขายที่ปิดแล้ว กรุณาส่งคำขอใหม่"}'),
    (29,   'payout_request', '{"pkey":"pm3","seller":"mint_vault","share":80}'),
    (28.8, 'payout',         '{"pkey":"pm3","to":"APPROVED"}'),
    (28.6, 'payout',         '{"pkey":"pm3","to":"PROCESSING"}'),
    (28.4, 'payout',         '{"pkey":"pm3","to":"FAILED","reason":"ธนาคารปลายทางปฏิเสธการโอน"}'),
    (27,   'payout_request', '{"pkey":"pm4","seller":"mint_vault","share":80}'),
    (26.8, 'payout',         '{"pkey":"pm4","to":"APPROVED"}'),
    (26.6, 'payout',         '{"pkey":"pm4","to":"PROCESSING"}'),
    (26.4, 'payout',         '{"pkey":"pm4","to":"PAID","ref":"KTB0098712342"}'),
    (40,   'payout_request', '{"pkey":"pp1","seller":"pegasus_official","share":90}'),
    (39.5, 'payout',         '{"pkey":"pp1","to":"APPROVED"}'),
    (39,   'payout',         '{"pkey":"pp1","to":"PROCESSING"}'),
    (38.5, 'payout',         '{"pkey":"pp1","to":"PAID","ref":"KBANK0012345601"}'),
    (3,    'payout_request', '{"pkey":"pp2","seller":"pegasus_official","share":80}'),
    (2.5,  'payout',         '{"pkey":"pp2","to":"APPROVED"}'),
    (2,    'payout',         '{"pkey":"pp2","to":"PROCESSING"}'),
    (50,   'payout_request', '{"pkey":"pd1","seller":"demo_seller","share":100}'),
    (49.5, 'payout',         '{"pkey":"pd1","to":"APPROVED"}'),
    (49,   'payout',         '{"pkey":"pd1","to":"PROCESSING"}'),
    (48.5, 'payout',         '{"pkey":"pd1","to":"PAID","ref":"BAY0033344455"}'),
    (45,   'payout_request', '{"pkey":"pg1","seller":"grand_line_cards","share":100}'),
    (44.5, 'payout',         '{"pkey":"pg1","to":"APPROVED"}'),
    (44,   'payout',         '{"pkey":"pg1","to":"PROCESSING"}'),
    (43.5, 'payout',         '{"pkey":"pg1","to":"PAID","ref":"TTB0066677788"}'),
    (25,   'adjust',         '{"seller":"card_kingdom_th","amount":150,"n":1,"memo":"ADJ-0001 ชดเชยค่าส่งที่ถูกเก็บซ้ำในคำสั่งซื้อก่อนหน้า"}')
  ) AS v(days, kind, args);

-- ---------- play it all ----------
SELECT pg_temp.run_events();

-- The admin's findings on banned_trader: listings already blocked (05), now the
-- seller is suspended. Done after the replay because a listing can only be
-- created while its seller is VERIFIED.
UPDATE seller_profile
   SET status = 'SUSPENDED',
       suspended_reason = 'ขายการ์ดปลอมตามรายงานของผู้ซื้อ (คำสั่งคืนสินค้าเหตุ COUNTERFEIT)',
       updated_at = pg_temp.ago(20, 2)
 WHERE id = pg_temp.spid('banned_trader');
