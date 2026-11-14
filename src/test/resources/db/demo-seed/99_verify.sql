-- Demo seed: checks the data against the invariants the application relies on
-- (checks 1-8 are the same as full-seed/99_verify.sql), then that every role has
-- something waiting to be tried. Any failure raises and the whole seed rolls back.

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

    -- 9. Only Pokemon and One Piece are in the catalogue.
    SELECT count(*) INTO bad FROM game WHERE code NOT IN ('POKEMON', 'ONE_PIECE');
    IF bad > 0 THEN RAISE EXCEPTION 'verify: % other game(s) left in the catalogue', bad; END IF;

    -- 10. Something waiting for each role to try.
    SELECT count(*) INTO n FROM payment WHERE status = 'AWAITING_VERIFICATION';
    IF n < 1 THEN RAISE EXCEPTION 'verify: admin has no transfer slip to check'; END IF;
    SELECT count(*) INTO n FROM seller_verification WHERE status = 'SUBMITTED';
    IF n < 1 THEN RAISE EXCEPTION 'verify: admin has no seller application to review'; END IF;
    SELECT count(*) INTO n FROM payout WHERE status = 'REQUESTED';
    IF n < 1 THEN RAISE EXCEPTION 'verify: admin has no payout request to approve'; END IF;
    SELECT count(*) INTO n FROM seller_order WHERE status = 'PAID' AND seller_profile_id = pg_temp.spid('demo_seller');
    IF n < 1 THEN RAISE EXCEPTION 'verify: demo_seller has no paid order to accept'; END IF;
    SELECT count(*) INTO n FROM seller_order so JOIN sales_order o ON o.id = so.sales_order_id
     WHERE so.status = 'SHIPPED' AND o.buyer_id = pg_temp.uid('demo_buyer');
    IF n < 1 THEN RAISE EXCEPTION 'verify: demo_buyer has no shipped order to confirm'; END IF;
    SELECT count(*) INTO n FROM seller_order WHERE status = 'COMPLETED';
    IF n < 5 THEN RAISE EXCEPTION 'verify: expected at least 5 completed sales, found %', n; END IF;
    SELECT count(*) INTO n FROM wallet w
     WHERE w.user_id = pg_temp.uid('demo_seller')
       AND w.balance_available - coalesce((SELECT sum(p.amount) FROM payout p
                                            WHERE p.wallet_id = w.id AND p.status IN ('REQUESTED', 'APPROVED', 'PROCESSING')), 0)
           >= pg_temp.setting_num('payout.minimum_amount');
    IF n < 1 THEN RAISE EXCEPTION 'verify: demo_seller has no balance to request a payout with'; END IF;
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
       (SELECT count(*) FROM listing)          AS listings,
       (SELECT count(*) FROM listing_unit)     AS cards,
       (SELECT count(*) FROM sales_order)      AS orders,
       (SELECT count(*) FROM seller_review)    AS reviews,
       (SELECT count(*) FROM payout)           AS payouts;
