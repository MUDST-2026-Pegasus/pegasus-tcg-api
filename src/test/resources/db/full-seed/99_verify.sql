-- Checks the seed against the invariants the application and its tests rely on.
-- Any failure raises, and the whole seed (still one transaction) rolls back.

DO $$
DECLARE
    bad   bigint;
    t     record;
    empty text[] := ARRAY[]::text[];
    n     bigint;
BEGIN
    -- 1. Every wallet equals its ledger (regression test #1 compares the two).
    SELECT count(*) INTO bad FROM wallet w
     WHERE w.balance_available + w.balance_pending <>
           coalesce((SELECT sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                       FROM ledger_entry e WHERE e.wallet_id = w.id), 0);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % wallet(s) disagree with the ledger', bad; END IF;

    -- 2. The platform's pending money is exactly what escrow still holds.
    SELECT count(*) INTO bad FROM wallet w
     WHERE w.kind = 'PLATFORM'
       AND w.balance_pending <> (SELECT coalesce(sum(amount - released_amount - refunded_amount), 0) FROM escrow_hold);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: platform pending balance differs from escrow'; END IF;

    -- 3. Each ledger row's balance_after is the running total of its wallet.
    SELECT count(*) INTO bad FROM (
        SELECT e.balance_after,
               sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                   OVER (PARTITION BY e.wallet_id ORDER BY e.id) AS running
          FROM ledger_entry e) x
     WHERE x.balance_after <> x.running;
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % ledger row(s) carry a wrong balance_after', bad; END IF;

    -- 4. Listing quantities match the cards on them.
    SELECT count(*) INTO bad FROM listing l
     WHERE l.quantity_available <> (SELECT count(*) FROM listing_unit u WHERE u.listing_id = l.id AND u.status = 'LISTED' AND u.deleted_at IS NULL)
        OR l.quantity_reserved  <> (SELECT count(*) FROM listing_unit u WHERE u.listing_id = l.id AND u.status = 'RESERVED' AND u.deleted_at IS NULL);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % listing(s) have wrong quantities', bad; END IF;

    -- 5. The cost books count the cards on hand, and so does the stock ledger.
    SELECT count(*) INTO bad FROM seller_variant_cost c
     WHERE c.total_quantity <> (SELECT count(*) FROM listing_unit u
                                 WHERE u.seller_profile_id = c.seller_profile_id AND u.catalog_variant_id = c.catalog_variant_id
                                   AND u.condition_code = c.condition_code
                                   AND u.status IN ('IN_STOCK', 'LISTED', 'RESERVED', 'ON_AUCTION', 'RETURNED'))
        OR c.total_quantity <> (SELECT coalesce(sum(m.quantity_delta), 0) FROM inventory_movement m
                                 WHERE m.seller_profile_id = c.seller_profile_id AND m.catalog_variant_id = c.catalog_variant_id
                                   AND m.condition_code = c.condition_code);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % cost row(s) disagree with stock', bad; END IF;

    -- 6. Order totals add up, and every line names exactly its cards.
    SELECT count(*) INTO bad FROM sales_order o
     WHERE o.items_subtotal <> (SELECT sum(items_subtotal) FROM seller_order WHERE sales_order_id = o.id)
        OR o.shipping_total <> (SELECT sum(shipping_fee) FROM seller_order WHERE sales_order_id = o.id);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % order(s) do not add up', bad; END IF;
    SELECT count(*) INTO bad FROM seller_order so
     WHERE so.items_subtotal <> (SELECT sum(line_total) FROM order_item WHERE seller_order_id = so.id);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % seller order(s) do not add up', bad; END IF;
    SELECT count(*) INTO bad FROM order_item oi
     WHERE oi.quantity <> (SELECT count(*) FROM order_item_unit WHERE order_item_id = oi.id);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % order line(s) have the wrong number of cards', bad; END IF;

    -- 7. Paid parts have escrow; completed parts were released and charged.
    SELECT count(*) INTO bad FROM seller_order so
     WHERE so.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
       AND NOT EXISTS (SELECT 1 FROM escrow_hold h WHERE h.seller_order_id = so.id);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % paid part(s) without escrow', bad; END IF;
    SELECT count(*) INTO bad FROM seller_order so
     WHERE so.status = 'COMPLETED'
       AND (NOT EXISTS (SELECT 1 FROM escrow_hold h WHERE h.seller_order_id = so.id AND h.status IN ('RELEASED', 'PARTIALLY_RELEASED'))
            OR NOT EXISTS (SELECT 1 FROM commission_charge c WHERE c.seller_order_id = so.id));
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % completed part(s) never released escrow', bad; END IF;

    -- 8. Review summaries match the visible reviews.
    SELECT count(*) INTO bad FROM seller_rating_summary s
     WHERE s.rating_count <> (SELECT count(*) FROM seller_review r WHERE r.seller_profile_id = s.seller_profile_id AND r.is_visible);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % rating summaries are stale', bad; END IF;

    -- 9. Discount codes: the counters match their rows, and a shop code's
    -- redemption is the shop discount on its part of the order.
    SELECT count(*) INTO bad FROM promotion p
     WHERE p.claimed_count <> (SELECT count(*) FROM promotion_claim c WHERE c.promotion_id = p.id)
        OR p.used_count <> (SELECT count(*) FROM promotion_redemption r
                             WHERE r.promotion_id = p.id AND r.status IN ('RESERVED', 'CONSUMED'));
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % promotion(s) have stale claimed/used counts', bad; END IF;
    SELECT count(*) INTO bad FROM promotion_redemption r JOIN promotion p ON p.id = r.promotion_id
      JOIN seller_order so ON so.id = r.seller_order_id
     WHERE p.owner_type = 'SELLER' AND r.amount <> so.seller_discount_amount;
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % shop-code redemption(s) differ from the shop discount', bad; END IF;

    -- Price sales [TCG-684]: SOLD_OUT needs every MTG listing's flash card taken, or the
    -- refresher would put the uncovered listings back on sale and the campaign reads ACTIVE.
    SELECT count(*) INTO bad FROM listing l
      JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
      JOIN catalog_product cp ON cp.id = cv.catalog_product_id
     WHERE l.status = 'ACTIVE' AND cp.game_id = (SELECT id FROM game WHERE code = 'MTG')
       AND NOT EXISTS (SELECT 1 FROM price_sale_listing psl JOIN price_sale s ON s.id = psl.sale_id
                        WHERE s.slug = 'midnight-flash' AND psl.listing_id = l.id
                          AND psl.sold_count = psl.quota);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % MTG listing(s) left on Midnight Flash', bad; END IF;
    SELECT count(*) INTO n FROM price_sale;
    IF n <> 13 THEN RAISE EXCEPTION 'verify: expected 13 price sales (8 + 5 in 10b), found %', n; END IF;

    -- Every auction status used by the demo UI and each penalty step must exist.
    SELECT count(DISTINCT status) INTO n FROM auction
     WHERE status IN ('LIVE', 'AWAITING_PAYMENT', 'SOLD', 'UNSOLD', 'SUSPENDED', 'CANCELLED');
    IF n <> 6 THEN RAISE EXCEPTION 'verify: auction demo statuses are incomplete'; END IF;
    SELECT count(*) INTO bad FROM auction a
     WHERE a.status = 'SOLD' AND NOT EXISTS (
         SELECT 1 FROM sales_order so JOIN payment p ON p.sales_order_id = so.id
          WHERE so.id = a.sales_order_id AND so.source = 'AUCTION'
            AND so.auction_id = a.id AND so.status = 'PAID' AND p.status = 'SUCCEEDED');
    IF bad > 0 THEN RAISE EXCEPTION 'verify: sold auction without a paid order'; END IF;
    SELECT count(*) INTO bad FROM auction a
     WHERE a.status = 'AWAITING_PAYMENT' AND a.sales_order_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM sales_order so WHERE so.id = a.sales_order_id
                       AND so.source = 'AUCTION' AND so.auction_id = a.id
                       AND so.status = 'PENDING_PAYMENT');
    IF bad > 0 THEN RAISE EXCEPTION 'verify: pending auction checkout has no unpaid order'; END IF;
    SELECT count(DISTINCT strike_count) INTO n FROM (
        SELECT count(*) AS strike_count FROM bidder_strike
         WHERE cleared_at IS NULL GROUP BY user_id) penalties;
    IF n <> 3 THEN RAISE EXCEPTION 'verify: auction penalty examples are incomplete'; END IF;
    SELECT count(*) INTO n FROM bidding_ban
     WHERE lifted_at IS NULL AND banned_until > now();
    IF n < 1 THEN RAISE EXCEPTION 'verify: no active auction bidding ban'; END IF;

    -- 11. Every page has something on it.
    -- Product pages: an offer on sale right now and at least three pictures.
    SELECT count(*) INTO bad FROM catalog_product p
     WHERE p.is_active AND NOT EXISTS (
           SELECT 1 FROM listing l JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
             JOIN seller_profile sp ON sp.id = l.seller_profile_id
            WHERE cv.catalog_product_id = p.id AND l.status = 'ACTIVE' AND l.quantity_available > 0
              AND sp.status = 'VERIFIED' AND NOT sp.vacation_mode);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % product page(s) have no offer on sale', bad; END IF;
    SELECT count(*) INTO bad FROM catalog_product p
     WHERE (SELECT count(*) FROM catalog_image ci WHERE ci.catalog_product_id = p.id) < 3;
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % product(s) have fewer than three pictures', bad; END IF;
    -- Listing pages: the shop's own photos (cards and sealed product at least two).
    SELECT count(*) INTO bad FROM listing l JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
      JOIN catalog_product p ON p.id = cv.catalog_product_id
     WHERE l.status = 'ACTIVE' AND p.product_type <> 'ACCESSORY'
       AND (SELECT count(*) FROM listing_image li WHERE li.listing_id = l.id) < 2;
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % active listing(s) have fewer than two photos', bad; END IF;

    -- Shop back office: every working shop (verified, not on vacation) has listings, orders that went
    -- all the way, reviews, a withdrawal, a code of its own and an auction.
    SELECT count(*) INTO bad FROM seller_profile sp
     WHERE sp.status = 'VERIFIED' AND NOT sp.vacation_mode
       AND ((SELECT count(*) FROM listing l WHERE l.seller_profile_id = sp.id AND l.status = 'ACTIVE') < 20
         OR (SELECT count(*) FROM seller_order so WHERE so.seller_profile_id = sp.id AND so.status = 'COMPLETED') < 3
         OR NOT EXISTS (SELECT 1 FROM seller_review r WHERE r.seller_profile_id = sp.id)
         OR NOT EXISTS (SELECT 1 FROM payout po WHERE po.seller_profile_id = sp.id)
         OR NOT EXISTS (SELECT 1 FROM promotion pr WHERE pr.seller_profile_id = sp.id)
         OR NOT EXISTS (SELECT 1 FROM auction a WHERE a.seller_profile_id = sp.id)
         OR NOT EXISTS (SELECT 1 FROM inventory_movement m WHERE m.seller_profile_id = sp.id));
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % shop(s) have an empty back-office page', bad; END IF;
    -- The shops added for the presentation show every order state and returns.
    SELECT count(*) INTO bad FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id
     WHERE sp.status = 'VERIFIED' AND NOT sp.vacation_mode AND u.created_at > pg_temp.ago(335)
       AND u.username NOT IN ('pegasus_official', 'card_kingdom_th', 'tcg_corner', 'mint_vault', 'demo_seller',
                              'grand_line_cards', 'kanto_cards', 'banned_trader')
       AND ((SELECT count(DISTINCT so.status) FROM seller_order so WHERE so.seller_profile_id = sp.id
              AND so.status IN ('PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELLED')) < 6
         OR NOT EXISTS (SELECT 1 FROM return_request rr JOIN seller_order so ON so.id = rr.seller_order_id
                         WHERE so.seller_profile_id = sp.id));
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % new shop(s) miss an order state or a return', bad; END IF;

    -- Buyer pages for every buyer added for the presentation: orders (one finished), an address,
    -- a basket, a wish list, a public collection and collected codes.
    SELECT count(*) INTO bad FROM seed_new_buyer b
     WHERE (SELECT count(*) FROM sales_order o WHERE o.buyer_id = b.user_id) < 3
        OR NOT EXISTS (SELECT 1 FROM sales_order o WHERE o.buyer_id = b.user_id AND o.status = 'COMPLETED')
        OR NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = b.user_id AND a.deleted_at IS NULL)
        OR NOT EXISTS (SELECT 1 FROM cart c JOIN cart_item ci ON ci.cart_id = c.id WHERE c.user_id = b.user_id)
        OR NOT EXISTS (SELECT 1 FROM wishlist_item w WHERE w.user_id = b.user_id)
        OR NOT EXISTS (SELECT 1 FROM collection_item ci WHERE ci.user_id = b.user_id AND ci.is_public AND ci.deleted_at IS NULL)
        OR NOT EXISTS (SELECT 1 FROM promotion_claim pc WHERE pc.user_id = b.user_id);
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % new buyer(s) have an empty account page', bad; END IF;

    -- Admin queues and lists have work in them.
    IF (SELECT count(*) FROM payment WHERE status = 'AWAITING_VERIFICATION') < 10 THEN
        RAISE EXCEPTION 'verify: fewer than 10 slips waiting for the admin'; END IF;
    IF (SELECT count(*) FROM refund WHERE status IN ('APPROVED', 'PROCESSING')) < 5 THEN
        RAISE EXCEPTION 'verify: fewer than 5 refunds waiting for the admin'; END IF;
    IF (SELECT count(*) FROM payout WHERE status IN ('REQUESTED', 'APPROVED', 'PROCESSING')) < 5 THEN
        RAISE EXCEPTION 'verify: fewer than 5 withdrawals waiting for the admin'; END IF;
    IF (SELECT count(*) FROM seller_verification WHERE status IN ('SUBMITTED', 'UNDER_REVIEW')) < 4 THEN
        RAISE EXCEPTION 'verify: fewer than 4 seller applications waiting'; END IF;
    IF NOT EXISTS (SELECT 1 FROM seller_review WHERE NOT is_visible) THEN
        RAISE EXCEPTION 'verify: no hidden review for the admin review page'; END IF;
    IF (SELECT count(*) FROM audit_log) < 500 THEN RAISE EXCEPTION 'verify: the activity log is nearly empty'; END IF;
    IF (SELECT count(*) FROM (SELECT q_norm FROM search_query_log
                               WHERE created_at >= now() - interval '7 days' AND result_count > 0
                               GROUP BY q_norm HAVING count(DISTINCT session_hash) >= 3) t) < 10 THEN
        RAISE EXCEPTION 'verify: fewer than 10 trending searches'; END IF;
    IF (SELECT count(*) FROM auction a WHERE a.status = 'LIVE' AND a.bid_count > 0 AND a.ends_at > now()) < 10 THEN
        RAISE EXCEPTION 'verify: fewer than 10 live auctions with bids'; END IF;
    IF (SELECT count(*) FROM home_banner WHERE is_active AND (starts_at IS NULL OR starts_at <= now())
                                         AND (ends_at IS NULL OR ends_at > now())) < 8 THEN
        RAISE EXCEPTION 'verify: fewer than 8 home slides live'; END IF;
    IF (SELECT count(DISTINCT catalog_variant_id) FROM variant_market_stat
         WHERE stat_date <= ((SELECT anchor FROM seed_clock) AT TIME ZONE 'Asia/Bangkok')::date - 80) < 20 THEN
        RAISE EXCEPTION 'verify: fewer than 20 cards with three months of price history'; END IF;

    -- 10. Every table covered by this seed has something in it. Auction and
    -- listing_sale_price is filled by the app's refresher, not the seed.
    FOR t IN SELECT c.relname FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
              WHERE ns.nspname = 'public' AND c.relkind = 'r'
                AND c.relname NOT IN ('flyway_schema_history', 'auction', 'auction_bid',
                                      'bidder_strike', 'bidding_ban',
                                      'listing_sale_price')
              ORDER BY c.relname LOOP
        EXECUTE format('SELECT count(*) FROM %I', t.relname) INTO n;
        IF n = 0 THEN empty := empty || t.relname::text; END IF;
    END LOOP;
    IF cardinality(empty) > 0 THEN
        RAISE EXCEPTION 'verify: these tables are still empty: %', array_to_string(empty, ', ');
    END IF;
END;
$$;

-- updated_at triggers back on, now that history has been written.
ALTER TABLE user_account          ENABLE TRIGGER trg_user_account_updated_at;
ALTER TABLE address               ENABLE TRIGGER trg_address_updated_at;
ALTER TABLE seller_profile        ENABLE TRIGGER trg_seller_profile_updated_at;
ALTER TABLE catalog_product       ENABLE TRIGGER trg_catalog_product_updated_at;
ALTER TABLE listing               ENABLE TRIGGER trg_listing_updated_at;
ALTER TABLE listing_unit          ENABLE TRIGGER trg_listing_unit_updated_at;
ALTER TABLE seller_variant_cost   ENABLE TRIGGER trg_seller_variant_cost_updated_at;
ALTER TABLE cart                  ENABLE TRIGGER trg_cart_updated_at;
ALTER TABLE cart_item             ENABLE TRIGGER trg_cart_item_updated_at;
ALTER TABLE sales_order           ENABLE TRIGGER trg_sales_order_updated_at;
ALTER TABLE seller_order          ENABLE TRIGGER trg_seller_order_updated_at;
ALTER TABLE shipment              ENABLE TRIGGER trg_shipment_updated_at;
ALTER TABLE wallet                ENABLE TRIGGER trg_wallet_updated_at;
ALTER TABLE seller_review         ENABLE TRIGGER trg_seller_review_updated_at;
ALTER TABLE seller_rating_summary ENABLE TRIGGER trg_seller_rating_summary_updated_at;
ALTER TABLE collection_item       ENABLE TRIGGER trg_collection_item_updated_at;
ALTER TABLE platform_setting      ENABLE TRIGGER trg_platform_setting_updated_at;
ALTER TABLE home_banner           ENABLE TRIGGER trg_home_banner_updated_at;

-- What came out of it.
SELECT (SELECT count(*) FROM user_account)     AS accounts,
       (SELECT count(*) FROM catalog_product)  AS products,
       (SELECT count(*) FROM catalog_variant)  AS printings,
       (SELECT count(*) FROM listing)          AS listings,
       (SELECT count(*) FROM listing_unit)     AS cards,
       (SELECT count(*) FROM sales_order)      AS orders,
       (SELECT count(*) FROM ledger_entry)     AS ledger_rows,
       (SELECT count(*) FROM seller_review)    AS reviews;
