-- What each seller offers, as events on the timeline: listings with their first
-- stock-in, later restocks, price moves (by the seller and by the nightly
-- AUTO_MEDIAN job), pauses, delistings, cards kept in hand and a write-off.
--
-- Prices are in baht, roughly the market price at the time of writing.
-- ref is a short handle 06_orders.sql uses to buy from a listing.

SELECT pg_temp.ev(v.days, 0, 'listing', jsonb_build_object(
           'ref', v.ref, 'seller', v.seller, 'sku', v.sku, 'cond', v.cond, 'grader', v.grader, 'grade', v.grade,
           'price', v.price, 'qty', v.qty, 'cost', v.cost, 'status', v.status, 'mode', v.mode,
           'offset', v.price_offset, 'floor', v.floor_price, 'ceiling', v.ceiling_price,
           'lot', v.lot, 'note', v.note, 'cert', v.cert, 'photos', v.photos))
  FROM (VALUES
    -- ref,                 seller,             sku,                                              cond,     grader, grade, price,    qty, cost,    days, status,   mode,          offset, floor, ceiling, lot, note, cert, photos
    -- Pegasus Official: sealed product and accessories at retail price
    ('po-151pack',      'pegasus_official', 'PKM-POKEMON-151-BOOSTER-PACK-EN',                 'SEALED', NULL::text, NULL::numeric, 320::numeric, 40, 210::numeric, 110::numeric, 'ACTIVE', 'MANUAL', NULL::numeric, NULL::numeric, NULL::numeric, NULL::text, NULL::text, NULL::text, 1),
    ('po-151bundle',    'pegasus_official', 'PKM-POKEMON-151-BOOSTER-BUNDLE-EN',               'SEALED', NULL, NULL, 2100,   8,  1500,  110, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-151etb',       'pegasus_official', 'PKM-POKEMON-151-ELITE-TRAINER-BOX-EN',            'SEALED', NULL, NULL, 3900,   6,  2800,  110, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-151upc',       'pegasus_official', 'PKM-POKEMON-151-ULTRA-PREMIUM-COLLECTION-EN',     'SEALED', NULL, NULL, 6500,   2,  4800,  100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'กล่องพรีเมียม ของแท้จากตัวแทนจำหน่าย', NULL, 1),
    ('po-151poster',    'pegasus_official', 'PKM-POKEMON-151-POSTER-COLLECTION-EN',            'SEALED', NULL, NULL, 1600,   0,  NULL,  12,  'DRAFT',  'MANUAL', NULL, NULL, NULL, 'รอของเข้า', NULL, NULL, 0),
    ('po-sspbox',       'pegasus_official', 'PKM-SURGING-SPARKS-BOOSTER-BOX-EN',               'SEALED', NULL, NULL, 8900,   5,  6900,  90,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-preetb',       'pegasus_official', 'PKM-PRISMATIC-EVOLUTIONS-ELITE-TRAINER-BOX-EN',   'SEALED', NULL, NULL, 3500,   10, 2600,  70,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'จำกัด 2 กล่องต่อออเดอร์', NULL, 1),
    ('po-prebundle',    'pegasus_official', 'PKM-PRISMATIC-EVOLUTIONS-BOOSTER-BUNDLE-EN',      'SEALED', NULL, NULL, 1950,   10, 1400,  70,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-pretin',       'pegasus_official', 'PKM-PRISMATIC-EVOLUTIONS-MINI-TIN-UMBREON-EN',    'SEALED', NULL, NULL, 890,    12, 600,   65,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-drietb',       'pegasus_official', 'PKM-DESTINED-RIVALS-ELITE-TRAINER-BOX-EN',        'SEALED', NULL, NULL, 3200,   8,  2300,  44,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-megbox',       'pegasus_official', 'PKM-MEGA-EVOLUTION-BOOSTER-BOX-EN',               'SEALED', NULL, NULL, 7200,   6,  5600,  18,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-megpack',      'pegasus_official', 'PKM-MEGA-EVOLUTION-BOOSTER-PACK-EN',              'SEALED', NULL, NULL, 220,    60, 150,   18,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-op09box',      'pegasus_official', 'OPC-OP09-EMPERORS-BOOSTER-BOX-EN',                'SEALED', NULL, NULL, 3900,   6,  2900,  80,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-op10box',      'pegasus_official', 'OPC-OP10-ROYAL-BLOOD-BOOSTER-BOX-EN',             'SEALED', NULL, NULL, 3350,   4,  2500,  60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-op11box',      'pegasus_official', 'OPC-OP11-FIST-OF-DIVINE-SPEED-BOOSTER-BOX-EN',    'SEALED', NULL, NULL, 3350,   5,  2500,  40,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-eb02box',      'pegasus_official', 'OPC-EB02-ANIME-25TH-BOOSTER-BOX-EN',              'SEALED', NULL, NULL, 5200,   3,  3900,  50,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-st21',         'pegasus_official', 'OPC-OP-ST21-STARTER-DECK-EX-GEAR-5-EN',           'SEALED', NULL, NULL, 690,    10, 450,   63,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-finbox',       'pegasus_official', 'MTG-FIN-PLAY-BOOSTER-DISPLAY-EN',                 'SEALED', NULL, NULL, 6300,   4,  4900,  75,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-fincol',       'pegasus_official', 'MTG-FIN-COLLECTOR-BOOSTER-PACK-EN',               'SEALED', NULL, NULL, 1590,   12, 1150,  75,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-finbundle',    'pegasus_official', 'MTG-FIN-BUNDLE-EN',                               'SEALED', NULL, NULL, 2450,   5,  1800,  75,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-eoebox',       'pegasus_official', 'MTG-EOE-PLAY-BOOSTER-DISPLAY-EN',                 'SEALED', NULL, NULL, 4900,   3,  3800,  55,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-fdnbeg',       'pegasus_official', 'MTG-FDN-BEGINNER-BOX-EN',                         'SEALED', NULL, NULL, 890,    8,  600,   99,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-cmmdeck',      'pegasus_official', 'MTG-CMM-COMMANDER-DECK-PLANESWALKER-PARTY-EN',    'SEALED', NULL, NULL, 2100,   3,  1500,  130, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-ra03box',      'pegasus_official', 'YGO-RA03-QUARTER-CENTURY-BONANZA-BOOSTER-BOX-EN-1E', 'SEALED', NULL, NULL, 3350, 4, 2500,  94,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-alinbox',      'pegasus_official', 'YGO-ALIN-ALLIANCE-INSIGHT-BOOSTER-BOX-EN-1E',     'SEALED', NULL, NULL, 2450,   4,  1800,  60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-alinpack',     'pegasus_official', 'YGO-ALIN-ALLIANCE-INSIGHT-BOOSTER-PACK-EN-1E',    'SEALED', NULL, NULL, 125,    48, 85,    60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-lobbox',       'pegasus_official', 'YGO-LOB-25TH-ANNIVERSARY-BOOSTER-BOX-EN',         'SEALED', NULL, NULL, 2800,   3,  2100,  128, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-bt21box',      'pegasus_official', 'DGM-BT21-WORLD-CONVERGENCE-BOOSTER-BOX-EN',       'SEALED', NULL, NULL, 2950,   3,  2200,  58,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-bt22box',      'pegasus_official', 'DGM-BT22-CYBER-EDEN-BOOSTER-BOX-EN',              'SEALED', NULL, NULL, 2950,   4,  2200,  35,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-bt22pack',     'pegasus_official', 'DGM-BT22-CYBER-EDEN-BOOSTER-PACK-EN',             'SEALED', NULL, NULL, 140,    48, 95,    35,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-jjkbox',       'pegasus_official', 'UA-UE03BT-JUJUTSU-KAISEN-BOOSTER-BOX-EN',         'SEALED', NULL, NULL, 1950,   4,  1450,  84,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-jjkpack',      'pegasus_official', 'UA-UE03BT-JUJUTSU-KAISEN-BOOSTER-PACK-EN',        'SEALED', NULL, NULL, 140,    32, 95,    84,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-dsbox',        'pegasus_official', 'UA-UE05BT-DEMON-SLAYER-BOOSTER-BOX-EN',           'SEALED', NULL, NULL, 2100,   3,  1550,  77,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-opmbox',       'pegasus_official', 'UA-UE06BT-ONE-PUNCH-MAN-BOOSTER-BOX-EN',          'SEALED', NULL, NULL, 1750,   2,  1300,  71,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-fb05box',      'pegasus_official', 'DBF-FB05-NEW-ADVENTURE-BOOSTER-BOX-EN',           'SEALED', NULL, NULL, 2800,   3,  2050,  65,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-fb07box',      'pegasus_official', 'DBF-FB07-WISH-FOR-SHENRON-BOOSTER-BOX-EN',        'SEALED', NULL, NULL, 2950,   4,  2200,  22,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-fb07pack',     'pegasus_official', 'DBF-FB07-WISH-FOR-SHENRON-BOOSTER-PACK-EN',       'SEALED', NULL, NULL, 140,    48, 95,    22,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-ds-jet',       'pegasus_official', 'MTG-DRAGON-SHIELD-MATTE-SLEEVES-JET-EN',          'SEALED', NULL, NULL, 450,    30, 290,   120, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 2),
    ('po-ds-pf',        'pegasus_official', 'MTG-DRAGON-SHIELD-PERFECT-FIT-CLEAR-EN',          'SEALED', NULL, NULL, 220,    30, 140,   120, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-ds-codex',     'pegasus_official', 'OPC-DRAGON-SHIELD-CARD-CODEX-PORTFOLIO-360-MIDNIGHT-BLUE-EN', 'SEALED', NULL, NULL, 1150, 8, 780, 108, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 2),
    ('po-ds-nest',      'pegasus_official', 'MTG-DRAGON-SHIELD-NEST-100-BLACK-BLUE-EN',        'SEALED', NULL, NULL, 1200,   6,  820,   106, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 2),
    ('po-ds-strong',    'pegasus_official', 'OPC-DRAGON-SHIELD-STRONGBOX-CLEAR-EN',            'SEALED', NULL, NULL, 260,    20, 160,   106, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-up-mat',       'pegasus_official', 'PKM-ULTRA-PRO-LILLIE-CLEFAIRY-PLAYMAT-EN',        'SEALED', NULL, NULL, 750,    10, 500,   29,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 2),
    ('po-up-binder',    'pegasus_official', 'PKM-ULTRA-PRO-LILLIE-CLEFAIRY-PRO-BINDER-EN',     'SEALED', NULL, NULL, 890,    10, 600,   29,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-up-sleeves',   'pegasus_official', 'PKM-ULTRA-PRO-LILLIE-CLEFAIRY-SLEEVES-65-EN',     'SEALED', NULL, NULL, 320,    20, 210,   29,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-up-toploader', 'pegasus_official', 'PKM-ULTRA-PRO-TOPLOADER-BLACK-BORDER-25-EN',      'SEALED', NULL, NULL, 190,    40, 120,   135, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-up-eclipse',   'pegasus_official', 'YGO-ULTRA-PRO-ECLIPSE-SMALL-SLEEVES-SMOKE-GREY-EN', 'SEALED', NULL, NULL, 290,   20, 190,   110, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-up-dskmat',    'pegasus_official', 'MTG-ULTRA-PRO-DUSKMOURN-ENDURING-CURIOSITY-PLAYMAT-EN', 'SEALED', NULL, NULL, 950, 6, 650, 88, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-vx-binder',    'pegasus_official', 'OPC-VAULT-X-9-POCKET-EXO-TEC-ZIP-BINDER-EN',      'SEALED', NULL, NULL, 990,    10, 680,   93,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 2),
    ('po-vx-mag',       'pegasus_official', 'YGO-VAULT-X-MAGNETIC-CARD-HOLDERS-25PT-EN',       'SEALED', NULL, NULL, 120,    50, 70,    93,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-vx-deck',      'pegasus_official', 'DBF-VAULT-X-EXO-TEC-DECK-BOX-100-EN',             'SEALED', NULL, NULL, 620,    10, 420,   93,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('po-ds-dice',      'pegasus_official', 'DGM-DRAGON-SHIELD-DICE-COMPANION-MIDNIGHT-BLUE-EN', 'SEALED', NULL, NULL, 990,   5,  680,   100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 2),

    -- Card Kingdom TH: Pokemon singles, a little One Piece and sealed
    ('ck-char006-nm',   'card_kingdom_th',  'PKM-MEW-006-EN-H',  'NM', NULL, NULL, 150,   6,  80,    115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-char006jp-nm', 'card_kingdom_th',  'PKM-MEW-006-JP-H',  'NM', NULL, NULL, 120,   4,  60,    115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'การ์ดภาษาญี่ปุ่น', NULL, 1),
    ('ck-mew151-nm',    'card_kingdom_th',  'PKM-MEW-151-EN-H',  'NM', NULL, NULL, 120,   5,  70,    115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-pika025-nm',   'card_kingdom_th',  'PKM-MEW-025-EN-N',  'NM', NULL, NULL, 15,    30, 5,     115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, 'กองการ์ดธรรมดา', NULL, NULL, 1),
    ('ck-pika025rh-nm', 'card_kingdom_th',  'PKM-MEW-025-EN-RH', 'NM', NULL, NULL, 35,    12, 15,    115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-charm004-nm',  'card_kingdom_th',  'PKM-MEW-004-EN-N',  'NM', NULL, NULL, 20,    20, 8,     115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-squirt007-nm', 'card_kingdom_th',  'PKM-MEW-007-EN-N',  'NM', NULL, NULL, 20,    20, 8,     115, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-char199-nm',   'card_kingdom_th',  'PKM-MEW-199-EN-H',  'NM', NULL, NULL, 7900,  2,  6000,  114, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-char199-lp',   'card_kingdom_th',  'PKM-MEW-199-EN-H',  'LP', NULL, NULL, 6900,  1,  5000,  100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'มีรอยขาวที่ขอบด้านหลังเล็กน้อย ดูรูปประกอบ', NULL, 1),
    ('ck-alak201-nm',   'card_kingdom_th',  'PKM-MEW-201-EN-H',  'NM', NULL, NULL, 1050,  3,  700,   108, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-erika203-nm',  'card_kingdom_th',  'PKM-MEW-203-EN-H',  'NM', NULL, NULL, 2100,  2,  1500,  106, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-pikaex057-nm', 'card_kingdom_th',  'PKM-SSP-057-EN-H',  'NM', NULL, NULL, 140,   10, 80,    88,  'ACTIVE', 'AUTO_MEDIAN', -2.00, 120, 200, NULL, NULL, NULL, 1),
    ('ck-pika238-nm',   'card_kingdom_th',  'PKM-SSP-238-EN-H',  'NM', NULL, NULL, 12500, 2,  9800,  86,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-umb161-nm',    'card_kingdom_th',  'PKM-PRE-161-EN-H',  'NM', NULL, NULL, 36000, 1,  29000, 68,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'ใบเดียวในร้าน', NULL, 1),
    ('ck-syl156-nm',    'card_kingdom_th',  'PKM-PRE-156-EN-H',  'NM', NULL, NULL, 3500,  2,  2600,  68,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-eevee167-nm',  'card_kingdom_th',  'PKM-PRE-167-EN-H',  'NM', NULL, NULL, 1800,  3,  1300,  66,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-mewtwo231-nm', 'card_kingdom_th',  'PKM-DRI-231-EN-H',  'NM', NULL, NULL, 3900,  2,  2900,  43,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-luc179-nm',    'card_kingdom_th',  'PKM-MEG-179-EN-H',  'NM', NULL, NULL, 2450,  3,  1800,  17,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-gard178-nm',   'card_kingdom_th',  'PKM-MEG-178-EN-H',  'NM', NULL, NULL, 3150,  2,  2300,  17,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-op09-004-nm',  'card_kingdom_th',  'OPC-OP09-004-EN-F', 'NM', NULL, NULL, 520,   4,  350,   78,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-sspbox',       'card_kingdom_th',  'PKM-SURGING-SPARKS-BOOSTER-BOX-EN',             'SEALED', NULL, NULL, 9100, 2, 7000, 89, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-preetb',       'card_kingdom_th',  'PKM-PRISMATIC-EVOLUTIONS-ELITE-TRAINER-BOX-EN', 'SEALED', NULL, NULL, 3650, 4, 2700, 70, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ck-151pack',      'card_kingdom_th',  'PKM-POKEMON-151-BOOSTER-PACK-EN',               'SEALED', NULL, NULL, 330,  20, 220, 100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),

    -- TCG Corner: One Piece first, then Pokemon
    ('tc-op01-120-nm',    'tcg_corner', 'OPC-OP01-120-EN-F',     'NM', NULL, NULL, 1050, 3,  700,  140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op01-120par-nm', 'tcg_corner', 'OPC-OP01-120-EN-F-PAR', 'NM', NULL, NULL, 7000, 1,  5200, 140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Parallel art', NULL, 1),
    ('tc-op01-025-nm',    'tcg_corner', 'OPC-OP01-025-EN-F',     'NM', NULL, NULL, 350,  6,  200,  140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op01-025par-nm', 'tcg_corner', 'OPC-OP01-025-EN-F-PAR', 'NM', NULL, NULL, 3150, 1,  2300, 140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Parallel art', NULL, 1),
    ('tc-op09-001-nm',    'tcg_corner', 'OPC-OP09-001-EN-F',     'NM', NULL, NULL, 70,   15, 35,   78,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op09-004-nm',    'tcg_corner', 'OPC-OP09-004-EN-F',     'NM', NULL, NULL, 540,  5,  360,  78,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op09-118-nm',    'tcg_corner', 'OPC-OP09-118-EN-F',     'NM', NULL, NULL, 1250, 3,  850,  78,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op09-119-nm',    'tcg_corner', 'OPC-OP09-119-EN-F',     'NM', NULL, NULL, 1050, 3,  700,  78,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op10-022-nm',    'tcg_corner', 'OPC-OP10-022-EN-F',     'NM', NULL, NULL, 40,   20, 15,   60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op10-071-nm',    'tcg_corner', 'OPC-OP10-071-EN-F',     'NM', NULL, NULL, 175,  8,  100,  60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op10-071-lp',    'tcg_corner', 'OPC-OP10-071-EN-F',     'LP', NULL, NULL, 120,  2,  60,   58,  'PAUSED', 'MANUAL', NULL, NULL, NULL, 'ลดราคาสภาพ LP', 'มีรอยถลอกที่มุม', NULL, 1),
    ('tc-op10-118-nm',    'tcg_corner', 'OPC-OP10-118-EN-F',     'NM', NULL, NULL, 700,  4,  460,  60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op10-118par-nm', 'tcg_corner', 'OPC-OP10-118-EN-F-PAR', 'NM', NULL, NULL, 3150, 1,  2300, 60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Parallel art', NULL, 1),
    ('tc-eb02-061-nm',    'tcg_corner', 'OPC-EB02-061-EN-F',     'NM', NULL, NULL, 1400, 2,  1000, 49,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op11-040-nm',    'tcg_corner', 'OPC-OP11-040-EN-F',     'NM', NULL, NULL, 55,   15, 25,   39,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op11-067-nm',    'tcg_corner', 'OPC-OP11-067-EN-F',     'NM', NULL, NULL, 140,  8,  80,   39,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-st21-014-nm',    'tcg_corner', 'OPC-ST21-014-EN-F',     'NM', NULL, NULL, 105,  8,  60,   62,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op09box',        'tcg_corner', 'OPC-OP09-EMPERORS-BOOSTER-BOX-EN',  'SEALED', NULL, NULL, 3990, 3,  3000, 80, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-op09pack',       'tcg_corner', 'OPC-OP09-EMPERORS-BOOSTER-PACK-EN', 'SEALED', NULL, NULL, 170,  36, 120,  80, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-char006-nm',     'tcg_corner', 'PKM-MEW-006-EN-H',      'NM', NULL, NULL, 155,  5,  85,   110, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-char006-lp',     'tcg_corner', 'PKM-MEW-006-EN-H',      'LP', NULL, NULL, 110,  3,  50,   110, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'ขอบมีรอยเล็กน้อย', NULL, 1),
    ('tc-zap202-nm',      'tcg_corner', 'PKM-MEW-202-EN-H',      'NM', NULL, NULL, 890,  2,  600,  105, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-eevee167-nm',    'tcg_corner', 'PKM-PRE-167-EN-H',      'NM', NULL, NULL, 1850, 2,  1350, 66,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-ho230-nm',       'tcg_corner', 'PKM-DRI-230-EN-H',      'NM', NULL, NULL, 1750, 2,  1250, 43,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('tc-gar232-nm',      'tcg_corner', 'PKM-DRI-232-EN-H',      'NM', NULL, NULL, 1600, 2,  1150, 43,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),

    -- Mint Vault: graded slabs and high-end raw cards, plus a few played copies
    ('mv-char199-psa10',  'mint_vault', 'PKM-MEW-199-EN-H',  'NM', 'PSA', 10,  25000, 1, 18000, 112, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'PSA 10 Gem Mint ตรวจเลขเซิร์ตได้', 'PSA 84521937', 1),
    ('mv-umb161-psa10',   'mint_vault', 'PKM-PRE-161-EN-H',  'NM', 'PSA', 10,  95000, 1, 78000, 64,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'PSA 10 Gem Mint', 'PSA 93314560', 1),
    ('mv-umb161-nm',      'mint_vault', 'PKM-PRE-161-EN-H',  'NM', NULL, NULL,  37500, 1, 30000, 66,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'เซ็นเตอร์ดี เหมาะส่งเกรด', NULL, 1),
    ('mv-pika238-bgs95',  'mint_vault', 'PKM-SSP-238-EN-H',  'NM', 'BGS', 9.5, 22000, 1, 16000, 80,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'BGS 9.5 Gem Mint', 'BGS 0014839275', 1),
    ('mv-pika238-nm',     'mint_vault', 'PKM-SSP-238-EN-H',  'NM', NULL, NULL,  12900, 1, 10000, 82,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('mv-espeon155-nm',   'mint_vault', 'PKM-PRE-155-EN-H',  'NM', NULL, NULL,  4600,  2, 3400,  66,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('mv-syl156-cgc10',   'mint_vault', 'PKM-PRE-156-EN-H',  'NM', 'CGC', 10,  9800,  1, 7000,  60,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'CGC 10 Pristine', 'CGC 4129385007', 1),
    ('mv-latias239-nm',   'mint_vault', 'PKM-SSP-239-EN-H',  'NM', NULL, NULL,  2100,  2, 1500,  84,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('mv-mew205-nm',      'mint_vault', 'PKM-MEW-205-EN-H',  'NM', NULL, NULL,  1550,  2, 1100,  103, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('mv-kang182-nm',     'mint_vault', 'PKM-MEG-182-EN-H',  'NM', NULL, NULL,  1400,  2, 1000,  15,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('mv-mew151-mp',      'mint_vault', 'PKM-MEW-151-EN-H',  'MP', NULL, NULL,  70,    2, 30,    100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'มีรอยถลอกที่ผิวหน้าการ์ด', NULL, 1),
    ('mv-char006-hp',     'mint_vault', 'PKM-MEW-006-EN-H',  'HP', NULL, NULL,  60,    1, 25,    100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'มุมยับ ขอบลอก', NULL, 1),
    ('mv-char006-dmg',    'mint_vault', 'PKM-MEW-006-EN-H',  'DMG', NULL, NULL, 35,    1, 10,    100, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'การ์ดมีรอยพับ ขายตามสภาพ', NULL, 1),
    ('mv-bewd-qcsr-nm',   'mint_vault', 'YGO-RA03-EN079-EN-F-1E-QCSR', 'NM', NULL, NULL, 880, 2, 600, 92, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('mv-dmg-qcsr-psa10', 'mint_vault', 'YGO-RA03-EN123-EN-F-1E-QCSR', 'NM', 'PSA', 10, 4900, 1, 3500, 90, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'PSA 10', 'PSA 71203344', 1),
    ('mv-shanks-par-psa10','mint_vault','OPC-OP01-120-EN-F-PAR', 'NM', 'PSA', 10, 18500, 1, 13000, 120, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'PSA 10 Parallel', 'PSA 66012345', 1),
    ('mv-gojo-sr2',       'mint_vault', 'UA-UE03BT-012-EN-F-SR2', 'NM', NULL, NULL, 2100, 1, 1500, 80, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'SR★★ ลายเซ็นพิมพ์', NULL, 1),

    -- Demo Seller Shop: Magic and Yu-Gi-Oh!
    ('ds-cloud-nm',     'demo_seller', 'MTG-FIN-010-EN-N', 'NM', NULL, NULL, 780,  3,  520,  72,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-cloud-f',      'demo_seller', 'MTG-FIN-010-EN-F', 'NM', NULL, NULL, 1100, 1,  780,  72,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Foil', NULL, 1),
    ('ds-vivi-nm',      'demo_seller', 'MTG-FIN-248-EN-N', 'NM', NULL, NULL, 1130, 2,  800,  72,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-buster-nm',    'demo_seller', 'MTG-FIN-255-EN-N', 'NM', NULL, NULL, 1720, 2,  1250, 72,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-bahamut-nm',   'demo_seller', 'MTG-FIN-001-EN-N', 'NM', NULL, NULL, 870,  2,  600,  72,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-lightning-nm', 'demo_seller', 'MTG-FIN-233-EN-N', 'NM', NULL, NULL, 320,  4,  200,  72,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-lightning-lp', 'demo_seller', 'MTG-FIN-233-EN-N', 'LP', NULL, NULL, 250,  2,  150,  70,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-riddler-nm',   'demo_seller', 'MTG-EOE-072-EN-N', 'NM', NULL, NULL, 1260, 2,  900,  53,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-riddler-f',    'demo_seller', 'MTG-EOE-072-EN-F', 'NM', NULL, NULL, 1650, 1,  1200, 53,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Foil', NULL, 1),
    ('ds-tezz-nm',      'demo_seller', 'MTG-EOE-002-EN-N', 'NM', NULL, NULL, 770,  2,  540,  53,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-ouro-nm',      'demo_seller', 'MTG-EOE-201-EN-N', 'NM', NULL, NULL, 1260, 2,  900,  52,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-sol-nm',       'demo_seller', 'MTG-CMM-410-EN-N', 'NM', NULL, NULL, 70,   20, 30,   130, 'ACTIVE', 'AUTO_MEDIAN', 0.00, 60, 120, NULL, NULL, NULL, 1),
    ('ds-sol-f',        'demo_seller', 'MTG-CMM-410-EN-F', 'NM', NULL, NULL, 450,  3,  300,  130, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Foil', NULL, 1),
    ('ds-lotus-nm',     'demo_seller', 'MTG-CMM-396-EN-N', 'NM', NULL, NULL, 1430, 1,  1000, 130, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-tithe-nm',     'demo_seller', 'MTG-CMM-057-EN-N', 'NM', NULL, NULL, 2030, 1,  1450, 130, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-dbl-nm',       'demo_seller', 'MTG-FDN-216-EN-N', 'NM', NULL, NULL, 1150, 2,  800,  95,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-dbl-f',        'demo_seller', 'MTG-FDN-216-EN-F', 'NM', NULL, NULL, 1600, 1,  1150, 95,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'Foil', NULL, 1),
    ('ds-niv-nm',       'demo_seller', 'MTG-FDN-123-EN-N', 'NM', NULL, NULL, 250,  3,  150,  95,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-bewd-qcsr',    'demo_seller', 'YGO-RA03-EN079-EN-F-1E-QCSR', 'NM', NULL, NULL, 900,  2, 620, 92, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-bewd-psr',     'demo_seller', 'YGO-RA03-EN079-EN-F-1E-PSR',  'NM', NULL, NULL, 280,  4, 180, 92, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-dm-qcsr',      'demo_seller', 'YGO-RA03-EN080-EN-F-1E-QCSR', 'NM', NULL, NULL, 1050, 2, 720, 92, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-rebd-qcsr',    'demo_seller', 'YGO-RA03-EN081-EN-F-1E-QCSR', 'NM', NULL, NULL, 700,  2, 480, 91, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-reborn-qcsr',  'demo_seller', 'YGO-RA03-EN052-EN-F-1E-QCSR', 'NM', NULL, NULL, 525,  3, 350, 91, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-shizuku-qcsr', 'demo_seller', 'YGO-RA03-EN043-EN-F-1E-QCSR', 'NM', NULL, NULL, 350,  3, 220, 90, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-raigeki-draft','demo_seller', 'YGO-RA03-EN083-EN-F-1E-QCSR', 'NM', NULL, NULL, 420,  0, NULL, 30, 'DRAFT', 'MANUAL', NULL, NULL, NULL, 'ร่าง', NULL, NULL, 0),
    ('ds-dmgapp',       'demo_seller', 'YGO-ALIN-EN004-EN-F-1E',      'NM', NULL, NULL, 525,  3, 350, 58, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-diabell',      'demo_seller', 'YGO-ALIN-EN011-EN-F-1E',      'NM', NULL, NULL, 280,  4, 170, 58, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-ryzeal',       'demo_seller', 'YGO-ALIN-EN022-EN-F-1E',      'NM', NULL, NULL, 350,  4, 220, 57, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('ds-exodia',       'demo_seller', 'YGO-LOB-EN124-EN-H',          'NM', NULL, NULL, 180,  5, 100, 125, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),

    -- Grand Line Cards: One Piece, Digimon, Union Arena, Dragon Ball
    ('gl-op09-119-nm', 'grand_line_cards', 'OPC-OP09-119-EN-F',     'NM', NULL, NULL, 1080, 2, 720,  76, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-op10-022-nm', 'grand_line_cards', 'OPC-OP10-022-EN-F',     'NM', NULL, NULL, 45,   12, 18,  58, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-eb02-006-nm', 'grand_line_cards', 'OPC-EB02-006-EN-F',     'NM', NULL, NULL, 280,  4, 180,  48, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-op11-040-nm', 'grand_line_cards', 'OPC-OP11-040-EN-F',     'NM', NULL, NULL, 60,   10, 25,  38, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-tai-nm',      'grand_line_cards', 'DGM-BT21-102-EN-F',     'NM', NULL, NULL, 1400, 2, 1000, 56, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-gaia-nm',     'grand_line_cards', 'DGM-BT21-101-EN-F',     'NM', NULL, NULL, 700,  2, 480,  56, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-davis-nm',    'grand_line_cards', 'DGM-BT21-085-EN-F',     'NM', NULL, NULL, 175,  5, 100,  56, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-takato-nm',   'grand_line_cards', 'DGM-BT21-089-EN-F',     'NM', NULL, NULL, 175,  5, 100,  56, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-omni-nm',     'grand_line_cards', 'DGM-BT22-015-EN-F',     'NM', NULL, NULL, 280,  4, 180,  33, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-kyoko-nm',    'grand_line_cards', 'DGM-BT22-101-EN-F',     'NM', NULL, NULL, 875,  2, 600,  33, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-sayo-nm',     'grand_line_cards', 'DGM-BT22-102-EN-F',     'NM', NULL, NULL, 700,  2, 480,  33, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-bt22box',     'grand_line_cards', 'DGM-BT22-CYBER-EDEN-BOOSTER-BOX-EN', 'SEALED', NULL, NULL, 3050, 2, 2250, 34, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-gojo-nm',     'grand_line_cards', 'UA-UE03BT-012-EN-F',    'NM', NULL, NULL, 210,  6, 120,  82, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-yuji-nm',     'grand_line_cards', 'UA-UE03BT-040-EN-F',    'NM', NULL, NULL, 105,  6, 55,   82, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-megumi-nm',   'grand_line_cards', 'UA-UE03BT-022-EN-F',    'NM', NULL, NULL, 105,  6, 55,   82, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-tanjiro-nm',  'grand_line_cards', 'UA-UE05BT-008-EN-F',    'NM', NULL, NULL, 140,  6, 80,   75, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-nezuko-nm',   'grand_line_cards', 'UA-UE05BT-011-EN-F',    'NM', NULL, NULL, 175,  6, 100,  75, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-zenitsu-nm',  'grand_line_cards', 'UA-UE05BT-071-EN-F',    'NM', NULL, NULL, 105,  6, 55,   75, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-saitama-nm',  'grand_line_cards', 'UA-UE06BT-018-EN-F',    'NM', NULL, NULL, 140,  6, 80,   70, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-genos-nm',    'grand_line_cards', 'UA-UE06BT-007-EN-F',    'NM', NULL, NULL, 105,  6, 55,   70, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-goku119-nm',  'grand_line_cards', 'DBF-FB05-119-EN-F',     'NM', NULL, NULL, 1580, 2, 1100, 64, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-frieza-nm',   'grand_line_cards', 'DBF-FB05-120-EN-F',     'NM', NULL, NULL, 875,  2, 600,  64, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-vegito-nm',   'grand_line_cards', 'DBF-FB05-040-EN-F',     'NM', NULL, NULL, 140,  5, 80,   64, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-broly-nm',    'grand_line_cards', 'DBF-FB05-084-EN-F',     'NM', NULL, NULL, 105,  5, 55,   64, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-gokugt-nm',   'grand_line_cards', 'DBF-FB07-122-EN-F',     'NM', NULL, NULL, 2100, 2, 1500, 21, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-gohan-nm',    'grand_line_cards', 'DBF-FB07-121-EN-F',     'NM', NULL, NULL, 1230, 2, 880,  21, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-syn-nm',      'grand_line_cards', 'DBF-FB07-025-EN-F',     'NM', NULL, NULL, 70,   8, 35,   20, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('gl-fb07box',     'grand_line_cards', 'DBF-FB07-WISH-FOR-SHENRON-BOOSTER-BOX-EN', 'SEALED', NULL, NULL, 3050, 2, 2250, 21, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),

    -- Kanto Cards: on vacation, so all of this is hidden from buyers without being unlisted
    ('kt-pika025-nm',  'kanto_cards', 'PKM-MEW-025-EN-N', 'NM', NULL, NULL, 18,   20, 5,    140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('kt-char006-nm',  'kanto_cards', 'PKM-MEW-006-EN-H', 'NM', NULL, NULL, 160,  3,  90,   140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('kt-syl156-nm',   'kanto_cards', 'PKM-PRE-156-EN-H', 'NM', NULL, NULL, 3400, 1,  2500, 66,  'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),
    ('kt-151pack',     'kanto_cards', 'PKM-POKEMON-151-BOOSTER-PACK-EN', 'SEALED', NULL, NULL, 325, 12, 220, 140, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, NULL, NULL, 1),

    -- Banned Trader: priced far below market; an admin blocks these and suspends the seller
    ('bt-umb161-nm',   'banned_trader', 'PKM-PRE-161-EN-H', 'NM', NULL, NULL, 9900, 3, 3000, 60, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'ของแท้แน่นอน ราคาพิเศษ', NULL, 1),
    ('bt-char199-nm',  'banned_trader', 'PKM-MEW-199-EN-H', 'NM', NULL, NULL, 2900, 2, 900,  58, 'ACTIVE', 'MANUAL', NULL, NULL, NULL, NULL, 'ราคาต่ำกว่าตลาด รีบเลย', NULL, 1)
  ) AS v(ref, seller, sku, cond, grader, grade, price, qty, cost, days, status, mode, price_offset, floor_price,
         ceiling_price, lot, note, cert, photos);

-- Cards that came in later, on listings that already exist.
SELECT pg_temp.ev(v.days, 0, 'restock', jsonb_build_object('ref', v.ref, 'qty', v.qty, 'cost', v.cost))
  FROM (VALUES
        ('po-151pack',      50, 36, 215),
        ('ck-pikaex057-nm', 40, 6,  85),
        ('tc-op09-001-nm',  45, 10, 30),
        ('po-megpack',      8,  36, 155)
       ) AS v(ref, days, qty, cost);

-- Price moves: by the seller (changed_by set) and by the nightly AUTO_MEDIAN job (changed_by NULL).
SELECT pg_temp.ev(v.days, 1, 'price', jsonb_build_object('ref', v.ref, 'price', v.price, 'job', v.job, 'reason', v.reason))
  FROM (VALUES
        ('ck-char199-nm',   80, 8500,  false, NULL::text),
        ('ck-char199-nm',   30, 8200,  false, NULL),
        ('ck-pikaex057-nm', 60, 150,   true,  'median of 4 active listings'),
        ('ck-pikaex057-nm', 20, 135,   true,  'median of 5 active listings'),
        ('ck-pikaex057-nm', 5,  140,   true,  'median of 4 active listings'),
        ('ds-sol-nm',       90, 75,    true,  'median of 3 active listings'),
        ('ds-sol-nm',       40, 70,    true,  'median of 3 active listings'),
        ('tc-op09-118-nm',  40, 1190,  false, NULL),
        ('po-megbox',       10, 6990,  false, NULL),
        ('mv-umb161-nm',    30, 38900, false, NULL),
        ('gl-op09-119-nm',  50, 990,   false, NULL)
       ) AS v(ref, days, price, job, reason);

-- Bulk kept in hand, a slab waiting to be graded, and a card lost in storage.
SELECT pg_temp.ev(v.days, 2, 'stock_hand', jsonb_build_object('seller', v.seller, 'sku', v.sku, 'cond', v.cond,
                                                              'qty', v.qty, 'cost', v.cost, 'unit_note', v.note))
  FROM (VALUES
        ('card_kingdom_th', 'PKM-MEW-025-EN-N',  'NM', 10, 5,    90, 'กล่องการ์ดธรรมดา ยังไม่ได้ลงขาย'),
        ('tcg_corner',      'OPC-OP09-001-EN-F', 'NM', 5,  35,   70, NULL),
        ('mint_vault',      'PKM-MEW-199-EN-H',  'NM', 1,  6500, 40, 'รอส่งเกรด PSA รอบหน้า')
       ) AS v(seller, sku, cond, qty, cost, days, note);

SELECT pg_temp.ev(30, 0, 'write_off', jsonb_build_object(
           'seller', 'card_kingdom_th', 'sku', 'PKM-MEW-025-EN-N', 'cond', 'NM',
           'note', 'การ์ดเปียกน้ำระหว่างจัดเก็บ'));

-- A listing the seller closed for good (its cards went back into hand), and the
-- banned seller's listings, blocked by an admin.
SELECT pg_temp.ev(20, 0, 'listing_status', jsonb_build_object('ref', 'ds-lightning-lp', 'status', 'DELISTED'));
SELECT pg_temp.ev(20, 3, 'listing_status', jsonb_build_object('ref', 'bt-umb161-nm', 'status', 'BLOCKED'));
SELECT pg_temp.ev(20, 3, 'listing_status', jsonb_build_object('ref', 'bt-char199-nm', 'status', 'BLOCKED'));
