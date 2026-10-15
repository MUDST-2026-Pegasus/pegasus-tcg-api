-- Loaded before 00_reset.sql unless the seed runs with --reset / -Reset / RESET=1.
--
-- The full seed builds money, stock and order history that must add up across
-- every table, so it cannot be merged into somebody's existing data. A database
-- fresh from the migrations passes; anything with accounts, products or orders
-- stops here and nothing is changed.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM user_account)
       OR EXISTS (SELECT 1 FROM catalog_product)
       OR EXISTS (SELECT 1 FROM listing)
       OR EXISTS (SELECT 1 FROM sales_order) THEN
        RAISE EXCEPTION 'This database already has data, so the full seed will not load into it. Run it with -Reset (PowerShell), --reset (bash) or RESET=1 (make) to wipe the data first.';
    END IF;
END;
$$;
