-- Always loaded. With --reset / -Reset / RESET=1 it wipes whatever is there; without it,
-- 00_guard.sql has already made sure there is nothing to lose.
--
-- Empties every application table and restarts their ids, so everyone who runs
-- the seed gets the same ids. Kept: flyway_schema_history (no migration reruns)
-- and app_role (the role codes are part of the API contract). platform_setting
-- and the platform wallet go too and are put back by 01_setup.sql.
--
-- Everything here is inside the seed's single transaction: if anything later
-- fails, this is rolled back with it and the database is left as it was.

TRUNCATE TABLE
    refund_transfer_attempt, refund, return_item, return_request,
    seller_review, seller_rating_summary, collection_item, wishlist_item,
    commission_charge, escrow_hold, ledger_entry, payout, commission_rule,
    payment, shipment, seller_order_status_history, order_item_unit, order_item,
    seller_order, sales_order, cart_item, cart,
    inventory_movement, seller_variant_cost, variant_market_stat,
    listing_price_history, listing_image, listing_unit, listing,
    catalog_card_effect, catalog_image, catalog_variant, catalog_product,
    card_set, catalog_category, game_attribute, game,
    seller_payout_account, seller_verification, seller_shipping_option, seller_profile,
    address, auth_token, user_role, wallet, platform_setting, home_banner, user_account
    RESTART IDENTITY CASCADE;
