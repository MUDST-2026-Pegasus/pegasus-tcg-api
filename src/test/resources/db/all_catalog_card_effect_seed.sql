-- Local, idempotent rules for every single card in the current demo catalog.
-- Run after the catalog_card_effect migration and the catalog demo seed.
-- Effect descriptions are paraphrases of the official card database.
BEGIN;

INSERT INTO card_set (game_id, code, name, name_local, release_date, total_cards)
SELECT g.id, 'MEW', 'Scarlet & Violet—151', 'โปเกมอนการ์ด 151', DATE '2023-09-22', 165
FROM game g
WHERE g.code = 'POKEMON'
  AND NOT EXISTS (SELECT 1 FROM card_set s WHERE s.game_id = g.id AND s.code = 'MEW');

-- Correct legacy demo metadata while preserving slugs and variant IDs so
-- existing links and listings remain valid.
UPDATE catalog_product p
SET card_number = '236/187', rarity_code = 'UR'
FROM card_set s, game g
WHERE s.game_id = g.id AND g.code = 'POKEMON' AND s.code = 'SV8A'
  AND p.game_id = g.id AND p.card_set_id = s.id
  AND p.slug = 'pikachu-ex-025-187' AND p.name = 'Pikachu ex'
  AND p.product_type = 'SINGLE_CARD' AND p.card_number IN ('025/187', '236/187')
  AND (p.card_number <> '236/187' OR p.rarity_code IS DISTINCT FROM 'UR');

UPDATE catalog_product p
SET card_set_id = s.id, rarity_code = 'RR'
FROM card_set s, game g
WHERE s.game_id = g.id AND g.code = 'POKEMON' AND s.code = 'MEW'
  AND p.game_id = g.id AND p.product_type = 'SINGLE_CARD'
  AND ((p.slug = 'charizard-ex-006-165' AND p.name = 'Charizard ex' AND p.card_number = '006/165')
    OR (p.slug = 'mew-ex-151-165' AND p.name = 'Mew ex' AND p.card_number = '151/165'))
  AND (p.card_set_id IS DISTINCT FROM s.id OR p.rarity_code IS DISTINCT FROM 'RR');

INSERT INTO catalog_card_effect (
    catalog_product_id, language_code, effect_type, name, effect_text,
    damage, energy_cost, display_order, source_url)
SELECT p.id, 'EN', e.effect_type, e.name, e.effect_text,
       e.damage, e.energy_cost::jsonb, e.display_order, e.source_url
FROM catalog_product p
JOIN game g ON g.id = p.game_id AND g.code = 'POKEMON'
JOIN card_set s ON s.id = p.card_set_id
JOIN (VALUES
  ('pikachu-ex-025-187', 'SV8A', '236/187', 'RULE', 'Terastal',
   'While this Pokémon is on the Bench, attacks cannot damage it.',
   NULL::int, '[]', 0::smallint,
   'https://www.pokemon-card.com/card-search/details.php/card/47157/regu/all'),
  ('pikachu-ex-025-187', 'SV8A', '236/187', 'ABILITY', 'Resolute Heart',
   'If an attack would Knock Out this Pokémon while it has full HP, it stays in play with 10 HP instead.',
   NULL::int, '[]', 1::smallint,
   'https://www.pokemon-card.com/card-search/details.php/card/47157/regu/all'),
  ('pikachu-ex-025-187', 'SV8A', '236/187', 'ATTACK', 'Topaz Bolt',
   'Discard 3 Energy from this Pokémon.',
   300, '["Grass","Lightning","Metal"]', 2::smallint,
   'https://www.pokemon-card.com/card-search/details.php/card/47157/regu/all'),
  ('pikachu-ex-025-187', 'SV8A', '236/187', 'RULE', 'Pokémon ex rule',
   'A knockout of this Pokémon awards the opponent 2 Prize cards.',
   NULL::int, '[]', 3::smallint,
   'https://www.pokemon-card.com/card-search/details.php/card/47157/regu/all'),
  ('charizard-ex-006-165', 'MEW', '006/165', 'ATTACK', 'Brave Wing',
   'Base damage is 60; damage counters on this Pokémon increase it by 100.',
   60, '["Fire"]', 0::smallint,
   'https://asia.pokemon-card.com/id/card-search/detail/8417/'),
  ('charizard-ex-006-165', 'MEW', '006/165', 'ATTACK', 'Explosive Vortex',
   'Discard 3 Energy from this Pokémon.',
   330, '["Fire","Fire","Fire","Fire"]', 1::smallint,
   'https://asia.pokemon-card.com/id/card-search/detail/8417/'),
  ('charizard-ex-006-165', 'MEW', '006/165', 'RULE', 'Pokémon ex rule',
   'A knockout of this Pokémon awards the opponent 2 Prize cards.',
   NULL::int, '[]', 2::smallint,
   'https://www.pokemon.com/uk/pokemon-tcg/pokemon-cards/series/sv3pt5/6/'),
  ('mew-ex-151-165', 'MEW', '151/165', 'ABILITY', 'Restart',
   'Once on your turn, draw from your deck to bring your hand to 3 cards.',
   NULL::int, '[]', 0::smallint,
   'https://www.pokemon.com/uk/pokemon-tcg/pokemon-cards/series/sv3pt5/151/'),
  ('mew-ex-151-165', 'MEW', '151/165', 'ATTACK', 'Genome Hacking',
   'Use one attack from the opposing Active Pokémon as this attack.',
   NULL::int, '["Colorless","Colorless","Colorless"]', 1::smallint,
   'https://www.pokemon.com/uk/pokemon-tcg/pokemon-cards/series/sv3pt5/151/'),
  ('mew-ex-151-165', 'MEW', '151/165', 'RULE', 'Pokémon ex rule',
   'A knockout of this Pokémon awards the opponent 2 Prize cards.',
   NULL::int, '[]', 2::smallint,
   'https://www.pokemon.com/uk/pokemon-tcg/pokemon-cards/series/sv3pt5/151/')
) AS e(slug, set_code, card_number, effect_type, name, effect_text,
       damage, energy_cost, display_order, source_url)
  ON e.slug = p.slug AND e.set_code = s.code AND e.card_number = p.card_number
WHERE p.product_type = 'SINGLE_CARD'
ON CONFLICT (catalog_product_id, language_code, display_order) DO NOTHING;

COMMIT;
