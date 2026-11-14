-- Demo seed: what the two shops sell, as events on the timeline (full-seed/04_engine.sql
-- plays them). Each listing is stocked with real cards (listing_unit rows) at a
-- cost, so stock, cost books and the stock ledger all agree.
--
-- demo_seller sells mostly Pokemon, tcg_corner mostly One Piece; a few cards are
-- sold by both so the product page shows two offers. Prices are in baht.
-- ref is the handle 04_orders.sql buys with.

SELECT pg_temp.ev(v.days, 0, 'listing', jsonb_build_object(
           'ref', v.ref, 'seller', v.seller, 'sku', v.sku, 'cond', v.cond,
           'price', v.price, 'qty', v.qty, 'cost', v.cost, 'note', v.note))
  FROM (VALUES
    -- ref,           seller,        sku,                                             cond,     price,  qty, cost,  days, note
    -- Demo Seller Shop: Pokemon singles, sealed and accessories
    ('ds-char006',   'demo_seller', 'PKM-MEW-006-EN-H',                              'NM',     150::numeric,   10, 80::numeric,    110::numeric, NULL::text),
    ('ds-mew151',    'demo_seller', 'PKM-MEW-151-EN-H',                              'NM',     120,    8,   70,    110, NULL),
    ('ds-pika025',   'demo_seller', 'PKM-MEW-025-EN-N',                              'NM',     15,     40,  5,     110, 'การ์ดธรรมดา เหมาะสำหรับเริ่มสะสม'),
    ('ds-charm004',  'demo_seller', 'PKM-MEW-004-EN-N',                              'NM',     20,     20,  8,     110, NULL),
    ('ds-squirt007', 'demo_seller', 'PKM-MEW-007-EN-N',                              'NM',     20,     20,  8,     110, NULL),
    ('ds-char199',   'demo_seller', 'PKM-MEW-199-EN-H',                              'NM',     7900,   2,   6000,  108, 'ใส่ท็อปโหลดเดอร์และกล่องกันกระแทกทุกใบ'),
    ('ds-char199lp', 'demo_seller', 'PKM-MEW-199-EN-H',                              'LP',     6900,   1,   5000,  100, 'มีรอยขาวที่ขอบด้านหลังเล็กน้อย ดูรูปประกอบ'),
    ('ds-pikaex057', 'demo_seller', 'PKM-SSP-057-EN-H',                              'NM',     140,    12,  80,    90,  NULL),
    ('ds-eevee167',  'demo_seller', 'PKM-PRE-167-EN-H',                              'NM',     1800,   3,   1300,  70,  NULL),
    ('ds-umb161',    'demo_seller', 'PKM-PRE-161-EN-H',                              'NM',     36000,  1,   29000, 68,  'ใบเดียวในร้าน'),
    ('ds-mewtwo231', 'demo_seller', 'PKM-DRI-231-EN-H',                              'NM',     3900,   2,   2900,  45,  NULL),
    ('ds-luc179',    'demo_seller', 'PKM-MEG-179-EN-H',                              'NM',     2450,   3,   1800,  20,  NULL),
    ('ds-151pack',   'demo_seller', 'PKM-POKEMON-151-BOOSTER-PACK-EN',               'SEALED', 320,    40,  210,   110, NULL),
    ('ds-preetb',    'demo_seller', 'PKM-PRISMATIC-EVOLUTIONS-ELITE-TRAINER-BOX-EN', 'SEALED', 3500,   6,   2600,  70,  'จำกัด 2 กล่องต่อออเดอร์'),
    ('ds-megpack',   'demo_seller', 'PKM-MEGA-EVOLUTION-BOOSTER-PACK-EN',            'SEALED', 220,    36,  150,   20,  NULL),
    ('ds-sleeves',   'demo_seller', 'PKM-ULTRA-PRO-LILLIE-CLEFAIRY-SLEEVES-65-EN',   'SEALED', 320,    15,  210,   100, NULL),
    ('ds-toploader', 'demo_seller', 'PKM-ULTRA-PRO-TOPLOADER-BLACK-BORDER-25-EN',    'SEALED', 190,    30,  120,   100, NULL),

    -- TCG Corner: One Piece singles and boxes, plus a few Pokemon
    ('tc-op01-120',  'tcg_corner',  'OPC-OP01-120-EN-F',                             'NM',     1050,   3,   700,   105, NULL),
    ('tc-op01-025',  'tcg_corner',  'OPC-OP01-025-EN-F',                             'NM',     350,    6,   200,   105, NULL),
    ('tc-op09-001',  'tcg_corner',  'OPC-OP09-001-EN-F',                             'NM',     70,     15,  35,    78,  NULL),
    ('tc-op09-004',  'tcg_corner',  'OPC-OP09-004-EN-F',                             'NM',     540,    5,   360,   78,  NULL),
    ('tc-op09-118',  'tcg_corner',  'OPC-OP09-118-EN-F',                             'NM',     1250,   3,   850,   78,  NULL),
    ('tc-op09-119',  'tcg_corner',  'OPC-OP09-119-EN-F',                             'NM',     1050,   3,   700,   78,  NULL),
    ('tc-op10-022',  'tcg_corner',  'OPC-OP10-022-EN-F',                             'NM',     40,     20,  15,    60,  NULL),
    ('tc-op10-071',  'tcg_corner',  'OPC-OP10-071-EN-F',                             'NM',     175,    8,   100,   60,  NULL),
    ('tc-op10-118',  'tcg_corner',  'OPC-OP10-118-EN-F',                             'NM',     700,    4,   460,   60,  NULL),
    ('tc-eb02-061',  'tcg_corner',  'OPC-EB02-061-EN-F',                             'NM',     1400,   2,   1000,  49,  NULL),
    ('tc-op11-040',  'tcg_corner',  'OPC-OP11-040-EN-F',                             'NM',     55,     15,  25,    39,  NULL),
    ('tc-op11-067',  'tcg_corner',  'OPC-OP11-067-EN-F',                             'NM',     140,    8,   80,    39,  NULL),
    ('tc-st21-014',  'tcg_corner',  'OPC-ST21-014-EN-F',                             'NM',     105,    8,   60,    62,  NULL),
    ('tc-op09box',   'tcg_corner',  'OPC-OP09-EMPERORS-BOOSTER-BOX-EN',              'SEALED', 3990,   3,   3000,  80,  NULL),
    ('tc-op09pack',  'tcg_corner',  'OPC-OP09-EMPERORS-BOOSTER-PACK-EN',             'SEALED', 170,    36,  120,   80,  NULL),
    ('tc-op10box',   'tcg_corner',  'OPC-OP10-ROYAL-BLOOD-BOOSTER-BOX-EN',           'SEALED', 3350,   3,   2500,  60,  NULL),
    ('tc-st21deck',  'tcg_corner',  'OPC-OP-ST21-STARTER-DECK-EX-GEAR-5-EN',         'SEALED', 690,    10,  450,   62,  NULL),
    ('tc-char006',   'tcg_corner',  'PKM-MEW-006-EN-H',                              'NM',     155,    5,   85,    100, NULL),
    ('tc-eevee167',  'tcg_corner',  'PKM-PRE-167-EN-H',                              'NM',     1850,   2,   1350,  66,  NULL),
    ('tc-strongbox', 'tcg_corner',  'OPC-DRAGON-SHIELD-STRONGBOX-CLEAR-EN',          'SEALED', 260,    20,  160,   90,  NULL)
  ) AS v(ref, seller, sku, cond, price, qty, cost, days, note);

-- A price cut after a quiet month, so the price history has a second point.
SELECT pg_temp.ev(30, 0, 'price', '{"ref":"ds-char199","price":7500}');
SELECT pg_temp.ev(12, 0, 'price', '{"ref":"tc-op09-118","price":1190}');
