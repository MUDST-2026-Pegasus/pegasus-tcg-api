-- A small, internally consistent auction trail keeps TCG-614's tables populated
-- for the full-seed empty-table check without changing the coupon/order fixtures.
DO $$
DECLARE
    card record;
    auction_key bigint;
    bid_one bigint;
    bid_two bigint;
    scenario integer;
    issued_cost numeric;
    penalty_index integer;
    penalty_user text;
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

    INSERT INTO bidder_strike (user_id, auction_id, struck_at)
    VALUES (pg_temp.uid('demo_buyer'), auction_key, anchor - interval '4 hours');

    INSERT INTO bidding_ban (user_id, banned_from, banned_until, lifted_at, lifted_by, lift_reason, created_at)
    VALUES (pg_temp.uid('collector_mai'), anchor - interval '30 days', anchor - interval '23 days',
            anchor - interval '23 days', admin_id, 'Seeded historical ban; lifted.', anchor - interval '30 days');

    -- A distinct physical card is reserved for each open or pending auction.
    FOR scenario IN 1..7 LOOP
        SELECT lu.id, lu.seller_profile_id, lu.catalog_variant_id, lu.condition_code
          INTO card FROM listing_unit lu
         WHERE lu.status = 'IN_STOCK'
           AND lu.deleted_at IS NULL
         ORDER BY lu.id LIMIT 1;
        IF card.id IS NULL THEN RAISE EXCEPTION 'auction seed: not enough cards in stock'; END IF;

        UPDATE listing_unit SET status = 'ON_AUCTION', updated_at = anchor WHERE id = card.id;
        INSERT INTO auction (auction_number, seller_profile_id, listing_unit_id, catalog_variant_id,
                             condition_code, starting_price, bid_increment, buy_now_price,
                             current_price, bid_count, bidder_count, started_at, original_ends_at,
                             ends_at, status, created_at, updated_at)
        VALUES ('AUC-2026-' || (9001 + scenario), card.seller_profile_id, card.id,
                card.catalog_variant_id, card.condition_code, 100, 10,
                CASE WHEN scenario = 4 THEN 200 ELSE NULL END,
                100, 0, 0, anchor - interval '2 hours',
                CASE WHEN scenario IN (3, 5, 6, 7) THEN anchor - interval '1 hour'
                     WHEN scenario = 4 THEN anchor + interval '10 minutes'
                     ELSE anchor + interval '2 days' END,
                CASE WHEN scenario IN (3, 5, 6, 7) THEN anchor - interval '1 hour'
                     WHEN scenario = 4 THEN anchor + interval '10 minutes'
                     ELSE anchor + interval '2 days' END,
                'LIVE', anchor - interval '2 hours', anchor)
        RETURNING id INTO auction_key;

        IF scenario IN (2, 5, 7) THEN
            INSERT INTO auction_bid (auction_id, bidder_id, amount, status, idempotency_key, created_at)
            VALUES (auction_key, pg_temp.uid('collector_mai'), 110, 'ACTIVE',
                    'seed-auction-' || scenario, anchor - interval '90 minutes')
            RETURNING id INTO bid_one;
            UPDATE auction SET leading_bid_id = bid_one, current_price = 110,
                bid_count = 1, bidder_count = 1, first_bid_at = anchor - interval '90 minutes'
            WHERE id = auction_key;
        END IF;

        CASE scenario
            WHEN 3 THEN
                UPDATE auction SET status = 'UNSOLD', closed_at = anchor - interval '1 hour'
                WHERE id = auction_key;
                UPDATE listing_unit SET status = 'IN_STOCK' WHERE id = card.id;
            WHEN 5 THEN
                UPDATE auction SET status = 'AWAITING_PAYMENT', winner_id = pg_temp.uid('collector_mai'),
                    winning_amount = 110, payment_due_at = anchor + interval '23 hours',
                    closed_at = anchor - interval '1 hour' WHERE id = auction_key;
            WHEN 6 THEN
                UPDATE auction SET status = 'SUSPENDED', suspended_reason = 'Demo moderation',
                    suspended_by = admin_id, suspended_at = anchor - interval '1 hour',
                    closed_at = anchor - interval '1 hour' WHERE id = auction_key;
                UPDATE listing_unit SET status = 'IN_STOCK' WHERE id = card.id;
            WHEN 7 THEN
                UPDATE auction SET status = 'SOLD', winner_id = pg_temp.uid('collector_mai'),
                    winning_amount = 110, closed_at = anchor - interval '1 hour'
                WHERE id = auction_key;
                UPDATE listing_unit SET status = 'SOLD', sold_at = anchor - interval '1 hour'
                WHERE id = card.id;
                issued_cost := pg_temp.cost_issue(card.seller_profile_id, card.catalog_variant_id,
                    card.condition_code, 1, anchor - interval '1 hour');
                INSERT INTO inventory_movement (seller_profile_id, listing_unit_id, catalog_variant_id,
                                                condition_code, movement_type, quantity_delta, unit_cost,
                                                created_by, created_at)
                VALUES (card.seller_profile_id, card.id, card.catalog_variant_id, card.condition_code,
                        'SALE', -1, issued_cost, admin_id, anchor - interval '1 hour');
            ELSE NULL;
        END CASE;
    END LOOP;

    -- One, two and three active unpaid-win strikes for demo_buyer,
    -- deckbuilder_ton and ploy_collects respectively.
    FOR penalty_index IN 1..5 LOOP
        penalty_user := CASE WHEN penalty_index <= 2 THEN 'deckbuilder_ton' ELSE 'ploy_collects' END;
        SELECT lu.id, lu.seller_profile_id, lu.catalog_variant_id, lu.condition_code
          INTO card FROM listing_unit lu
         WHERE lu.status = 'IN_STOCK'
           AND lu.deleted_at IS NULL
         ORDER BY lu.id LIMIT 1;
        INSERT INTO auction (auction_number, seller_profile_id, listing_unit_id,
                             catalog_variant_id, condition_code, starting_price, bid_increment,
                             current_price, bid_count, bidder_count, started_at,
                             original_ends_at, ends_at, first_bid_at, winner_id,
                             winning_amount, closed_at, payment_due_at, status,
                             created_at, updated_at)
        VALUES ('AUC-2026-' || (9008 + penalty_index), card.seller_profile_id,
                card.id, card.catalog_variant_id, card.condition_code, 100, 10,
                110, 0, 0, anchor - interval '4 days', anchor - interval '3 days',
                anchor - interval '3 days', anchor - interval '3 days 1 hour',
                pg_temp.uid(penalty_user), 110, anchor - interval '3 days',
                anchor - interval '2 days', 'UNSOLD', anchor - interval '4 days', anchor)
        RETURNING id INTO auction_key;
        INSERT INTO auction_bid (auction_id, bidder_id, amount, status, idempotency_key, created_at)
        VALUES (auction_key, pg_temp.uid(penalty_user), 110, 'ACTIVE',
                'seed-penalty-' || penalty_index, anchor - interval '3 days 1 hour')
        RETURNING id INTO bid_one;
        UPDATE auction SET leading_bid_id = bid_one, bid_count = 1, bidder_count = 1
        WHERE id = auction_key;
        INSERT INTO bidder_strike (user_id, auction_id, struck_at)
        VALUES (pg_temp.uid(penalty_user), auction_key, anchor - interval '2 days');
    END LOOP;
    INSERT INTO bidding_ban (user_id, banned_from, banned_until, created_at)
    VALUES (pg_temp.uid('ploy_collects'), anchor - interval '2 days',
            anchor + interval '28 days', anchor - interval '2 days');
END;
$$;
