-- Local catalog correction and sourced rules for the SV8a Pikachu ex demo row.
-- The historical slug is kept so existing product links and listings still work.
-- Official SV8a card: https://www.pokemon-card.com/card-search/details.php/card/47157/regu/all
-- The legacy demo variants are not official printings; review them separately.
-- Run after the catalog_card_effect migration and the catalog demo seed.
BEGIN;

UPDATE catalog_product p
SET card_number = '236/187', rarity_code = 'UR'
FROM game g, card_set s
WHERE p.game_id = g.id AND g.code = 'POKEMON'
  AND p.card_set_id = s.id AND s.code = 'SV8A'
  AND p.slug = 'pikachu-ex-025-187'
  AND p.name = 'Pikachu ex' AND p.product_type = 'SINGLE_CARD'
  AND p.card_number IN ('025/187', '236/187')
  AND (p.card_number <> '236/187' OR p.rarity_code IS DISTINCT FROM 'UR');

INSERT INTO catalog_card_effect (
    catalog_product_id, language_code, effect_type, name, effect_text,
    damage, energy_cost, display_order, source_url)
SELECT p.id, 'EN', e.effect_type, e.name, e.effect_text,
       e.damage, e.energy_cost::jsonb, e.display_order,
       'https://www.pokemon-card.com/card-search/details.php/card/47157/regu/all'
FROM catalog_product p
JOIN card_set s ON s.id = p.card_set_id AND s.code = 'SV8A'
CROSS JOIN (VALUES
    ('RULE', 'Terastal',
     'While this Pokémon is on the Bench, attacks cannot damage it.',
     NULL::int, '[]', 0::smallint),
    ('ABILITY', 'Resolute Heart',
     'If an attack would Knock Out this Pokémon while it has full HP, it stays in play with 10 HP instead.',
     NULL::int, '[]', 1::smallint),
    ('ATTACK', 'Topaz Bolt',
     'Discard 3 Energy from this Pokémon.',
     300, '["Grass","Lightning","Metal"]', 2::smallint)
) AS e(effect_type, name, effect_text, damage, energy_cost, display_order)
WHERE p.slug = 'pikachu-ex-025-187'
  AND p.name = 'Pikachu ex'
  AND p.card_number = '236/187'
ON CONFLICT (catalog_product_id, language_code, display_order) DO NOTHING;

COMMIT;
