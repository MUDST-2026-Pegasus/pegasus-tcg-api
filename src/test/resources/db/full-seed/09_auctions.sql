-- Auction demo orders use the same order/payment helpers as the ordinary seed.
CREATE FUNCTION pg_temp.seed_auction_order(p_auction bigint, p_buyer bigint, p_paid boolean,
                                           p_at timestamptz) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    a auction%ROWTYPE;
    card listing_unit%ROWTYPE;
    addr address%ROWTYPE;
    opt seller_shipping_option%ROWTYPE;
    product record;
    commission record;
    fee numeric;
    order_no text;
    order_id bigint;
    seller_order_id bigint;
    item_id bigint;
    payment_id bigint;
BEGIN
    SELECT * INTO a FROM auction WHERE id = p_auction;
    SELECT * INTO card FROM listing_unit WHERE id = a.listing_unit_id;
    SELECT * INTO addr FROM address WHERE user_id = p_buyer
     ORDER BY is_default_shipping DESC, id LIMIT 1;
    IF addr.id IS NULL THEN RAISE EXCEPTION 'auction seed: buyer % has no address', p_buyer; END IF;
    SELECT * INTO opt FROM seller_shipping_option
     WHERE seller_profile_id = a.seller_profile_id AND is_active
     ORDER BY display_order, id LIMIT 1;
    IF opt.id IS NULL THEN RAISE EXCEPTION 'auction seed: seller % has no shipping option', a.seller_profile_id; END IF;
    fee := CASE WHEN opt.free_threshold IS NOT NULL AND a.winning_amount >= opt.free_threshold
                THEN 0 ELSE opt.base_fee END;
    SELECT * INTO commission FROM pg_temp.quote_commission(a.seller_profile_id, a.winning_amount,
                                                             ARRAY[a.catalog_variant_id], p_at);
    SELECT p.name AS product_name, g.name AS game_name, v.language_code, v.finish, v.edition,
           v.printing_note, (SELECT image_key FROM catalog_image ci
                             WHERE ci.catalog_product_id = p.id AND ci.is_primary LIMIT 1) AS image_key
      INTO product FROM catalog_variant v JOIN catalog_product p ON p.id = v.catalog_product_id
      LEFT JOIN game g ON g.id = p.game_id WHERE v.id = a.catalog_variant_id;
    order_no := 'PGS-' || to_char(p_at AT TIME ZONE 'UTC', 'YYYYMMDD') || 'Z-'
                || lpad(nextval('order_number_seq')::text, 6, '0');
    UPDATE listing_unit SET status = 'RESERVED', updated_at = p_at WHERE id = card.id;
    INSERT INTO sales_order (order_number, buyer_id, status, items_subtotal, shipping_total,
                             discount_total, grand_total, shipping_address_id, shipping_address_snapshot,
                             idempotency_key, placed_at, updated_at, source, auction_id)
    VALUES (order_no, p_buyer, 'PENDING_PAYMENT', a.winning_amount, fee, 0,
            a.winning_amount + fee, addr.id,
            jsonb_build_object('id', addr.id, 'recipientName', addr.recipient_name, 'phone', addr.phone,
                               'line1', addr.line1, 'line2', addr.line2, 'subdistrict', addr.subdistrict,
                               'district', addr.district, 'province', addr.province,
                               'postalCode', addr.postal_code, 'countryCode', addr.country_code),
            'seed-auction-order-' || p_auction, p_at, p_at, 'AUCTION', p_auction)
    RETURNING id INTO order_id;
    INSERT INTO seller_order (sales_order_id, seller_profile_id, seller_order_number, status,
                              items_subtotal, shipping_fee, discount_amount, grand_total,
                              commission_rate_percent, commission_amount, seller_net_amount,
                              shipping_option_id, created_at, updated_at)
    VALUES (order_id, a.seller_profile_id, order_no || '-S' || a.seller_profile_id,
            'PENDING_PAYMENT', a.winning_amount, fee, 0, a.winning_amount + fee,
            commission.rate, commission.fee, a.winning_amount + fee - commission.fee,
            opt.id, p_at, p_at)
    RETURNING id INTO seller_order_id;
    INSERT INTO seller_order_status_history (seller_order_id, from_status, to_status, changed_by, note, created_at)
    VALUES (seller_order_id, NULL, 'PENDING_PAYMENT', p_buyer, 'Auction checkout', p_at);
    INSERT INTO order_item (seller_order_id, listing_id, catalog_variant_id, quantity, unit_price,
                            line_total, unit_cost_snapshot, product_name_snapshot, variant_label_snapshot,
                            condition_snapshot, game_name_snapshot, image_key_snapshot, created_at)
    VALUES (seller_order_id, card.listing_id, a.catalog_variant_id, 1, a.winning_amount,
            a.winning_amount,
            coalesce((SELECT average_unit_cost FROM seller_variant_cost WHERE seller_profile_id = a.seller_profile_id
                      AND catalog_variant_id = a.catalog_variant_id AND condition_code = a.condition_code), 0),
            product.product_name,
            product.language_code
              || CASE WHEN product.finish <> 'NOT_APPLICABLE' THEN ' / ' || product.finish ELSE '' END
              || CASE WHEN product.edition <> 'NOT_APPLICABLE' THEN ' / ' || product.edition ELSE '' END
              || CASE WHEN coalesce(product.printing_note, '') <> '' THEN ' / ' || product.printing_note ELSE '' END,
            a.condition_code, product.game_name, product.image_key, p_at)
    RETURNING id INTO item_id;
    INSERT INTO order_item_unit (order_item_id, listing_unit_id, created_at) VALUES (item_id, card.id, p_at);
    INSERT INTO payment (sales_order_id, method, status, amount, idempotency_key, created_at)
    VALUES (order_id, 'MOCK_GATEWAY', 'PENDING', a.winning_amount + fee,
            'seed-auction-payment-' || p_auction, p_at) RETURNING id INTO payment_id;
    IF p_paid THEN PERFORM pg_temp.pay_succeed(payment_id, NULL, p_at); END IF;
    UPDATE auction SET sales_order_id = order_id WHERE id = p_auction;
    RETURN order_id;
END;
$$;

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
    FOR scenario IN 1..8 LOOP
        SELECT lu.id, lu.seller_profile_id, lu.catalog_variant_id, lu.condition_code
          INTO card FROM listing_unit lu
         WHERE lu.status = 'IN_STOCK'
           AND lu.deleted_at IS NULL
           AND NOT EXISTS (SELECT 1 FROM auction x WHERE x.listing_unit_id = lu.id)
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
                     WHEN scenario = 4 THEN anchor + interval '7 days'
                     ELSE anchor + interval '2 days' END,
                CASE WHEN scenario IN (3, 5, 6, 7) THEN anchor - interval '1 hour'
                     WHEN scenario = 4 THEN anchor + interval '7 days'
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
                    winning_amount = 110, payment_due_at = anchor + interval '7 days',
                    closed_at = anchor - interval '1 hour' WHERE id = auction_key;
                PERFORM pg_temp.seed_auction_order(auction_key, pg_temp.uid('collector_mai'), false, anchor - interval '1 hour');
            WHEN 6 THEN
                UPDATE auction SET status = 'SUSPENDED', suspended_reason = 'Demo moderation',
                    suspended_by = admin_id, suspended_at = anchor - interval '1 hour',
                    closed_at = anchor - interval '1 hour' WHERE id = auction_key;
                UPDATE listing_unit SET status = 'IN_STOCK' WHERE id = card.id;
            WHEN 7 THEN
                UPDATE auction SET status = 'SOLD', winner_id = pg_temp.uid('collector_mai'),
                    winning_amount = 110, closed_at = anchor - interval '1 hour'
                WHERE id = auction_key;
                PERFORM pg_temp.seed_auction_order(auction_key, pg_temp.uid('collector_mai'), true, anchor - interval '1 hour');
                UPDATE listing_unit SET status = 'SOLD', sold_at = anchor - interval '1 hour'
                WHERE id = card.id;
                issued_cost := pg_temp.cost_issue(card.seller_profile_id, card.catalog_variant_id,
                    card.condition_code, 1, anchor - interval '1 hour');
                INSERT INTO inventory_movement (seller_profile_id, listing_unit_id, catalog_variant_id,
                                                condition_code, movement_type, quantity_delta, unit_cost,
                                                created_by, created_at)
                VALUES (card.seller_profile_id, card.id, card.catalog_variant_id, card.condition_code,
                        'SALE', -1, issued_cost, admin_id, anchor - interval '1 hour');
            WHEN 8 THEN
                UPDATE auction SET status = 'CANCELLED', cancelled_at = anchor,
                    closed_at = anchor WHERE id = auction_key;
                UPDATE listing_unit SET status = 'IN_STOCK' WHERE id = card.id;
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
           AND NOT EXISTS (SELECT 1 FROM auction x WHERE x.listing_unit_id = lu.id)
         ORDER BY lu.id LIMIT 1;
        IF card.id IS NULL THEN RAISE EXCEPTION 'auction seed: not enough cards in stock for penalties'; END IF;
        INSERT INTO auction (auction_number, seller_profile_id, listing_unit_id,
                             catalog_variant_id, condition_code, starting_price, bid_increment,
                             current_price, bid_count, bidder_count, started_at,
                             original_ends_at, ends_at, first_bid_at, winner_id,
                             winning_amount, closed_at, payment_due_at, status,
                             created_at, updated_at)
        VALUES ('AUC-2026-' || (9009 + penalty_index), card.seller_profile_id,
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
