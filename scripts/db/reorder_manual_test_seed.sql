-- ============================================================================
-- Pegasus TCG Backend: Reorder & Batch Add-to-Cart Manual Test Seed Data
-- ============================================================================
-- This script seeds deterministic test data for:
-- 1. GET /api/v1/orders/{orderId}/reorder-preview
-- 2. POST /api/v1/cart/items/batch
--
-- Target Database: pegasus_tcg (PostgreSQL)
-- Safe dedicated ID ranges:
--   - sales_order:  9001, 9002, 9003
--   - seller_order: 9101, 9102, 9103, 9104
--   - order_item:   9201, 9202, 9203, 9204, 9205, 9206
--   - listing:      9301, 9302, 9303, 9304, 9305, 9306
--
-- Existing User Accounts used:
--   - buyer_id = 9 (demo_buyer / buyer@example.com / Password123!)
--   - other_buyer_id = 6 (deckbuilder_ton / ton@example.com / Password123!)
--   - seller_profile_id = 2 (Card Kingdom TH, user_id = 4, VERIFIED, vacation_mode = false)
--   - seller_profile_id = 7 (Kanto Cards, user_id = 13, VERIFIED, vacation_mode = true)
--
-- Test Scenarios in Order 9001:
--   - Item 9201 (Variant 1: Charizard ex): Original condition NM.
--       Listing 9301 (NM) price changed (150 -> 165), in stock.
--       Listings 9302 (LP) & 9303 (MP) available as alternatives.
--       -> Expected Status: AVAILABLE_PRICE_CHANGED
--   - Item 9202 (Variant 139: Bio-Broly): Original condition DMG.
--       Listing 9304 (DMG) is SOLD_OUT (qty 0).
--       Listings 9305 (NM) & 9306 (LP) available as alternatives.
--       -> Expected Status: ALTERNATIVE_AVAILABLE
--   - Item 9203 (Variant 140: Son Goku : GT): Original condition NM.
--       No active listings for seller 2.
--       -> Expected Status: OUT_OF_STOCK
--   - Item 9204 (Variant 1: Charizard ex): Ordered from Seller 7 (Kanto Cards).
--       Seller 7 is on vacation mode (vacation_mode = true).
--       -> Expected Status: OUT_OF_STOCK, sellerAvailable: false, vacationMode: true
-- ============================================================================

BEGIN;

-- 0. Clean up any previous test run data (Idempotent)
DELETE FROM cart_item WHERE listing_id IN (9301, 9302, 9303, 9304, 9305, 9306);
DELETE FROM order_item WHERE id IN (9201, 9202, 9203, 9204, 9205, 9206);
DELETE FROM seller_order WHERE id IN (9101, 9102, 9103, 9104);
DELETE FROM sales_order WHERE id IN (9001, 9002, 9003);
DELETE FROM listing WHERE id IN (9301, 9302, 9303, 9304, 9305, 9306);

-- Ensure seller 7 is on vacation mode and seller 2 is open for business
UPDATE seller_profile SET vacation_mode = false, status = 'VERIFIED' WHERE id = 2;
UPDATE seller_profile SET vacation_mode = true, status = 'VERIFIED' WHERE id = 7;

-- 1. Create Listings for Seller 2 (Card Kingdom TH)
-- Variant 1: Charizard ex (PKM-MEW-006-EN-H)
-- 9301: Original condition NM, price changed (150 -> 165), in stock (5)
INSERT INTO listing (
    id, seller_profile_id, catalog_variant_id, condition_code,
    price, currency, pricing_mode, quantity_total, quantity_reserved, quantity_available,
    status, created_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9301, 2, 1, 'NM', 165.00, 'THB', 'MANUAL', 5, 0, 5, 'ACTIVE', now(), now()),
-- 9302: Alternative condition LP, in stock (3)
(9302, 2, 1, 'LP', 140.00, 'THB', 'MANUAL', 3, 0, 3, 'ACTIVE', now(), now()),
-- 9303: Alternative condition MP, in stock (2)
(9303, 2, 1, 'MP', 110.00, 'THB', 'MANUAL', 2, 0, 2, 'ACTIVE', now(), now()),

-- Variant 139: Bio-Broly (SR) (DBF-FB05-084-EN-F)
-- 9304: Original condition DMG, OUT OF STOCK (quantity = 0)
(9304, 2, 139, 'DMG', 8000.00, 'THB', 'MANUAL', 0, 0, 0, 'SOLD_OUT', now(), now()),
-- 9305: Alternative condition NM, in stock (2)
(9305, 2, 139, 'NM', 9500.00, 'THB', 'MANUAL', 2, 0, 2, 'ACTIVE', now(), now()),
-- 9306: Alternative condition LP, in stock (1)
(9306, 2, 139, 'LP', 8800.00, 'THB', 'MANUAL', 1, 0, 1, 'ACTIVE', now(), now());

-- 2. Scenario Order 1 (ID 9001): Fully COMPLETED order for demo_buyer (user 9)
INSERT INTO sales_order (
    id, order_number, buyer_id, status, currency,
    items_subtotal, shipping_total, discount_total, grand_total,
    shipping_address_snapshot, placed_at, paid_at, completed_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9001, 'PGS-TEST-REORDER-001', 9, 'COMPLETED', 'THB',
 13150.00, 100.00, 0.00, 13250.00,
 '{"recipient":"Demo Buyer","phone":"0812345678","street":"99 Sukhumvit Rd","city":"Bangkok","postal_code":"10110"}'::jsonb,
 now() - interval '7 days', now() - interval '7 days', now() - interval '6 days', now());

INSERT INTO seller_order (
    id, sales_order_id, seller_profile_id, seller_order_number, status,
    items_subtotal, shipping_fee, discount_amount, grand_total,
    commission_rate_percent, commission_amount, seller_net_amount,
    seller_discount_amount, platform_discount_amount,
    accepted_at, shipped_at, delivered_at, completed_at, created_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9101, 9001, 2, 'SO-TEST-REORDER-001-A', 'COMPLETED',
 13000.00, 50.00, 0.00, 13050.00,
 5.000, 652.50, 12397.50,
 0.00, 0.00,
 now() - interval '7 days', now() - interval '6 days', now() - interval '5 days', now() - interval '5 days', now() - interval '7 days', now()),
(9102, 9001, 7, 'SO-TEST-REORDER-001-B', 'COMPLETED',
 150.00, 50.00, 0.00, 200.00,
 5.000, 10.00, 190.00,
 0.00, 0.00,
 now() - interval '7 days', now() - interval '6 days', now() - interval '5 days', now() - interval '5 days', now() - interval '7 days', now());

INSERT INTO order_item (
    id, seller_order_id, catalog_variant_id, quantity, unit_price, line_total,
    unit_cost_snapshot, product_name_snapshot, variant_label_snapshot, condition_snapshot,
    game_name_snapshot, discount_allocated, seller_discount_allocated, created_at
) OVERRIDING SYSTEM VALUE VALUES
-- Item 9201: Charizard ex, NM (orig 150.00, current 165.00) -> AVAILABLE_PRICE_CHANGED
(9201, 9101, 1, 1, 150.00, 150.00,
 100.00, 'Charizard ex', 'EN / HOLO', 'NM',
 'Pokémon TCG', 0.00, 0.00, now() - interval '7 days'),
-- Item 9202: Bio-Broly (SR), DMG (orig 8000.00, DMG is sold out, NM/LP available) -> ALTERNATIVE_AVAILABLE
(9202, 9101, 139, 1, 8000.00, 8000.00,
 6000.00, 'Bio-Broly (SR)', 'EN / FOIL', 'DMG',
 'Dragon Ball Super', 0.00, 0.00, now() - interval '7 days'),
-- Item 9203: Son Goku : GT (SCR), NM (orig 4850.00, no stock from seller 2) -> OUT_OF_STOCK
(9203, 9101, 140, 1, 4850.00, 4850.00,
 3500.00, 'Son Goku : GT (SCR)', 'EN / FOIL', 'NM',
 'Dragon Ball Super', 0.00, 0.00, now() - interval '7 days'),
-- Item 9204: Charizard ex from seller 7 (on vacation) -> OUT_OF_STOCK
(9204, 9102, 1, 1, 150.00, 150.00,
 100.00, 'Charizard ex', 'EN / HOLO', 'NM',
 'Pokémon TCG', 0.00, 0.00, now() - interval '7 days');

-- 3. Scenario Order 2 (ID 9002): PENDING_PAYMENT order for demo_buyer (user 9)
-- Used to verify 400 Bad Request ("Only completed orders can be reordered")
INSERT INTO sales_order (
    id, order_number, buyer_id, status, currency,
    items_subtotal, shipping_total, discount_total, grand_total,
    shipping_address_snapshot, placed_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9002, 'PGS-TEST-REORDER-002', 9, 'PENDING_PAYMENT', 'THB',
 165.00, 50.00, 0.00, 215.00,
 '{"recipient":"Demo Buyer","phone":"0812345678","street":"99 Sukhumvit Rd","city":"Bangkok","postal_code":"10110"}'::jsonb,
 now(), now());

INSERT INTO seller_order (
    id, sales_order_id, seller_profile_id, seller_order_number, status,
    items_subtotal, shipping_fee, discount_amount, grand_total,
    commission_rate_percent, commission_amount, seller_net_amount,
    seller_discount_amount, platform_discount_amount, created_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9103, 9002, 2, 'SO-TEST-REORDER-002', 'PENDING_PAYMENT',
 165.00, 50.00, 0.00, 215.00,
 5.000, 10.75, 204.25,
 0.00, 0.00, now(), now());

INSERT INTO order_item (
    id, seller_order_id, catalog_variant_id, quantity, unit_price, line_total,
    unit_cost_snapshot, product_name_snapshot, variant_label_snapshot, condition_snapshot,
    game_name_snapshot, discount_allocated, seller_discount_allocated, created_at
) OVERRIDING SYSTEM VALUE VALUES
(9205, 9103, 1, 1, 165.00, 165.00,
 100.00, 'Charizard ex', 'EN / HOLO', 'NM',
 'Pokémon TCG', 0.00, 0.00, now());

-- 4. Scenario Order 3 (ID 9003): Completed order belonging to OTHER buyer (user 6: deckbuilder_ton)
-- Used to verify 403 Forbidden ("Access is denied") when requested by demo_buyer
INSERT INTO sales_order (
    id, order_number, buyer_id, status, currency,
    items_subtotal, shipping_total, discount_total, grand_total,
    shipping_address_snapshot, placed_at, paid_at, completed_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9003, 'PGS-TEST-REORDER-003', 6, 'COMPLETED', 'THB',
 165.00, 50.00, 0.00, 215.00,
 '{"recipient":"Ton Deckbuilder","phone":"0898765432","street":"12 Phaholyothin Rd","city":"Bangkok","postal_code":"10400"}'::jsonb,
 now() - interval '2 days', now() - interval '2 days', now() - interval '1 day', now());

INSERT INTO seller_order (
    id, sales_order_id, seller_profile_id, seller_order_number, status,
    items_subtotal, shipping_fee, discount_amount, grand_total,
    commission_rate_percent, commission_amount, seller_net_amount,
    seller_discount_amount, platform_discount_amount,
    accepted_at, shipped_at, delivered_at, completed_at, created_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
(9104, 9003, 2, 'SO-TEST-REORDER-003', 'COMPLETED',
 165.00, 50.00, 0.00, 215.00,
 5.000, 10.75, 204.25,
 0.00, 0.00,
 now() - interval '2 days', now() - interval '2 days', now() - interval '1 day', now() - interval '1 day', now() - interval '2 days', now());

INSERT INTO order_item (
    id, seller_order_id, catalog_variant_id, quantity, unit_price, line_total,
    unit_cost_snapshot, product_name_snapshot, variant_label_snapshot, condition_snapshot,
    game_name_snapshot, discount_allocated, seller_discount_allocated, created_at
) OVERRIDING SYSTEM VALUE VALUES
(9206, 9104, 1, 1, 165.00, 165.00,
 100.00, 'Charizard ex', 'EN / HOLO', 'NM',
 'Pokémon TCG', 0.00, 0.00, now() - interval '2 days');

COMMIT;
