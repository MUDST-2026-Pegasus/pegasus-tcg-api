-- Home page sample data: games with logos, shelves with pictures, ~50 products
-- with art, four verified sellers and their listings, hero slides, and a month
-- of completed orders so the trending rail has something to rank.
--
-- NOT a migration, like dev_seed.sql beside it. Every picture it names lives in
-- seed-images/ and must be in MinIO under the same key, so run it through make:
--
--   make seed-home
--
-- which uploads seed-images/ to <bucket>/seed/ and then runs this file. By hand:
--
--   docker cp src/test/resources/db/seed-images/. pegasus-tcg-minio:/tmp/seed-images
--   docker exec pegasus-tcg-minio sh -c "sh /tmp/seed-images/upload.sh pegasus"
--   docker exec -i pegasus-tcg-postgres psql -U postgres -d pegasus_tcg \
--       -v ON_ERROR_STOP=1 < src/test/resources/db/home_seed.sql
--
-- Safe to run again, and safe beside dev_seed.sql: rows are matched on natural
-- keys (codes, slugs, SKUs, usernames, order numbers) and skipped when present.
-- Seeded listings carry lot_label 'seed:home'; seeded orders are numbered PGS-SEED-*.
--
-- Accounts (password Password123!): pegasus_official, card_kingdom_th, tcg_corner
-- and mint_vault sell; collector_mai and deckbuilder_ton buy.
--
-- The seeded orders are COMPLETED history only: they have no payment, ledger or
-- escrow rows and point at no physical cards, so no job ever picks them up.
--
-- Generated from one table together with the images; edit both together.

\set ON_ERROR_STOP on
BEGIN;

-- ---------- games ----------

INSERT INTO game (code, name, name_local, slug, logo_url, display_order)
SELECT v.code, v.name, v.name_local, v.slug, v.logo_url, v.display_order::smallint
  FROM (VALUES
        ('POKEMON', 'Pokemon TCG', 'โปเกมอนการ์ด', 'pokemon-tcg', 'seed/games/pokemon.png', 1),
        ('ONE_PIECE', 'One Piece Card Game', 'วันพีซการ์ดเกม', 'one-piece-card-game', 'seed/games/one-piece.png', 2),
        ('MTG', 'Magic: The Gathering', 'เมจิก เดอะ แกเธอริง', 'magic-the-gathering', 'seed/games/mtg.png', 3),
        ('YUGIOH', 'Yu-Gi-Oh! TCG', 'ยูกิโอ', 'yu-gi-oh-tcg', 'seed/games/yugioh.png', 4),
        ('DIGIMON', 'Digimon Card Game', 'ดิจิมอนการ์ดเกม', 'digimon-card-game', 'seed/games/digimon.png', 5),
        ('UNION_ARENA', 'Union Arena', 'ยูเนียนอารีน่า', 'union-arena', 'seed/games/union-arena.png', 6),
        ('DBS_FW', 'Dragon Ball Super Fusion World', 'ดราก้อนบอล ฟิวชันเวิลด์', 'dragon-ball-fusion-world', 'seed/games/dbs-fw.png', 7)
       ) AS v(code, name, name_local, slug, logo_url, display_order)
 WHERE NOT EXISTS (SELECT 1 FROM game g WHERE g.code = v.code OR g.slug = v.slug);

-- Games made before this seed keep their own logo if they have one.
UPDATE game g
   SET logo_url = v.logo_url
  FROM (VALUES
        ('POKEMON', 'seed/games/pokemon.png'),
        ('ONE_PIECE', 'seed/games/one-piece.png'),
        ('MTG', 'seed/games/mtg.png'),
        ('YUGIOH', 'seed/games/yugioh.png'),
        ('DIGIMON', 'seed/games/digimon.png'),
        ('UNION_ARENA', 'seed/games/union-arena.png'),
        ('DBS_FW', 'seed/games/dbs-fw.png')
       ) AS v(code, logo_url)
 WHERE g.code = v.code
   AND g.logo_url IS NULL;

-- ---------- card sets ----------

INSERT INTO card_set (game_id, code, name, name_local, release_date, total_cards)
SELECT g.id, v.code, v.name, v.name_local, v.release_date::date, v.total_cards
  FROM (VALUES
        ('POKEMON', 'SV8A', 'Terastal Festival', 'เทศกาลเทระสตัล', '2024-10-18', 187),
        ('POKEMON', 'PRE', 'Prismatic Evolutions', 'ปริซึมาติก อีโวลูชันส์', '2025-01-17', 180),
        ('POKEMON', 'MEW', 'Pokemon Card 151', 'โปเกมอนการ์ด 151', '2023-09-22', 207),
        ('POKEMON', 'ME01', 'Mega Evolution', 'เมก้าอีโวลูชัน', '2025-09-26', 188),
        ('ONE_PIECE', 'OP01', 'Romance Dawn', 'โรแมนซ์ดอว์น', '2022-12-02', 121),
        ('ONE_PIECE', 'OP09', 'Emperors in the New World', 'จักรพรรดิแห่งโลกใหม่', '2024-12-13', 121),
        ('ONE_PIECE', 'OP10', 'Royal Blood', 'สายเลือดราชวงศ์', '2025-03-21', 119),
        ('ONE_PIECE', 'EB02', 'Anime 25th Collection', 'คอลเลกชันครบรอบ 25 ปี', '2025-05-09', 106),
        ('ONE_PIECE', 'ST21', 'Starter Deck EX Gear 5', NULL, '2025-01-24', 17),
        ('MTG', 'FIN', 'Final Fantasy', 'ไฟนอลแฟนตาซี', '2025-06-13', 309),
        ('MTG', 'EOE', 'Edge of Eternities', 'สุดขอบนิรันดร์', '2025-08-01', 276),
        ('MTG', 'CMM', 'Commander Masters', NULL, '2023-08-04', 451),
        ('YUGIOH', 'ALIN', 'Alliance Insight', 'อัลไลแอนซ์ อินไซต์', '2025-05-01', 101),
        ('YUGIOH', 'RA02', '25th Anniversary Rarity Collection II', NULL, '2024-05-24', 180),
        ('DIGIMON', 'BT20', 'Over the X', 'โอเวอร์ เดอะ เอ็กซ์', '2025-03-14', 112),
        ('UNION_ARENA', 'UAJJK', 'Jujutsu Kaisen', 'มหาเวทย์ผนึกมาร', '2024-09-06', 70),
        ('DBS_FW', 'FB05', 'New Adventure', 'การผจญภัยครั้งใหม่', '2025-02-14', 124)
       ) AS v(game_code, code, name, name_local, release_date, total_cards)
  JOIN game g ON g.code = v.game_code
 WHERE NOT EXISTS (SELECT 1 FROM card_set s WHERE s.game_id = g.id AND s.code = v.code);

-- ---------- shelves ----------

-- Booster packs and booster boxes, filed under Sealed product.
INSERT INTO catalog_category (game_id, parent_id, code, name, slug, display_order, image_key)
SELECT NULL, parent.id, v.code, v.name, v.slug, v.display_order::smallint, v.image_key
  FROM (VALUES
        ('BOOSTER_PACKS', 'Booster packs', 'booster-packs', 'SEALED', 2, 'seed/categories/packs.jpg'),
        ('BOOSTER_BOXES', 'Booster boxes', 'booster-boxes', 'SEALED', 2, 'seed/categories/boxes.jpg')
       ) AS v(code, name, slug, parent_code, display_order, image_key)
  JOIN catalog_category parent ON parent.code = v.parent_code AND parent.game_id IS NULL
 WHERE NOT EXISTS (
        SELECT 1 FROM catalog_category c WHERE c.code = v.code AND c.game_id IS NULL);

UPDATE catalog_category c
   SET image_key = v.image_key
  FROM (VALUES
        ('SINGLES', 'seed/categories/singles.jpg'),
        ('BOOSTER_PACKS', 'seed/categories/packs.jpg'),
        ('BOOSTER_BOXES', 'seed/categories/boxes.jpg'),
        ('SEALED', 'seed/categories/sealed.jpg'),
        ('ACCESSORY', 'seed/categories/accessories.jpg')
       ) AS v(code, image_key)
 WHERE c.code = v.code
   AND c.game_id IS NULL
   AND c.image_key IS NULL;

-- ---------- products ----------

INSERT INTO catalog_product (
        game_id, category_id, card_set_id, product_type, name, name_local, slug,
        card_number, rarity_code, description, attributes, created_at, updated_at)
SELECT g.id, c.id, s.id, v.product_type, v.name, v.name_local, v.slug,
       v.card_number, v.rarity_code, v.description, v.attributes::jsonb,
       now() - make_interval(days => v.days_ago), now() - make_interval(days => v.days_ago)
  FROM (VALUES
        ('POKEMON', 'SV8A', 'SINGLES', 'SINGLE_CARD', 'Pikachu ex', 'พิคาชู ex', 'pikachu-ex-025-187', '025/187', 'RR', 'The Terastal Festival Pikachu ex, a staple of Lightning decks.', '{"hp":200,"card_type":"Lightning","stage":"Basic"}', 60),
        ('POKEMON', 'SV8A', 'SINGLES', 'SINGLE_CARD', 'Charizard ex', 'ลิซาร์ดอน ex', 'charizard-ex-006-165', '006/165', 'SAR', 'Special Art Rare Charizard ex with full-bleed illustration.', '{"hp":330,"card_type":"Fire","stage":"Stage 2"}', 58),
        ('POKEMON', 'SV8A', 'SINGLES', 'SINGLE_CARD', 'Mew ex', 'มิว ex', 'mew-ex-151-165', '151/165', 'SR', 'Japanese Super Rare Mew ex.', '{"hp":180,"card_type":"Psychic","stage":"Basic"}', 57),
        ('POKEMON', 'SV8A', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Terastal Festival Booster Box', NULL, 'terastal-festival-booster-box', NULL, NULL, 'Sealed Japanese booster box, 10 packs.', '{}', 55),
        ('POKEMON', 'PRE', 'SINGLES', 'SINGLE_CARD', 'Umbreon ex SIR', 'แบล็กกี ex', 'umbreon-ex-sir-161-131', '161/131', 'SIR', 'The chase card of Prismatic Evolutions.', '{}', 3),
        ('POKEMON', 'PRE', 'SINGLES', 'SINGLE_CARD', 'Sylveon ex SIR', 'นิมเฟีย ex', 'sylveon-ex-sir-156-131', '156/131', 'SIR', 'Special Illustration Rare Sylveon ex.', '{}', 4),
        ('POKEMON', 'PRE', 'SINGLES', 'SINGLE_CARD', 'Eevee ex SAR', 'อีวุย ex', 'eevee-ex-sar-167-131', '167/131', 'SAR', 'Eevee ex in Special Art Rare treatment.', '{}', 6),
        ('POKEMON', 'PRE', 'SEALED', 'ELITE_TRAINER_BOX', 'Prismatic Evolutions Elite Trainer Box', NULL, 'prismatic-evolutions-elite-trainer-box', NULL, NULL, '9 booster packs, sleeves, dice and a storage box.', '{}', 5),
        ('POKEMON', 'PRE', 'SEALED', 'BUNDLE', 'Prismatic Evolutions Booster Bundle', NULL, 'prismatic-evolutions-booster-bundle', NULL, NULL, 'Six Prismatic Evolutions booster packs.', '{}', 7),
        ('POKEMON', 'MEW', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Pokemon Card 151 Booster Box [JP]', 'กล่องบูสเตอร์ 151', 'pokemon-151-booster-box-jp', NULL, NULL, 'Japanese 151 booster box, 20 packs.', '{}', 40),
        ('POKEMON', 'MEW', 'BOOSTER_PACKS', 'BOOSTER_PACK', 'Pokemon Card 151 Booster Pack [JP]', 'ซองบูสเตอร์ 151', 'pokemon-151-booster-pack-jp', NULL, NULL, 'One Japanese 151 booster pack, 7 cards.', '{}', 38),
        ('POKEMON', 'MEW', 'SINGLES', 'SINGLE_CARD', 'Alakazam ex SAR', 'ฟูดิน ex', 'alakazam-ex-sar-201-165', '201/165', 'SAR', 'Special Art Rare Alakazam ex.', '{}', 36),
        ('POKEMON', 'MEW', 'SINGLES', 'SINGLE_CARD', 'Zapdos ex SAR', 'ธันเดอร์ ex', 'zapdos-ex-sar-202-165', '202/165', 'SAR', 'Special Art Rare Zapdos ex.', '{}', 35),
        ('POKEMON', 'MEW', 'SINGLES', 'SINGLE_CARD', 'Erika''s Invitation SAR', 'คำเชิญของเอริกะ', 'erikas-invitation-sar-203-165', '203/165', 'SAR', 'Supporter card in Special Art Rare.', '{}', 34),
        ('POKEMON', 'ME01', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Mega Evolution Booster Box [ENG]', NULL, 'mega-evolution-booster-box-en', NULL, NULL, '36 Mega Evolution booster packs.', '{}', 1),
        ('POKEMON', 'ME01', 'BOOSTER_PACKS', 'BOOSTER_PACK', 'Mega Evolution Booster Pack [ENG]', NULL, 'mega-evolution-booster-pack-en', NULL, NULL, 'One Mega Evolution booster pack, 10 cards.', '{}', 1),
        ('POKEMON', 'ME01', 'SINGLES', 'SINGLE_CARD', 'Mega Lucario ex SAR', 'เมก้าลูคาริโอ ex', 'mega-lucario-ex-sar-178-132', '178/132', 'SAR', 'Mega Lucario ex, Special Art Rare.', '{}', 1),
        ('POKEMON', 'ME01', 'SINGLES', 'SINGLE_CARD', 'Mega Gardevoir ex SAR', 'เมก้าเซอไนต์ ex', 'mega-gardevoir-ex-sar-179-132', '179/132', 'SAR', 'Mega Gardevoir ex, Special Art Rare.', '{"card_type":"Psychic","stage":"Stage 2"}', 2),
        ('POKEMON', 'ME01', 'SEALED', 'ELITE_TRAINER_BOX', 'Mega Evolution Elite Trainer Box', NULL, 'mega-evolution-elite-trainer-box', NULL, NULL, '9 booster packs and trainer accessories.', '{}', 2),
        ('POKEMON', NULL, 'ACCESSORY', 'ACCESSORY', 'Pikachu Electric Playmat', 'แผ่นรองเล่นพิคาชู', 'pokemon-pikachu-electric-playmat', NULL, NULL, 'Stitched-edge rubber playmat, 60 x 35 cm.', '{}', 20),
        ('POKEMON', NULL, 'ACCESSORY', 'ACCESSORY', 'Pokemon Premium Card Sleeves (65)', NULL, 'pokemon-premium-sleeves-65', NULL, NULL, '65 matte standard-size sleeves.', '{}', 22),
        ('POKEMON', NULL, 'ACCESSORY', 'ACCESSORY', 'Pegasus Perfect Fit Sleeves (100)', 'ซองใส่การ์ด Pegasus', 'pegasus-perfect-fit-sleeves', NULL, NULL, 'Inner sleeves sized for double sleeving.', '{}', 3),
        ('ONE_PIECE', 'OP09', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'ONE PIECE OP-09 Booster Box', 'กล่อง OP-09', 'op09-booster-box', NULL, NULL, 'Emperors in the New World, 24 packs.', '{}', 14),
        ('ONE_PIECE', 'OP09', 'BOOSTER_PACKS', 'BOOSTER_PACK', 'ONE PIECE OP-09 Booster Pack', 'ซอง OP-09', 'op09-booster-pack', NULL, NULL, 'One OP-09 booster pack, 12 cards.', '{}', 14),
        ('ONE_PIECE', 'OP09', 'SINGLES', 'SINGLE_CARD', 'Shanks SEC', 'แชงคูส', 'shanks-sec-op09-004', 'OP09-004', 'SEC', 'Secret Rare Shanks leader card.', '{}', 13),
        ('ONE_PIECE', 'OP09', 'SINGLES', 'SINGLE_CARD', 'Monkey.D.Luffy Gear 5 SEC', 'ลูฟี่ เกียร์ 5', 'monkey-d-luffy-sec-op09-119', 'OP09-119', 'SEC', 'Gear 5 Luffy, Secret Rare.', '{}', 12),
        ('ONE_PIECE', 'OP10', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'ONE PIECE OP-10 Royal Blood Booster Box', NULL, 'op10-royal-blood-booster-box', NULL, NULL, 'Royal Blood, 24 packs.', '{}', 8),
        ('ONE_PIECE', 'OP10', 'SINGLES', 'SINGLE_CARD', 'Trafalgar Law SR', 'ทราฟัลการ์ ลอว์', 'trafalgar-law-sr-op10-119', 'OP10-119', 'SR', 'Super Rare Trafalgar Law.', '{}', 8),
        ('ONE_PIECE', 'EB02', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'ONE PIECE EB-02 Anime 25th Collection Box', NULL, 'one-piece-eb02-anime-25th-booster-box', NULL, NULL, 'Extra booster celebrating 25 years of the anime.', '{}', 10),
        ('ONE_PIECE', 'ST21', 'SEALED', 'STARTER_DECK', 'Starter Deck EX Gear 5 [ST-21]', NULL, 'st21-gear-5-starter-deck', NULL, NULL, 'Ready-to-play 51-card deck.', '{}', 25),
        ('ONE_PIECE', NULL, 'ACCESSORY', 'ACCESSORY', 'Pegasus 9-Pocket Premium Binder', 'แฟ้มใส่การ์ด Pegasus', 'pegasus-premium-binder-9-pocket', NULL, NULL, 'Side-loading binder, 360 card capacity.', '{}', 12),
        ('MTG', 'FIN', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'FINAL FANTASY Play Booster Box', NULL, 'final-fantasy-play-booster-box', NULL, NULL, '30 Play Boosters from Magic x FINAL FANTASY.', '{}', 30),
        ('MTG', 'FIN', 'BOOSTER_PACKS', 'BOOSTER_PACK', 'FINAL FANTASY Collector Booster', NULL, 'final-fantasy-collector-booster', NULL, NULL, '15 cards, every one foil or special treatment.', '{}', 29),
        ('MTG', 'FIN', 'SINGLES', 'SINGLE_CARD', 'Cloud, Midgar Mercenary', 'คลาวด์', 'cloud-midgar-mercenary-fin-0382', '0382', 'M', 'Mythic rare legendary creature, foil.', '{}', 28),
        ('MTG', 'EOE', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Edge of Eternities Play Booster Box', NULL, 'edge-of-eternities-play-booster-box', NULL, NULL, '30 Play Boosters.', '{}', 9),
        ('MTG', 'EOE', 'SEALED', 'STARTER_DECK', 'Edge of Eternities Commander Deck', NULL, 'edge-of-eternities-commander-deck', NULL, NULL, '100-card ready-to-play Commander deck.', '{}', 9),
        ('MTG', 'CMM', 'SINGLES', 'SINGLE_CARD', 'Sol Ring', 'โซลริง', 'sol-ring-cmm-0410', '0410', 'U', 'The most-played Commander card. Nobody is selling one right now.', '{}', 45),
        ('MTG', NULL, 'ACCESSORY', 'ACCESSORY', 'Pegasus Deck Box 100+', 'กล่องใส่เด็ค Pegasus', 'pegasus-deck-box-100', NULL, NULL, 'Magnetic-lid deck box for double-sleeved decks.', '{}', 6),
        ('YUGIOH', 'RA02', 'SINGLES', 'SINGLE_CARD', 'Blue-Eyes White Dragon', 'มังกรขาวตาสีฟ้า', 'blue-eyes-white-dragon-ra02-001', 'RA02-001', 'QCSR', 'Quarter Century Secret Rare.', '{}', 26),
        ('YUGIOH', 'RA02', 'SINGLES', 'SINGLE_CARD', 'Dark Magician', 'จอมเวทย์ทมิฬ', 'dark-magician-ra02-002', 'RA02-002', 'QCSR', 'Quarter Century Secret Rare.', '{}', 26),
        ('YUGIOH', 'ALIN', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Alliance Insight Booster Box', NULL, 'alliance-insight-booster-box', NULL, NULL, '24 packs, 9 cards each.', '{}', 11),
        ('YUGIOH', 'RA02', 'BOOSTER_BOXES', 'BOOSTER_BOX', '25th Anniversary Rarity Collection II Box', NULL, 'rarity-collection-ii-booster-box', NULL, NULL, 'Sold out everywhere: kept here to test an empty market.', '{}', 33),
        ('YUGIOH', NULL, 'ACCESSORY', 'ACCESSORY', 'Pegasus Magnetic Card Case 35pt', 'เคสแม่เหล็ก Pegasus', 'pegasus-magnetic-card-case-35pt', NULL, NULL, 'UV-protected one-touch case.', '{}', 24),
        ('DIGIMON', 'BT20', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Digimon BT-20 Over the X Booster Box', NULL, 'digimon-bt20-booster-box', NULL, NULL, '24 packs of Over the X.', '{}', 16),
        ('DIGIMON', 'BT20', 'SINGLES', 'SINGLE_CARD', 'Omnimon SEC', 'โอเมก้ามอน', 'omnimon-sec-bt20-112', 'BT20-112', 'SEC', 'Secret Rare Omnimon.', '{}', 16),
        ('UNION_ARENA', 'UAJJK', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Union Arena Jujutsu Kaisen Booster Box', NULL, 'union-arena-jujutsu-kaisen-booster-box', NULL, NULL, '16 packs of Jujutsu Kaisen.', '{}', 21),
        ('UNION_ARENA', 'UAJJK', 'BOOSTER_PACKS', 'BOOSTER_PACK', 'Union Arena Jujutsu Kaisen Booster Pack', NULL, 'union-arena-jujutsu-kaisen-booster-pack', NULL, NULL, 'One pack, 8 cards.', '{}', 21),
        ('UNION_ARENA', 'UAJJK', 'SINGLES', 'SINGLE_CARD', 'Satoru Gojo SR', 'โกโจ ซาโตรุ', 'satoru-gojo-sr-uajjk-045', 'UA-JJK-045', 'SR', 'Super Rare with parallel foil.', '{}', 20),
        ('DBS_FW', 'FB05', 'BOOSTER_BOXES', 'BOOSTER_BOX', 'Fusion World FB05 New Adventure Booster Box', NULL, 'fusion-world-fb05-booster-box', NULL, NULL, '24 packs of New Adventure.', '{}', 17),
        ('DBS_FW', 'FB05', 'SINGLES', 'SINGLE_CARD', 'Son Goku SCR', 'ซง โกคู', 'son-goku-scr-fb05-118', 'FB05-118', 'SCR', 'Secret Rare Son Goku.', '{}', 17)
       ) AS v(game_code, set_code, category_code, product_type, name, name_local, slug,
              card_number, rarity_code, description, attributes, days_ago)
  JOIN game g ON g.code = v.game_code
  JOIN catalog_category c ON c.code = v.category_code AND c.game_id IS NULL
  LEFT JOIN card_set s ON s.game_id = g.id AND s.code = v.set_code
 WHERE NOT EXISTS (SELECT 1 FROM catalog_product p WHERE p.slug = v.slug);

-- One printing each. The second test skips a product that already has this
-- printing under another SKU, which would otherwise break ux_catalog_variant_identity.
INSERT INTO catalog_variant (catalog_product_id, sku, language_code, finish, edition)
SELECT p.id, v.sku, v.language_code, v.finish, v.edition
  FROM (VALUES
        ('pikachu-ex-025-187', 'POKEMON-SV8A-025-187-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('charizard-ex-006-165', 'POKEMON-SV8A-006-165-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('mew-ex-151-165', 'POKEMON-SV8A-151-165-JP-NORMAL', 'JP', 'NORMAL', 'UNLIMITED'),
        ('terastal-festival-booster-box', 'POKEMON-SV8A-BOX-JP', 'JP', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('umbreon-ex-sir-161-131', 'POKEMON-PRE-161-131-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('sylveon-ex-sir-156-131', 'POKEMON-PRE-156-131-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('eevee-ex-sar-167-131', 'POKEMON-PRE-167-131-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('prismatic-evolutions-elite-trainer-box', 'POKEMON-PRE-ETB-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('prismatic-evolutions-booster-bundle', 'POKEMON-PRE-BUNDLE-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pokemon-151-booster-box-jp', 'POKEMON-MEW-BOX-JP', 'JP', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pokemon-151-booster-pack-jp', 'POKEMON-MEW-PACK-JP', 'JP', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('alakazam-ex-sar-201-165', 'POKEMON-MEW-201-165-JP-NORMAL', 'JP', 'NORMAL', 'UNLIMITED'),
        ('zapdos-ex-sar-202-165', 'POKEMON-MEW-202-165-JP-NORMAL', 'JP', 'NORMAL', 'UNLIMITED'),
        ('erikas-invitation-sar-203-165', 'POKEMON-MEW-203-165-JP-NORMAL', 'JP', 'NORMAL', 'UNLIMITED'),
        ('mega-evolution-booster-box-en', 'POKEMON-ME01-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('mega-evolution-booster-pack-en', 'POKEMON-ME01-PACK-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('mega-lucario-ex-sar-178-132', 'POKEMON-ME01-178-132-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('mega-gardevoir-ex-sar-179-132', 'POKEMON-ME01-179-132-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('mega-evolution-elite-trainer-box', 'POKEMON-ME01-ETB-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pokemon-pikachu-electric-playmat', 'POKEMON-ACC-PLAYMAT-PIKACHU', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pokemon-premium-sleeves-65', 'POKEMON-ACC-SLEEVES-65', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pegasus-perfect-fit-sleeves', 'PEGASUS-ACC-PERFECT-FIT-100', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('op09-booster-box', 'ONEPIECE-OP09-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('op09-booster-pack', 'ONEPIECE-OP09-PACK-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('shanks-sec-op09-004', 'ONEPIECE-OP09-004-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('monkey-d-luffy-sec-op09-119', 'ONEPIECE-OP09-119-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('op10-royal-blood-booster-box', 'ONEPIECE-OP10-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('trafalgar-law-sr-op10-119', 'ONEPIECE-OP10-119-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('one-piece-eb02-anime-25th-booster-box', 'ONEPIECE-EB02-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('st21-gear-5-starter-deck', 'ONEPIECE-ST21-DECK-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pegasus-premium-binder-9-pocket', 'PEGASUS-ACC-BINDER-9', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('final-fantasy-play-booster-box', 'MTG-FIN-PLAY-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('final-fantasy-collector-booster', 'MTG-FIN-COLLECTOR-PACK-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('cloud-midgar-mercenary-fin-0382', 'MTG-FIN-0382-EN-FOIL', 'EN', 'FOIL', 'UNLIMITED'),
        ('edge-of-eternities-play-booster-box', 'MTG-EOE-PLAY-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('edge-of-eternities-commander-deck', 'MTG-EOE-COMMANDER-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('sol-ring-cmm-0410', 'MTG-CMM-0410-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('pegasus-deck-box-100', 'PEGASUS-ACC-DECKBOX-100', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('blue-eyes-white-dragon-ra02-001', 'YUGIOH-RA02-001-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('dark-magician-ra02-002', 'YUGIOH-RA02-002-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('alliance-insight-booster-box', 'YUGIOH-ALIN-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('rarity-collection-ii-booster-box', 'YUGIOH-RA02-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('pegasus-magnetic-card-case-35pt', 'PEGASUS-ACC-MAGCASE-35', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('digimon-bt20-booster-box', 'DIGIMON-BT20-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('omnimon-sec-bt20-112', 'DIGIMON-BT20-112-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('union-arena-jujutsu-kaisen-booster-box', 'UNIONARENA-JJK-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('union-arena-jujutsu-kaisen-booster-pack', 'UNIONARENA-JJK-PACK-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('satoru-gojo-sr-uajjk-045', 'UNIONARENA-JJK-045-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED'),
        ('fusion-world-fb05-booster-box', 'DBSFW-FB05-BOX-EN', 'EN', 'NOT_APPLICABLE', 'NOT_APPLICABLE'),
        ('son-goku-scr-fb05-118', 'DBSFW-FB05-118-EN-NORMAL', 'EN', 'NORMAL', 'UNLIMITED')
       ) AS v(slug, sku, language_code, finish, edition)
  JOIN catalog_product p ON p.slug = v.slug
 WHERE NOT EXISTS (SELECT 1 FROM catalog_variant cv WHERE cv.sku = v.sku)
   AND NOT EXISTS (
        SELECT 1 FROM catalog_variant cv
         WHERE cv.catalog_product_id = p.id
           AND cv.language_code = v.language_code
           AND cv.finish = v.finish
           AND cv.edition = v.edition
           AND cv.printing_note IS NULL);

-- The art. A product that already has a primary image keeps it.
INSERT INTO catalog_image (catalog_product_id, image_key, alt_text, sort_order, is_primary)
SELECT p.id, v.image_key, v.alt_text, 0, true
  FROM (VALUES
        ('pikachu-ex-025-187', 'seed/products/pikachu-ex-025-187.jpg', 'Pikachu ex'),
        ('charizard-ex-006-165', 'seed/products/charizard-ex-006-165.jpg', 'Charizard ex'),
        ('mew-ex-151-165', 'seed/products/mew-ex-151-165.jpg', 'Mew ex'),
        ('terastal-festival-booster-box', 'seed/products/terastal-festival-booster-box.jpg', 'Terastal Festival Booster Box'),
        ('umbreon-ex-sir-161-131', 'seed/products/umbreon-ex-sir-161-131.jpg', 'Umbreon ex SIR'),
        ('sylveon-ex-sir-156-131', 'seed/products/sylveon-ex-sir-156-131.jpg', 'Sylveon ex SIR'),
        ('eevee-ex-sar-167-131', 'seed/products/eevee-ex-sar-167-131.jpg', 'Eevee ex SAR'),
        ('prismatic-evolutions-elite-trainer-box', 'seed/products/prismatic-evolutions-elite-trainer-box.jpg', 'Prismatic Evolutions Elite Trainer Box'),
        ('prismatic-evolutions-booster-bundle', 'seed/products/prismatic-evolutions-booster-bundle.jpg', 'Prismatic Evolutions Booster Bundle'),
        ('pokemon-151-booster-box-jp', 'seed/products/pokemon-151-booster-box-jp.jpg', 'Pokemon Card 151 Booster Box [JP]'),
        ('pokemon-151-booster-pack-jp', 'seed/products/pokemon-151-booster-pack-jp.jpg', 'Pokemon Card 151 Booster Pack [JP]'),
        ('alakazam-ex-sar-201-165', 'seed/products/alakazam-ex-sar-201-165.jpg', 'Alakazam ex SAR'),
        ('zapdos-ex-sar-202-165', 'seed/products/zapdos-ex-sar-202-165.jpg', 'Zapdos ex SAR'),
        ('erikas-invitation-sar-203-165', 'seed/products/erikas-invitation-sar-203-165.jpg', 'Erika''s Invitation SAR'),
        ('mega-evolution-booster-box-en', 'seed/products/mega-evolution-booster-box-en.jpg', 'Mega Evolution Booster Box [ENG]'),
        ('mega-evolution-booster-pack-en', 'seed/products/mega-evolution-booster-pack-en.jpg', 'Mega Evolution Booster Pack [ENG]'),
        ('mega-lucario-ex-sar-178-132', 'seed/products/mega-lucario-ex-sar-178-132.jpg', 'Mega Lucario ex SAR'),
        ('mega-gardevoir-ex-sar-179-132', 'seed/products/mega-gardevoir-ex-sar-179-132.jpg', 'Mega Gardevoir ex SAR'),
        ('mega-evolution-elite-trainer-box', 'seed/products/mega-evolution-elite-trainer-box.jpg', 'Mega Evolution Elite Trainer Box'),
        ('pokemon-pikachu-electric-playmat', 'seed/products/pokemon-pikachu-electric-playmat.jpg', 'Pikachu Electric Playmat'),
        ('pokemon-premium-sleeves-65', 'seed/products/pokemon-premium-sleeves-65.jpg', 'Pokemon Premium Card Sleeves (65)'),
        ('pegasus-perfect-fit-sleeves', 'seed/products/pegasus-perfect-fit-sleeves.jpg', 'Pegasus Perfect Fit Sleeves (100)'),
        ('op09-booster-box', 'seed/products/op09-booster-box.jpg', 'ONE PIECE OP-09 Booster Box'),
        ('op09-booster-pack', 'seed/products/op09-booster-pack.jpg', 'ONE PIECE OP-09 Booster Pack'),
        ('shanks-sec-op09-004', 'seed/products/shanks-sec-op09-004.jpg', 'Shanks SEC'),
        ('monkey-d-luffy-sec-op09-119', 'seed/products/monkey-d-luffy-sec-op09-119.jpg', 'Monkey.D.Luffy Gear 5 SEC'),
        ('op10-royal-blood-booster-box', 'seed/products/op10-royal-blood-booster-box.jpg', 'ONE PIECE OP-10 Royal Blood Booster Box'),
        ('trafalgar-law-sr-op10-119', 'seed/products/trafalgar-law-sr-op10-119.jpg', 'Trafalgar Law SR'),
        ('one-piece-eb02-anime-25th-booster-box', 'seed/products/one-piece-eb02-anime-25th-booster-box.jpg', 'ONE PIECE EB-02 Anime 25th Collection Box'),
        ('st21-gear-5-starter-deck', 'seed/products/st21-gear-5-starter-deck.jpg', 'Starter Deck EX Gear 5 [ST-21]'),
        ('pegasus-premium-binder-9-pocket', 'seed/products/pegasus-premium-binder-9-pocket.jpg', 'Pegasus 9-Pocket Premium Binder'),
        ('final-fantasy-play-booster-box', 'seed/products/final-fantasy-play-booster-box.jpg', 'FINAL FANTASY Play Booster Box'),
        ('final-fantasy-collector-booster', 'seed/products/final-fantasy-collector-booster.jpg', 'FINAL FANTASY Collector Booster'),
        ('cloud-midgar-mercenary-fin-0382', 'seed/products/cloud-midgar-mercenary-fin-0382.jpg', 'Cloud, Midgar Mercenary'),
        ('edge-of-eternities-play-booster-box', 'seed/products/edge-of-eternities-play-booster-box.jpg', 'Edge of Eternities Play Booster Box'),
        ('edge-of-eternities-commander-deck', 'seed/products/edge-of-eternities-commander-deck.jpg', 'Edge of Eternities Commander Deck'),
        ('sol-ring-cmm-0410', 'seed/products/sol-ring-cmm-0410.jpg', 'Sol Ring'),
        ('pegasus-deck-box-100', 'seed/products/pegasus-deck-box-100.jpg', 'Pegasus Deck Box 100+'),
        ('blue-eyes-white-dragon-ra02-001', 'seed/products/blue-eyes-white-dragon-ra02-001.jpg', 'Blue-Eyes White Dragon'),
        ('dark-magician-ra02-002', 'seed/products/dark-magician-ra02-002.jpg', 'Dark Magician'),
        ('alliance-insight-booster-box', 'seed/products/alliance-insight-booster-box.jpg', 'Alliance Insight Booster Box'),
        ('rarity-collection-ii-booster-box', 'seed/products/rarity-collection-ii-booster-box.jpg', '25th Anniversary Rarity Collection II Box'),
        ('pegasus-magnetic-card-case-35pt', 'seed/products/pegasus-magnetic-card-case-35pt.jpg', 'Pegasus Magnetic Card Case 35pt'),
        ('digimon-bt20-booster-box', 'seed/products/digimon-bt20-booster-box.jpg', 'Digimon BT-20 Over the X Booster Box'),
        ('omnimon-sec-bt20-112', 'seed/products/omnimon-sec-bt20-112.jpg', 'Omnimon SEC'),
        ('union-arena-jujutsu-kaisen-booster-box', 'seed/products/union-arena-jujutsu-kaisen-booster-box.jpg', 'Union Arena Jujutsu Kaisen Booster Box'),
        ('union-arena-jujutsu-kaisen-booster-pack', 'seed/products/union-arena-jujutsu-kaisen-booster-pack.jpg', 'Union Arena Jujutsu Kaisen Booster Pack'),
        ('satoru-gojo-sr-uajjk-045', 'seed/products/satoru-gojo-sr-uajjk-045.jpg', 'Satoru Gojo SR'),
        ('fusion-world-fb05-booster-box', 'seed/products/fusion-world-fb05-booster-box.jpg', 'Fusion World FB05 New Adventure Booster Box'),
        ('son-goku-scr-fb05-118', 'seed/products/son-goku-scr-fb05-118.jpg', 'Son Goku SCR')
       ) AS v(slug, image_key, alt_text)
  JOIN catalog_product p ON p.slug = v.slug
 WHERE NOT EXISTS (
        SELECT 1 FROM catalog_image i WHERE i.catalog_product_id = p.id AND i.is_primary);

-- ---------- accounts ----------

INSERT INTO user_account (email, password_hash, username, display_name, bio, status)
SELECT v.email, '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU', v.username, v.display_name, v.bio, 'ACTIVE'
  FROM (VALUES
        ('store@pegasus.example.com', 'pegasus_official', 'Pegasus Official', 'The official Pegasus store. Every item is checked and shipped by our team.'),
        ('cardkingdom@example.com', 'card_kingdom_th', 'Card Kingdom TH', 'Singles and sealed from Bangkok, shipped next day.'),
        ('tcgcorner@example.com', 'tcg_corner', 'TCG Corner', 'One Piece and Pokemon specialists.'),
        ('mintvault@example.com', 'mint_vault', 'Mint Vault', 'Graded and high-end singles.'),
        ('mai@example.com', 'collector_mai', 'Mai Collector', NULL),
        ('ton@example.com', 'deckbuilder_ton', 'Ton Deckbuilder', NULL)
       ) AS v(email, username, display_name, bio)
 WHERE NOT EXISTS (
        SELECT 1 FROM user_account u WHERE u.email = v.email OR u.username = v.username);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM (VALUES
        ('pegasus_official', 'BUYER'),
        ('card_kingdom_th', 'BUYER'),
        ('tcg_corner', 'BUYER'),
        ('mint_vault', 'BUYER'),
        ('pegasus_official', 'SELLER'),
        ('card_kingdom_th', 'SELLER'),
        ('tcg_corner', 'SELLER'),
        ('mint_vault', 'SELLER'),
        ('collector_mai', 'BUYER'),
        ('deckbuilder_ton', 'BUYER')
       ) AS v(username, role_code)
  JOIN user_account u ON u.username = v.username
  JOIN app_role r ON r.code = v.role_code
 WHERE NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO seller_profile (user_id, status, verified_at, handling_days, vacation_mode, auto_accept_orders)
SELECT u.id, 'VERIFIED', now(), v.handling_days, false, true
  FROM (VALUES
        ('pegasus_official', 1),
        ('card_kingdom_th', 2),
        ('tcg_corner', 2),
        ('mint_vault', 2)
       ) AS v(username, handling_days)
  JOIN user_account u ON u.username = v.username
 WHERE NOT EXISTS (SELECT 1 FROM seller_profile sp WHERE sp.user_id = u.id);

INSERT INTO seller_shipping_option (seller_profile_id, name, carrier_code, base_fee, per_item_fee,
                                    free_threshold, est_days_min, est_days_max, display_order)
SELECT sp.id, v.name, v.carrier_code, v.base_fee, v.per_item_fee, v.free_threshold,
       v.est_days_min::smallint, v.est_days_max::smallint, v.display_order::smallint
  FROM (VALUES
        ('Kerry Express', 'KERRY', 50.00, 10.00, 3000.00, 1, 2, 1),
        ('Thailand Post EMS', 'THP', 40.00, 5.00, NULL::numeric, 2, 4, 2)
       ) AS v(name, carrier_code, base_fee, per_item_fee, free_threshold, est_days_min, est_days_max, display_order)
  CROSS JOIN seller_profile sp
  JOIN user_account u ON u.id = sp.user_id
 WHERE u.username IN ('pegasus_official', 'card_kingdom_th', 'tcg_corner', 'mint_vault')
   AND NOT EXISTS (
        SELECT 1 FROM seller_shipping_option o
         WHERE o.seller_profile_id = sp.id AND o.name = v.name);

INSERT INTO address (user_id, label, recipient_name, phone, line1, subdistrict, district, province,
                     postal_code, country_code, is_default_shipping, is_default_billing)
SELECT u.id, 'Home', v.recipient_name, v.phone, v.line1, v.subdistrict, v.district, v.province,
       v.postal_code, 'TH', true, true
  FROM (VALUES
        ('collector_mai', 'Mai Srisuk', '+66811112222', '88 Sukhumvit 55', 'Khlong Tan Nuea', 'Watthana', 'Bangkok', '10110'),
        ('deckbuilder_ton', 'Ton Wongsa', '+66823334444', '12 Nimmanhaemin Rd', 'Suthep', 'Mueang Chiang Mai', 'Chiang Mai', '50200')
       ) AS v(username, recipient_name, phone, line1, subdistrict, district, province, postal_code)
  JOIN user_account u ON u.username = v.username
 WHERE NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = u.id AND a.line1 = v.line1);

-- ---------- listings and stock ----------

INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, grading_company, grade_value,
                     price, currency, pricing_mode, status, lot_label, public_note, published_at, created_at)
SELECT sp.id, cv.id, v.condition_code, v.grading_company, v.grade_value::numeric,
       v.price::numeric, 'THB', 'MANUAL', 'ACTIVE', 'seed:home', v.public_note,
       now() - make_interval(days => v.days_ago), now() - make_interval(days => v.days_ago)
  FROM (VALUES
        ('card_kingdom_th', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', '890.00', 59, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', '940.00', 58, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', '860.00', 57, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-SV8A-025-187-EN-NORMAL', 'LP', '730.00', 57, NULL, NULL, 'Light edge wear, see photos.'),
        ('tcg_corner', 'POKEMON-SV8A-006-165-EN-NORMAL', 'NM', '4200.00', 57, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-SV8A-006-165-EN-NORMAL', 'NM', '4450.00', 56, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-SV8A-006-165-EN-NORMAL', 'LP', '3440.00', 55, NULL, NULL, 'Light edge wear, see photos.'),
        ('mint_vault', 'POKEMON-SV8A-151-165-JP-NORMAL', 'NM', '1650.00', 56, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-SV8A-151-165-JP-NORMAL', 'NM', '1750.00', 55, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-SV8A-151-165-JP-NORMAL', 'NM', '1600.00', 54, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-SV8A-151-165-JP-NORMAL', 'LP', '1350.00', 54, NULL, NULL, 'Light edge wear, see photos.'),
        ('tcg_corner', 'POKEMON-SV8A-BOX-JP', 'SEALED', '3010.00', 54, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-SV8A-BOX-JP', 'SEALED', '3090.00', 53, NULL, NULL, NULL),
        ('pegasus_official', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', '8900.00', 2, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('tcg_corner', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', '8900.00', 2, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', '9430.00', 1, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', '8630.00', 0, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-PRE-161-131-EN-NORMAL', 'LP', '7300.00', 0, NULL, NULL, 'Light edge wear, see photos.'),
        ('mint_vault', 'POKEMON-PRE-156-131-EN-NORMAL', 'NM', '3200.00', 3, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-PRE-156-131-EN-NORMAL', 'NM', '3390.00', 2, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-PRE-156-131-EN-NORMAL', 'LP', '2620.00', 1, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'POKEMON-PRE-167-131-EN-NORMAL', 'NM', '1450.00', 5, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-PRE-167-131-EN-NORMAL', 'NM', '1540.00', 4, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-PRE-167-131-EN-NORMAL', 'NM', '1410.00', 3, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-PRE-167-131-EN-NORMAL', 'LP', '1190.00', 3, NULL, NULL, 'Light edge wear, see photos.'),
        ('pegasus_official', 'POKEMON-PRE-ETB-EN', 'SEALED', '3290.00', 4, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('tcg_corner', 'POKEMON-PRE-ETB-EN', 'SEALED', '3420.00', 4, NULL, NULL, NULL),
        ('pegasus_official', 'POKEMON-PRE-BUNDLE-EN', 'SEALED', '1890.00', 6, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('card_kingdom_th', 'POKEMON-PRE-BUNDLE-EN', 'SEALED', '1970.00', 6, NULL, NULL, NULL),
        ('pegasus_official', 'POKEMON-MEW-BOX-JP', 'SEALED', '5490.00', 39, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('tcg_corner', 'POKEMON-MEW-BOX-JP', 'SEALED', '5710.00', 39, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-MEW-PACK-JP', 'SEALED', '300.00', 37, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-MEW-PACK-JP', 'SEALED', '310.00', 36, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-MEW-201-165-JP-NORMAL', 'NM', '1290.00', 35, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-MEW-201-165-JP-NORMAL', 'NM', '1370.00', 34, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-MEW-201-165-JP-NORMAL', 'LP', '1060.00', 33, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'POKEMON-MEW-202-165-JP-NORMAL', 'NM', '990.00', 34, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-MEW-202-165-JP-NORMAL', 'NM', '1050.00', 33, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-MEW-202-165-JP-NORMAL', 'NM', '960.00', 32, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-MEW-202-165-JP-NORMAL', 'LP', '810.00', 32, NULL, NULL, 'Light edge wear, see photos.'),
        ('tcg_corner', 'POKEMON-MEW-203-165-JP-NORMAL', 'NM', '1590.00', 33, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-MEW-203-165-JP-NORMAL', 'NM', '1690.00', 32, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-MEW-203-165-JP-NORMAL', 'LP', '1300.00', 31, NULL, NULL, 'Light edge wear, see photos.'),
        ('pegasus_official', 'POKEMON-ME01-BOX-EN', 'SEALED', '5590.00', 0, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('card_kingdom_th', 'POKEMON-ME01-BOX-EN', 'SEALED', '5810.00', 0, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-ME01-PACK-EN', 'SEALED', '200.00', 0, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-ME01-PACK-EN', 'SEALED', '200.00', 0, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', '2450.00', 0, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', '2600.00', 0, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', '2380.00', 0, NULL, NULL, NULL),
        ('mint_vault', 'POKEMON-ME01-178-132-EN-NORMAL', 'LP', '2010.00', 0, NULL, NULL, 'Light edge wear, see photos.'),
        ('mint_vault', 'POKEMON-ME01-179-132-EN-NORMAL', 'NM', '1890.00', 1, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-ME01-179-132-EN-NORMAL', 'NM', '2000.00', 0, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-ME01-179-132-EN-NORMAL', 'LP', '1550.00', 0, NULL, NULL, 'Light edge wear, see photos.'),
        ('pegasus_official', 'POKEMON-ME01-ETB-EN', 'SEALED', '2590.00', 1, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('card_kingdom_th', 'POKEMON-ME01-ETB-EN', 'SEALED', '2690.00', 1, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-ACC-PLAYMAT-PIKACHU', 'SEALED', '720.00', 19, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-ACC-PLAYMAT-PIKACHU', 'SEALED', '740.00', 18, NULL, NULL, NULL),
        ('card_kingdom_th', 'POKEMON-ACC-SLEEVES-65', 'SEALED', '300.00', 21, NULL, NULL, NULL),
        ('tcg_corner', 'POKEMON-ACC-SLEEVES-65', 'SEALED', '310.00', 20, NULL, NULL, NULL),
        ('pegasus_official', 'PEGASUS-ACC-PERFECT-FIT-100', 'SEALED', '280.00', 2, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('pegasus_official', 'ONEPIECE-OP09-BOX-EN', 'SEALED', '3950.00', 13, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('card_kingdom_th', 'ONEPIECE-OP09-BOX-EN', 'SEALED', '4110.00', 13, NULL, NULL, NULL),
        ('tcg_corner', 'ONEPIECE-OP09-PACK-EN', 'SEALED', '170.00', 13, NULL, NULL, NULL),
        ('card_kingdom_th', 'ONEPIECE-OP09-PACK-EN', 'SEALED', '180.00', 12, NULL, NULL, NULL),
        ('card_kingdom_th', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', '2890.00', 12, NULL, NULL, NULL),
        ('tcg_corner', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', '3060.00', 11, NULL, NULL, NULL),
        ('mint_vault', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', '2800.00', 10, NULL, NULL, NULL),
        ('tcg_corner', 'ONEPIECE-OP09-004-EN-NORMAL', 'LP', '2370.00', 10, NULL, NULL, 'Light edge wear, see photos.'),
        ('tcg_corner', 'ONEPIECE-OP09-119-EN-NORMAL', 'NM', '5200.00', 11, NULL, NULL, NULL),
        ('mint_vault', 'ONEPIECE-OP09-119-EN-NORMAL', 'NM', '5510.00', 10, NULL, NULL, NULL),
        ('mint_vault', 'ONEPIECE-OP09-119-EN-NORMAL', 'LP', '4260.00', 9, NULL, NULL, 'Light edge wear, see photos.'),
        ('pegasus_official', 'ONEPIECE-OP10-BOX-EN', 'SEALED', '4290.00', 7, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('card_kingdom_th', 'ONEPIECE-OP10-BOX-EN', 'SEALED', '4460.00', 7, NULL, NULL, NULL),
        ('card_kingdom_th', 'ONEPIECE-OP10-119-EN-NORMAL', 'NM', '690.00', 7, NULL, NULL, NULL),
        ('tcg_corner', 'ONEPIECE-OP10-119-EN-NORMAL', 'NM', '730.00', 6, NULL, NULL, NULL),
        ('tcg_corner', 'ONEPIECE-OP10-119-EN-NORMAL', 'LP', '570.00', 5, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'ONEPIECE-EB02-BOX-EN', 'SEALED', '3110.00', 9, NULL, NULL, NULL),
        ('tcg_corner', 'ONEPIECE-EB02-BOX-EN', 'SEALED', '3200.00', 8, NULL, NULL, NULL),
        ('pegasus_official', 'ONEPIECE-ST21-DECK-EN', 'SEALED', '590.00', 24, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('tcg_corner', 'ONEPIECE-ST21-DECK-EN', 'SEALED', '610.00', 24, NULL, NULL, NULL),
        ('pegasus_official', 'PEGASUS-ACC-BINDER-9', 'SEALED', '890.00', 11, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('pegasus_official', 'MTG-FIN-PLAY-BOX-EN', 'SEALED', '6990.00', 29, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('tcg_corner', 'MTG-FIN-PLAY-BOX-EN', 'SEALED', '7270.00', 29, NULL, NULL, NULL),
        ('card_kingdom_th', 'MTG-FIN-COLLECTOR-PACK-EN', 'SEALED', '1340.00', 28, NULL, NULL, NULL),
        ('tcg_corner', 'MTG-FIN-COLLECTOR-PACK-EN', 'SEALED', '1380.00', 27, NULL, NULL, NULL),
        ('card_kingdom_th', 'MTG-FIN-0382-EN-FOIL', 'NM', '1890.00', 27, NULL, NULL, NULL),
        ('tcg_corner', 'MTG-FIN-0382-EN-FOIL', 'NM', '2000.00', 26, NULL, NULL, NULL),
        ('tcg_corner', 'MTG-FIN-0382-EN-FOIL', 'LP', '1550.00', 25, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'MTG-EOE-PLAY-BOX-EN', 'SEALED', '5710.00', 8, NULL, NULL, NULL),
        ('tcg_corner', 'MTG-EOE-PLAY-BOX-EN', 'SEALED', '5870.00', 7, NULL, NULL, NULL),
        ('tcg_corner', 'MTG-EOE-COMMANDER-EN', 'SEALED', '1760.00', 8, NULL, NULL, NULL),
        ('card_kingdom_th', 'MTG-EOE-COMMANDER-EN', 'SEALED', '1810.00', 7, NULL, NULL, NULL),
        ('pegasus_official', 'PEGASUS-ACC-DECKBOX-100', 'SEALED', '450.00', 5, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('mint_vault', 'YUGIOH-RA02-001-EN-NORMAL', 'NM', '5990.00', 25, NULL, NULL, NULL),
        ('card_kingdom_th', 'YUGIOH-RA02-001-EN-NORMAL', 'NM', '6350.00', 24, NULL, NULL, NULL),
        ('tcg_corner', 'YUGIOH-RA02-001-EN-NORMAL', 'NM', '5810.00', 23, NULL, NULL, NULL),
        ('card_kingdom_th', 'YUGIOH-RA02-001-EN-NORMAL', 'LP', '4910.00', 23, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'YUGIOH-RA02-002-EN-NORMAL', 'NM', '3490.00', 25, NULL, NULL, NULL),
        ('tcg_corner', 'YUGIOH-RA02-002-EN-NORMAL', 'NM', '3700.00', 24, NULL, NULL, NULL),
        ('tcg_corner', 'YUGIOH-RA02-002-EN-NORMAL', 'LP', '2860.00', 23, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'YUGIOH-ALIN-BOX-EN', 'SEALED', '3010.00', 10, NULL, NULL, NULL),
        ('tcg_corner', 'YUGIOH-ALIN-BOX-EN', 'SEALED', '3090.00', 9, NULL, NULL, NULL),
        ('pegasus_official', 'PEGASUS-ACC-MAGCASE-35', 'SEALED', '120.00', 23, NULL, NULL, 'Checked and shipped by Pegasus.'),
        ('tcg_corner', 'DIGIMON-BT20-BOX-EN', 'SEALED', '2800.00', 15, NULL, NULL, NULL),
        ('card_kingdom_th', 'DIGIMON-BT20-BOX-EN', 'SEALED', '2880.00', 14, NULL, NULL, NULL),
        ('mint_vault', 'DIGIMON-BT20-112-EN-NORMAL', 'NM', '1490.00', 15, NULL, NULL, NULL),
        ('card_kingdom_th', 'DIGIMON-BT20-112-EN-NORMAL', 'NM', '1580.00', 14, NULL, NULL, NULL),
        ('tcg_corner', 'DIGIMON-BT20-112-EN-NORMAL', 'NM', '1450.00', 13, NULL, NULL, NULL),
        ('card_kingdom_th', 'DIGIMON-BT20-112-EN-NORMAL', 'LP', '1220.00', 13, NULL, NULL, 'Light edge wear, see photos.'),
        ('tcg_corner', 'UNIONARENA-JJK-BOX-EN', 'SEALED', '1860.00', 20, NULL, NULL, NULL),
        ('card_kingdom_th', 'UNIONARENA-JJK-BOX-EN', 'SEALED', '1920.00', 19, NULL, NULL, NULL),
        ('card_kingdom_th', 'UNIONARENA-JJK-PACK-EN', 'SEALED', '120.00', 20, NULL, NULL, NULL),
        ('tcg_corner', 'UNIONARENA-JJK-PACK-EN', 'SEALED', '130.00', 19, NULL, NULL, NULL),
        ('mint_vault', 'UNIONARENA-JJK-045-EN-NORMAL', 'NM', '1190.00', 19, NULL, NULL, NULL),
        ('card_kingdom_th', 'UNIONARENA-JJK-045-EN-NORMAL', 'NM', '1260.00', 18, NULL, NULL, NULL),
        ('card_kingdom_th', 'UNIONARENA-JJK-045-EN-NORMAL', 'LP', '980.00', 17, NULL, NULL, 'Light edge wear, see photos.'),
        ('card_kingdom_th', 'DBSFW-FB05-BOX-EN', 'SEALED', '3110.00', 16, NULL, NULL, NULL),
        ('tcg_corner', 'DBSFW-FB05-BOX-EN', 'SEALED', '3200.00', 15, NULL, NULL, NULL),
        ('tcg_corner', 'DBSFW-FB05-118-EN-NORMAL', 'NM', '2290.00', 16, NULL, NULL, NULL),
        ('mint_vault', 'DBSFW-FB05-118-EN-NORMAL', 'NM', '2430.00', 15, NULL, NULL, NULL),
        ('mint_vault', 'DBSFW-FB05-118-EN-NORMAL', 'LP', '1880.00', 14, NULL, NULL, 'Light edge wear, see photos.'),
        ('mint_vault', 'POKEMON-SV8A-006-165-EN-NORMAL', 'NM', '18900.00', 20, 'PSA', 10, 'PSA 10 Gem Mint slab.'),
        ('tcg_corner', 'ONEPIECE-OP09-BOX-EN', 'SEALED', '4030.00', 14, NULL, NULL, NULL)
       ) AS v(username, sku, condition_code, price, days_ago, grading_company, grade_value, public_note)
  JOIN user_account u ON u.username = v.username
  JOIN seller_profile sp ON sp.user_id = u.id
  JOIN catalog_variant cv ON cv.sku = v.sku
 WHERE NOT EXISTS (
        SELECT 1 FROM listing l
         WHERE l.seller_profile_id = sp.id
           AND l.catalog_variant_id = cv.id
           AND l.condition_code = v.condition_code
           AND l.grading_company IS NOT DISTINCT FROM v.grading_company
           AND l.lot_label = 'seed:home');

-- The cards behind each listing. The trigger on listing_unit fills in the
-- listing's quantities, so a listing only has stock once this has run.
INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code, listing_id, status,
                          acquisition_cost, acquired_at)
SELECT l.seller_profile_id, l.catalog_variant_id, l.condition_code, l.id, 'LISTED',
       round(l.price * 0.6, 2), l.published_at
  FROM (VALUES
        ('card_kingdom_th', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-SV8A-025-187-EN-NORMAL', 'NM', NULL, 3),
        ('tcg_corner', 'POKEMON-SV8A-025-187-EN-NORMAL', 'LP', NULL, 1),
        ('tcg_corner', 'POKEMON-SV8A-006-165-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-SV8A-006-165-EN-NORMAL', 'NM', NULL, 3),
        ('mint_vault', 'POKEMON-SV8A-006-165-EN-NORMAL', 'LP', NULL, 1),
        ('mint_vault', 'POKEMON-SV8A-151-165-JP-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'POKEMON-SV8A-151-165-JP-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'POKEMON-SV8A-151-165-JP-NORMAL', 'NM', NULL, 2),
        ('card_kingdom_th', 'POKEMON-SV8A-151-165-JP-NORMAL', 'LP', NULL, 1),
        ('tcg_corner', 'POKEMON-SV8A-BOX-JP', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'POKEMON-SV8A-BOX-JP', 'SEALED', NULL, 2),
        ('pegasus_official', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', NULL, 8),
        ('tcg_corner', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', NULL, 1),
        ('mint_vault', 'POKEMON-PRE-161-131-EN-NORMAL', 'LP', NULL, 1),
        ('mint_vault', 'POKEMON-PRE-156-131-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'POKEMON-PRE-156-131-EN-NORMAL', 'NM', NULL, 1),
        ('card_kingdom_th', 'POKEMON-PRE-156-131-EN-NORMAL', 'LP', NULL, 1),
        ('card_kingdom_th', 'POKEMON-PRE-167-131-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'POKEMON-PRE-167-131-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-PRE-167-131-EN-NORMAL', 'NM', NULL, 3),
        ('tcg_corner', 'POKEMON-PRE-167-131-EN-NORMAL', 'LP', NULL, 1),
        ('pegasus_official', 'POKEMON-PRE-ETB-EN', 'SEALED', NULL, 11),
        ('tcg_corner', 'POKEMON-PRE-ETB-EN', 'SEALED', NULL, 5),
        ('pegasus_official', 'POKEMON-PRE-BUNDLE-EN', 'SEALED', NULL, 12),
        ('card_kingdom_th', 'POKEMON-PRE-BUNDLE-EN', 'SEALED', NULL, 2),
        ('pegasus_official', 'POKEMON-MEW-BOX-JP', 'SEALED', NULL, 4),
        ('tcg_corner', 'POKEMON-MEW-BOX-JP', 'SEALED', NULL, 3),
        ('card_kingdom_th', 'POKEMON-MEW-PACK-JP', 'SEALED', NULL, 4),
        ('tcg_corner', 'POKEMON-MEW-PACK-JP', 'SEALED', NULL, 5),
        ('mint_vault', 'POKEMON-MEW-201-165-JP-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'POKEMON-MEW-201-165-JP-NORMAL', 'NM', NULL, 1),
        ('card_kingdom_th', 'POKEMON-MEW-201-165-JP-NORMAL', 'LP', NULL, 1),
        ('card_kingdom_th', 'POKEMON-MEW-202-165-JP-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'POKEMON-MEW-202-165-JP-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-MEW-202-165-JP-NORMAL', 'NM', NULL, 3),
        ('tcg_corner', 'POKEMON-MEW-202-165-JP-NORMAL', 'LP', NULL, 1),
        ('tcg_corner', 'POKEMON-MEW-203-165-JP-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-MEW-203-165-JP-NORMAL', 'NM', NULL, 3),
        ('mint_vault', 'POKEMON-MEW-203-165-JP-NORMAL', 'LP', NULL, 1),
        ('pegasus_official', 'POKEMON-ME01-BOX-EN', 'SEALED', NULL, 9),
        ('card_kingdom_th', 'POKEMON-ME01-BOX-EN', 'SEALED', NULL, 4),
        ('tcg_corner', 'POKEMON-ME01-PACK-EN', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'POKEMON-ME01-PACK-EN', 'SEALED', NULL, 2),
        ('tcg_corner', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', NULL, 1),
        ('mint_vault', 'POKEMON-ME01-178-132-EN-NORMAL', 'LP', NULL, 1),
        ('mint_vault', 'POKEMON-ME01-179-132-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'POKEMON-ME01-179-132-EN-NORMAL', 'NM', NULL, 1),
        ('card_kingdom_th', 'POKEMON-ME01-179-132-EN-NORMAL', 'LP', NULL, 1),
        ('pegasus_official', 'POKEMON-ME01-ETB-EN', 'SEALED', NULL, 4),
        ('card_kingdom_th', 'POKEMON-ME01-ETB-EN', 'SEALED', NULL, 4),
        ('tcg_corner', 'POKEMON-ACC-PLAYMAT-PIKACHU', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'POKEMON-ACC-PLAYMAT-PIKACHU', 'SEALED', NULL, 2),
        ('card_kingdom_th', 'POKEMON-ACC-SLEEVES-65', 'SEALED', NULL, 2),
        ('tcg_corner', 'POKEMON-ACC-SLEEVES-65', 'SEALED', NULL, 3),
        ('pegasus_official', 'PEGASUS-ACC-PERFECT-FIT-100', 'SEALED', NULL, 7),
        ('pegasus_official', 'ONEPIECE-OP09-BOX-EN', 'SEALED', NULL, 8),
        ('card_kingdom_th', 'ONEPIECE-OP09-BOX-EN', 'SEALED', NULL, 4),
        ('tcg_corner', 'ONEPIECE-OP09-PACK-EN', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'ONEPIECE-OP09-PACK-EN', 'SEALED', NULL, 2),
        ('card_kingdom_th', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', NULL, 3),
        ('tcg_corner', 'ONEPIECE-OP09-004-EN-NORMAL', 'LP', NULL, 1),
        ('tcg_corner', 'ONEPIECE-OP09-119-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'ONEPIECE-OP09-119-EN-NORMAL', 'NM', NULL, 3),
        ('mint_vault', 'ONEPIECE-OP09-119-EN-NORMAL', 'LP', NULL, 1),
        ('pegasus_official', 'ONEPIECE-OP10-BOX-EN', 'SEALED', NULL, 12),
        ('card_kingdom_th', 'ONEPIECE-OP10-BOX-EN', 'SEALED', NULL, 4),
        ('card_kingdom_th', 'ONEPIECE-OP10-119-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'ONEPIECE-OP10-119-EN-NORMAL', 'NM', NULL, 2),
        ('tcg_corner', 'ONEPIECE-OP10-119-EN-NORMAL', 'LP', NULL, 1),
        ('card_kingdom_th', 'ONEPIECE-EB02-BOX-EN', 'SEALED', NULL, 2),
        ('tcg_corner', 'ONEPIECE-EB02-BOX-EN', 'SEALED', NULL, 3),
        ('pegasus_official', 'ONEPIECE-ST21-DECK-EN', 'SEALED', NULL, 6),
        ('tcg_corner', 'ONEPIECE-ST21-DECK-EN', 'SEALED', NULL, 3),
        ('pegasus_official', 'PEGASUS-ACC-BINDER-9', 'SEALED', NULL, 7),
        ('pegasus_official', 'MTG-FIN-PLAY-BOX-EN', 'SEALED', NULL, 8),
        ('tcg_corner', 'MTG-FIN-PLAY-BOX-EN', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'MTG-FIN-COLLECTOR-PACK-EN', 'SEALED', NULL, 2),
        ('tcg_corner', 'MTG-FIN-COLLECTOR-PACK-EN', 'SEALED', NULL, 3),
        ('card_kingdom_th', 'MTG-FIN-0382-EN-FOIL', 'NM', NULL, 1),
        ('tcg_corner', 'MTG-FIN-0382-EN-FOIL', 'NM', NULL, 2),
        ('tcg_corner', 'MTG-FIN-0382-EN-FOIL', 'LP', NULL, 1),
        ('card_kingdom_th', 'MTG-EOE-PLAY-BOX-EN', 'SEALED', NULL, 4),
        ('tcg_corner', 'MTG-EOE-PLAY-BOX-EN', 'SEALED', NULL, 5),
        ('tcg_corner', 'MTG-EOE-COMMANDER-EN', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'MTG-EOE-COMMANDER-EN', 'SEALED', NULL, 2),
        ('pegasus_official', 'PEGASUS-ACC-DECKBOX-100', 'SEALED', NULL, 5),
        ('mint_vault', 'YUGIOH-RA02-001-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'YUGIOH-RA02-001-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'YUGIOH-RA02-001-EN-NORMAL', 'NM', NULL, 2),
        ('card_kingdom_th', 'YUGIOH-RA02-001-EN-NORMAL', 'LP', NULL, 1),
        ('card_kingdom_th', 'YUGIOH-RA02-002-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'YUGIOH-RA02-002-EN-NORMAL', 'NM', NULL, 2),
        ('tcg_corner', 'YUGIOH-RA02-002-EN-NORMAL', 'LP', NULL, 1),
        ('card_kingdom_th', 'YUGIOH-ALIN-BOX-EN', 'SEALED', NULL, 2),
        ('tcg_corner', 'YUGIOH-ALIN-BOX-EN', 'SEALED', NULL, 3),
        ('pegasus_official', 'PEGASUS-ACC-MAGCASE-35', 'SEALED', NULL, 10),
        ('tcg_corner', 'DIGIMON-BT20-BOX-EN', 'SEALED', NULL, 5),
        ('card_kingdom_th', 'DIGIMON-BT20-BOX-EN', 'SEALED', NULL, 2),
        ('mint_vault', 'DIGIMON-BT20-112-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'DIGIMON-BT20-112-EN-NORMAL', 'NM', NULL, 1),
        ('tcg_corner', 'DIGIMON-BT20-112-EN-NORMAL', 'NM', NULL, 2),
        ('card_kingdom_th', 'DIGIMON-BT20-112-EN-NORMAL', 'LP', NULL, 1),
        ('tcg_corner', 'UNIONARENA-JJK-BOX-EN', 'SEALED', NULL, 3),
        ('card_kingdom_th', 'UNIONARENA-JJK-BOX-EN', 'SEALED', NULL, 4),
        ('card_kingdom_th', 'UNIONARENA-JJK-PACK-EN', 'SEALED', NULL, 4),
        ('tcg_corner', 'UNIONARENA-JJK-PACK-EN', 'SEALED', NULL, 5),
        ('mint_vault', 'UNIONARENA-JJK-045-EN-NORMAL', 'NM', NULL, 3),
        ('card_kingdom_th', 'UNIONARENA-JJK-045-EN-NORMAL', 'NM', NULL, 1),
        ('card_kingdom_th', 'UNIONARENA-JJK-045-EN-NORMAL', 'LP', NULL, 1),
        ('card_kingdom_th', 'DBSFW-FB05-BOX-EN', 'SEALED', NULL, 2),
        ('tcg_corner', 'DBSFW-FB05-BOX-EN', 'SEALED', NULL, 3),
        ('tcg_corner', 'DBSFW-FB05-118-EN-NORMAL', 'NM', NULL, 2),
        ('mint_vault', 'DBSFW-FB05-118-EN-NORMAL', 'NM', NULL, 3),
        ('mint_vault', 'DBSFW-FB05-118-EN-NORMAL', 'LP', NULL, 1),
        ('mint_vault', 'POKEMON-SV8A-006-165-EN-NORMAL', 'NM', 'PSA', 1),
        ('tcg_corner', 'ONEPIECE-OP09-BOX-EN', 'SEALED', NULL, 3)
       ) AS v(username, sku, condition_code, grading_company, units)
  JOIN user_account u ON u.username = v.username
  JOIN seller_profile sp ON sp.user_id = u.id
  JOIN catalog_variant cv ON cv.sku = v.sku
  JOIN listing l ON l.seller_profile_id = sp.id
                AND l.catalog_variant_id = cv.id
                AND l.condition_code = v.condition_code
                AND l.grading_company IS NOT DISTINCT FROM v.grading_company
                AND l.lot_label = 'seed:home'
  CROSS JOIN generate_series(1, v.units)
 WHERE NOT EXISTS (SELECT 1 FROM listing_unit lu WHERE lu.listing_id = l.id);

-- Running cost per seller, card and condition, which a sale reads its cost from.
INSERT INTO seller_variant_cost (seller_profile_id, catalog_variant_id, condition_code,
                                 total_quantity, total_cost, average_unit_cost, last_movement_at)
SELECT l.seller_profile_id, l.catalog_variant_id, l.condition_code,
       count(*), sum(lu.acquisition_cost), round(avg(lu.acquisition_cost), 4), now()
  FROM listing l
  JOIN listing_unit lu ON lu.listing_id = l.id
 WHERE l.lot_label = 'seed:home'
   AND NOT EXISTS (
        SELECT 1 FROM seller_variant_cost c
         WHERE c.seller_profile_id = l.seller_profile_id
           AND c.catalog_variant_id = l.catalog_variant_id
           AND c.condition_code = l.condition_code)
 GROUP BY l.seller_profile_id, l.catalog_variant_id, l.condition_code;

-- ---------- home page slides ----------

INSERT INTO home_banner (display_order, eyebrow, title, description, image_key, image_alt, theme,
                         primary_label, primary_href, secondary_label, secondary_href)
SELECT v.display_order::smallint, v.eyebrow, replace(v.title, '\n', E'\n'), v.description, v.image_key,
       v.image_alt, v.theme, v.primary_label, v.primary_href, v.secondary_label, v.secondary_href
  FROM (VALUES
        (1, 'NEW FROM PEGASUS', 'FRESH CARDS.\nREADY TO PLAY.', 'Discover this week''s newest singles and sealed releases, straight from verified sellers.', 'seed/banners/new-releases.jpg', 'New booster boxes and cards fanned out on a dark table', 'CAMPAIGN', 'Shop New Releases', '/search?sort=newest', 'Explore Cards', '/search'),
        (2, 'LATEST SEALED RELEASE', 'MEGA EVOLUTION.\nNOW IN STOCK.', 'Open the newest Pokemon expansion and build your next winning deck.', 'seed/banners/mega-evolution.jpg', 'Mega Evolution booster box and packs', 'RELEASE', 'Shop Mega Evolution', '/search?q=Mega%20Evolution', 'All Pokemon', '/search?game=pokemon-tcg'),
        (3, 'COLLECTOR SPOTLIGHT', 'BUILT FOR\nCOLLECTORS.', 'Secret rares, special illustrations and graded slabs from sellers we trust.', 'seed/banners/collector-spotlight.jpg', 'High-rarity cards displayed in a row', 'COLLECTOR', 'Browse Singles', '/search?category=single-cards', 'Pegasus Picks', '/store/pegasus_official'),
        (4, 'ONE PIECE WEEK', 'SET SAIL WITH\nOP-09 AND OP-10.', 'Booster boxes, Gear 5 Luffy and every leader you need for the new format.', 'seed/banners/one-piece-week.jpg', 'One Piece booster boxes and cards', 'RELEASE', 'Shop One Piece', '/search?game=one-piece-card-game', 'Explore Cards', '/search')
       ) AS v(display_order, eyebrow, title, description, image_key, image_alt, theme,
              primary_label, primary_href, secondary_label, secondary_href)
 WHERE NOT EXISTS (SELECT 1 FROM home_banner b WHERE b.image_key = v.image_key);

-- ---------- order history behind the trending rail ----------

CREATE TEMP TABLE seed_order ON COMMIT DROP AS
SELECT v.order_number, v.buyer, v.seller, v.sku, v.condition_code, v.quantity,
       v.unit_price::numeric(14,2) AS unit_price,
       (v.unit_price::numeric * v.quantity)::numeric(14,2) AS items_subtotal,
       50.00::numeric(14,2) AS shipping,
       now() - make_interval(days => v.days_ago, hours => 3) AS placed_at,
       v.product_name, v.variant_label, v.game_name, v.image_key
  FROM (VALUES
        ('PGS-SEED-000001', 'collector_mai', 'pegasus_official', 'POKEMON-ME01-BOX-EN', 'SEALED', 2, '5590.00', 1, 'Mega Evolution Booster Box [ENG]', 'EN', 'Pokemon TCG', 'seed/products/mega-evolution-booster-box-en.jpg'),
        ('PGS-SEED-000002', 'deckbuilder_ton', 'pegasus_official', 'POKEMON-ME01-BOX-EN', 'SEALED', 3, '5590.00', 1, 'Mega Evolution Booster Box [ENG]', 'EN', 'Pokemon TCG', 'seed/products/mega-evolution-booster-box-en.jpg'),
        ('PGS-SEED-000003', 'collector_mai', 'card_kingdom_th', 'POKEMON-ME01-BOX-EN', 'SEALED', 1, '5810.00', 0, 'Mega Evolution Booster Box [ENG]', 'EN', 'Pokemon TCG', 'seed/products/mega-evolution-booster-box-en.jpg'),
        ('PGS-SEED-000004', 'deckbuilder_ton', 'pegasus_official', 'POKEMON-PRE-ETB-EN', 'SEALED', 3, '3290.00', 4, 'Prismatic Evolutions Elite Trainer Box', 'EN', 'Pokemon TCG', 'seed/products/prismatic-evolutions-elite-trainer-box.jpg'),
        ('PGS-SEED-000005', 'collector_mai', 'pegasus_official', 'POKEMON-PRE-ETB-EN', 'SEALED', 2, '3290.00', 3, 'Prismatic Evolutions Elite Trainer Box', 'EN', 'Pokemon TCG', 'seed/products/prismatic-evolutions-elite-trainer-box.jpg'),
        ('PGS-SEED-000006', 'collector_mai', 'card_kingdom_th', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', 1, '8630.00', 2, 'Umbreon ex SIR', 'EN / NORMAL / UNLIMITED', 'Pokemon TCG', 'seed/products/umbreon-ex-sir-161-131.jpg'),
        ('PGS-SEED-000007', 'deckbuilder_ton', 'mint_vault', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', 1, '9430.00', 2, 'Umbreon ex SIR', 'EN / NORMAL / UNLIMITED', 'Pokemon TCG', 'seed/products/umbreon-ex-sir-161-131.jpg'),
        ('PGS-SEED-000008', 'collector_mai', 'pegasus_official', 'POKEMON-PRE-161-131-EN-NORMAL', 'NM', 1, '8900.00', 1, 'Umbreon ex SIR', 'EN / NORMAL / UNLIMITED', 'Pokemon TCG', 'seed/products/umbreon-ex-sir-161-131.jpg'),
        ('PGS-SEED-000009', 'deckbuilder_ton', 'tcg_corner', 'ONEPIECE-OP09-BOX-EN', 'SEALED', 2, '4030.00', 10, 'ONE PIECE OP-09 Booster Box', 'EN', 'One Piece Card Game', 'seed/products/op09-booster-box.jpg'),
        ('PGS-SEED-000010', 'collector_mai', 'pegasus_official', 'ONEPIECE-OP09-BOX-EN', 'SEALED', 2, '3950.00', 6, 'ONE PIECE OP-09 Booster Box', 'EN', 'One Piece Card Game', 'seed/products/op09-booster-box.jpg'),
        ('PGS-SEED-000011', 'deckbuilder_ton', 'pegasus_official', 'PEGASUS-ACC-PERFECT-FIT-100', 'SEALED', 5, '280.00', 2, 'Pegasus Perfect Fit Sleeves (100)', 'EN', 'Pokemon TCG', 'seed/products/pegasus-perfect-fit-sleeves.jpg'),
        ('PGS-SEED-000012', 'collector_mai', 'pegasus_official', 'PEGASUS-ACC-PERFECT-FIT-100', 'SEALED', 2, '280.00', 1, 'Pegasus Perfect Fit Sleeves (100)', 'EN', 'Pokemon TCG', 'seed/products/pegasus-perfect-fit-sleeves.jpg'),
        ('PGS-SEED-000013', 'deckbuilder_ton', 'tcg_corner', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', 1, '3060.00', 9, 'Shanks SEC', 'EN / NORMAL / UNLIMITED', 'One Piece Card Game', 'seed/products/shanks-sec-op09-004.jpg'),
        ('PGS-SEED-000014', 'collector_mai', 'card_kingdom_th', 'ONEPIECE-OP09-004-EN-NORMAL', 'NM', 1, '2890.00', 7, 'Shanks SEC', 'EN / NORMAL / UNLIMITED', 'One Piece Card Game', 'seed/products/shanks-sec-op09-004.jpg'),
        ('PGS-SEED-000015', 'deckbuilder_ton', 'pegasus_official', 'MTG-FIN-PLAY-BOX-EN', 'SEALED', 1, '6990.00', 20, 'FINAL FANTASY Play Booster Box', 'EN', 'Magic: The Gathering', 'seed/products/final-fantasy-play-booster-box.jpg'),
        ('PGS-SEED-000016', 'collector_mai', 'card_kingdom_th', 'MTG-FIN-0382-EN-FOIL', 'NM', 2, '1890.00', 18, 'Cloud, Midgar Mercenary', 'EN / FOIL / UNLIMITED', 'Magic: The Gathering', 'seed/products/cloud-midgar-mercenary-fin-0382.jpg'),
        ('PGS-SEED-000017', 'deckbuilder_ton', 'card_kingdom_th', 'POKEMON-ME01-178-132-EN-NORMAL', 'NM', 1, '2380.00', 0, 'Mega Lucario ex SAR', 'EN / NORMAL / UNLIMITED', 'Pokemon TCG', 'seed/products/mega-lucario-ex-sar-178-132.jpg'),
        ('PGS-SEED-000018', 'collector_mai', 'tcg_corner', 'ONEPIECE-OP09-119-EN-NORMAL', 'NM', 1, '5200.00', 5, 'Monkey.D.Luffy Gear 5 SEC', 'EN / NORMAL / UNLIMITED', 'One Piece Card Game', 'seed/products/monkey-d-luffy-sec-op09-119.jpg'),
        ('PGS-SEED-000019', 'deckbuilder_ton', 'card_kingdom_th', 'POKEMON-MEW-PACK-JP', 'SEALED', 10, '300.00', 42, 'Pokemon Card 151 Booster Pack [JP]', 'JP', 'Pokemon TCG', 'seed/products/pokemon-151-booster-pack-jp.jpg'),
        ('PGS-SEED-000020', 'collector_mai', 'card_kingdom_th', 'POKEMON-MEW-PACK-JP', 'SEALED', 8, '300.00', 45, 'Pokemon Card 151 Booster Pack [JP]', 'JP', 'Pokemon TCG', 'seed/products/pokemon-151-booster-pack-jp.jpg')
       ) AS v(order_number, buyer, seller, sku, condition_code, quantity, unit_price, days_ago,
              product_name, variant_label, game_name, image_key);

INSERT INTO sales_order (order_number, buyer_id, status, currency, items_subtotal, shipping_total,
                         discount_total, grand_total, shipping_address_id, shipping_address_snapshot,
                         placed_at, paid_at, completed_at)
SELECT o.order_number, u.id, 'COMPLETED', 'THB', o.items_subtotal, o.shipping, 0,
       o.items_subtotal + o.shipping, a.id,
       jsonb_build_object('id', a.id, 'recipientName', a.recipient_name, 'phone', a.phone,
                          'line1', a.line1, 'line2', a.line2, 'subdistrict', a.subdistrict,
                          'district', a.district, 'province', a.province,
                          'postalCode', a.postal_code, 'countryCode', a.country_code),
       o.placed_at, o.placed_at + interval '5 minutes', o.placed_at + interval '2 hours'
  FROM seed_order o
  JOIN user_account u ON u.username = o.buyer
  JOIN address a ON a.user_id = u.id AND a.is_default_shipping
 WHERE NOT EXISTS (SELECT 1 FROM sales_order s WHERE s.order_number = o.order_number);

INSERT INTO seller_order (sales_order_id, seller_profile_id, seller_order_number, status,
                          items_subtotal, shipping_fee, discount_amount, grand_total,
                          commission_rate_percent, commission_amount, seller_net_amount,
                          accepted_at, shipped_at, delivered_at, completed_at, created_at)
SELECT s.id, sp.id, o.order_number || '-01', 'COMPLETED',
       o.items_subtotal, o.shipping, 0, o.items_subtotal + o.shipping,
       5.000, round((o.items_subtotal + o.shipping) * 0.05, 2),
       (o.items_subtotal + o.shipping) - round((o.items_subtotal + o.shipping) * 0.05, 2),
       o.placed_at + interval '10 minutes', o.placed_at + interval '30 minutes',
       o.placed_at + interval '90 minutes', o.placed_at + interval '2 hours', o.placed_at
  FROM seed_order o
  JOIN sales_order s ON s.order_number = o.order_number
  JOIN user_account u ON u.username = o.seller
  JOIN seller_profile sp ON sp.user_id = u.id
 WHERE NOT EXISTS (
        SELECT 1 FROM seller_order so WHERE so.seller_order_number = o.order_number || '-01');

INSERT INTO order_item (seller_order_id, listing_id, catalog_variant_id, quantity, unit_price, line_total,
                        unit_cost_snapshot, product_name_snapshot, variant_label_snapshot,
                        condition_snapshot, game_name_snapshot, image_key_snapshot, created_at)
SELECT so.id, l.id, cv.id, o.quantity, o.unit_price, o.unit_price * o.quantity,
       round(o.unit_price * 0.6, 4), o.product_name, o.variant_label,
       o.condition_code, o.game_name, o.image_key, o.placed_at
  FROM seed_order o
  JOIN seller_order so ON so.seller_order_number = o.order_number || '-01'
  JOIN catalog_variant cv ON cv.sku = o.sku
  LEFT JOIN listing l ON l.seller_profile_id = so.seller_profile_id
                     AND l.catalog_variant_id = cv.id
                     AND l.condition_code = o.condition_code
                     AND l.grading_company IS NULL
                     AND l.lot_label = 'seed:home'
 WHERE NOT EXISTS (SELECT 1 FROM order_item oi WHERE oi.seller_order_id = so.id);

COMMIT;

-- What came out of it.
SELECT (SELECT count(*) FROM game)                                        AS games,
       (SELECT count(*) FROM catalog_category WHERE image_key IS NOT NULL) AS shelves_with_pictures,
       (SELECT count(*) FROM catalog_product)                             AS products,
       (SELECT count(*) FROM listing WHERE lot_label = 'seed:home')          AS seeded_listings,
       (SELECT count(*) FROM listing WHERE status = 'ACTIVE' AND quantity_available > 0) AS listings_on_sale,
       (SELECT count(*) FROM home_banner WHERE is_active)                 AS slides,
       (SELECT count(*) FROM sales_order WHERE order_number LIKE 'PGS-SEED-%') AS seeded_orders;
