-- A small, internally consistent auction trail keeps TCG-614's tables populated
-- for the full-seed empty-table check without changing the coupon/order fixtures.
DO $$
DECLARE
    card record;
    auction_key bigint;
    bid_one bigint;
    bid_two bigint;
    anchor timestamptz := now();
    admin_id bigint := pg_temp.uid('admin');
BEGIN
    SELECT lu.id, lu.seller_profile_id, lu.catalog_variant_id, lu.condition_code
      INTO card
      FROM listing_unit lu
      JOIN seller_profile sp ON sp.id = lu.seller_profile_id
      JOIN user_account seller ON seller.id = sp.user_id
     WHERE seller.username = 'tcg_corner'
       AND lu.status = 'IN_STOCK'
       AND lu.deleted_at IS NULL
     ORDER BY lu.id
     LIMIT 1;

    IF card.id IS NULL THEN
        RAISE EXCEPTION 'auction seed: tcg_corner needs one unlisted card in stock';
    END IF;

    UPDATE listing_unit
       SET status = 'ON_AUCTION', updated_at = anchor
     WHERE id = card.id;

    INSERT INTO auction (auction_number, seller_profile_id, listing_unit_id, catalog_variant_id,
                         condition_code, starting_price, bid_increment, buy_now_price, current_price,
                         bid_count, bidder_count, started_at, original_ends_at, ends_at, first_bid_at,
                         status, created_at, updated_at)
    VALUES ('AUC-2026-9001', card.seller_profile_id, card.id, card.catalog_variant_id,
            card.condition_code, 100, 10, 200, 100, 0, 0,
            anchor - interval '52 hours', anchor - interval '28 hours', anchor - interval '28 hours',
            NULL, 'LIVE', anchor - interval '52 hours', anchor)
    RETURNING id INTO auction_key;

    INSERT INTO auction_bid (auction_id, bidder_id, amount, status, idempotency_key, created_at)
    VALUES (auction_key, pg_temp.uid('collector_mai'), 110, 'ACTIVE', 'seed-auction-bid-1', anchor - interval '28 hours 30 seconds')
    RETURNING id INTO bid_one;

    INSERT INTO auction_bid (auction_id, bidder_id, amount, status, idempotency_key, created_at)
    VALUES (auction_key, pg_temp.uid('demo_buyer'), 120, 'ACTIVE', 'seed-auction-bid-2', anchor - interval '28 hours 10 seconds')
    RETURNING id INTO bid_two;

    -- The high bidder did not pay within 24 hours, so the auction ended UNSOLD and
    -- the card went back to the seller's stock.
    UPDATE auction
       SET leading_bid_id = bid_two, current_price = 120, bid_count = 2, bidder_count = 2,
           first_bid_at = anchor - interval '28 hours 30 seconds',
           winner_id = pg_temp.uid('demo_buyer'), winning_amount = 120,
           payment_due_at = anchor - interval '4 hours', status = 'UNSOLD',
           closed_at = anchor - interval '28 hours', updated_at = anchor
     WHERE id = auction_key;

    UPDATE listing_unit
       SET status = 'IN_STOCK', updated_at = anchor
     WHERE id = card.id;

    INSERT INTO bidder_strike (user_id, auction_id, struck_at, cleared_at, cleared_by, clear_reason)
    VALUES (pg_temp.uid('demo_buyer'), auction_key, anchor - interval '4 hours',
            anchor - interval '3 hours', admin_id, 'Seeded historical strike; cleared.');

    INSERT INTO bidding_ban (user_id, banned_from, banned_until, lifted_at, lifted_by, lift_reason, created_at)
    VALUES (pg_temp.uid('collector_mai'), anchor - interval '30 days', anchor - interval '23 days',
            anchor - interval '23 days', admin_id, 'Seeded historical ban; lifted.', anchor - interval '30 days');
END;
$$;
