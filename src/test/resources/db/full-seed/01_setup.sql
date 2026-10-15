-- Shared clock, lookups and runtime settings for the rest of the seed.
--
-- Every timestamp is relative to one anchor taken when the seed starts, so the
-- data always reads as "the last few months" whenever it is loaded.

CREATE TEMP TABLE seed_clock ON COMMIT DROP AS
SELECT date_trunc('minute', now()) AS anchor;

-- pg_temp functions vanish with the session, so nothing here outlives the seed.
CREATE FUNCTION pg_temp.ago(p_days numeric, p_hours numeric DEFAULT 0) RETURNS timestamptz
    LANGUAGE sql STABLE AS $$
    SELECT (SELECT anchor FROM seed_clock)
           - make_interval(secs => (p_days * 86400 + p_hours * 3600)::double precision)
$$;

CREATE FUNCTION pg_temp.uid(p_username text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE found_id bigint;
BEGIN
    SELECT id INTO found_id FROM user_account WHERE username = p_username;
    IF found_id IS NULL THEN
        RAISE EXCEPTION 'seed: no user %', p_username;
    END IF;
    RETURN found_id;
END;
$$;

CREATE FUNCTION pg_temp.spid(p_username text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE found_id bigint;
BEGIN
    SELECT sp.id INTO found_id
      FROM seller_profile sp JOIN user_account u ON u.id = sp.user_id
     WHERE u.username = p_username;
    IF found_id IS NULL THEN
        RAISE EXCEPTION 'seed: no seller profile for %', p_username;
    END IF;
    RETURN found_id;
END;
$$;

CREATE FUNCTION pg_temp.vid(p_sku text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE found_id bigint;
BEGIN
    SELECT id INTO found_id FROM catalog_variant WHERE sku = p_sku;
    IF found_id IS NULL THEN
        RAISE EXCEPTION 'seed: no variant %', p_sku;
    END IF;
    RETURN found_id;
END;
$$;

-- updated_at triggers stamp now() on every UPDATE. The seed replays months of
-- history in one go, so it switches them off and writes the historical time
-- itself; 99_verify.sql switches them back on in the same transaction.
ALTER TABLE user_account          DISABLE TRIGGER trg_user_account_updated_at;
ALTER TABLE address               DISABLE TRIGGER trg_address_updated_at;
ALTER TABLE seller_profile        DISABLE TRIGGER trg_seller_profile_updated_at;
ALTER TABLE catalog_product       DISABLE TRIGGER trg_catalog_product_updated_at;
ALTER TABLE listing               DISABLE TRIGGER trg_listing_updated_at;
ALTER TABLE listing_unit          DISABLE TRIGGER trg_listing_unit_updated_at;
ALTER TABLE seller_variant_cost   DISABLE TRIGGER trg_seller_variant_cost_updated_at;
ALTER TABLE cart                  DISABLE TRIGGER trg_cart_updated_at;
ALTER TABLE cart_item             DISABLE TRIGGER trg_cart_item_updated_at;
ALTER TABLE sales_order           DISABLE TRIGGER trg_sales_order_updated_at;
ALTER TABLE seller_order          DISABLE TRIGGER trg_seller_order_updated_at;
ALTER TABLE shipment              DISABLE TRIGGER trg_shipment_updated_at;
ALTER TABLE wallet                DISABLE TRIGGER trg_wallet_updated_at;
ALTER TABLE seller_review         DISABLE TRIGGER trg_seller_review_updated_at;
ALTER TABLE seller_rating_summary DISABLE TRIGGER trg_seller_rating_summary_updated_at;
ALTER TABLE collection_item       DISABLE TRIGGER trg_collection_item_updated_at;
ALTER TABLE platform_setting      DISABLE TRIGGER trg_platform_setting_updated_at;
ALTER TABLE home_banner           DISABLE TRIGGER trg_home_banner_updated_at;

-- The values the migrations ship with. After -Reset they are put back; on a
-- fresh database they are already these.
INSERT INTO platform_setting (setting_key, setting_value, description, updated_at) VALUES
    ('escrow.auto_release_days',      '7',      'Days after delivery before escrow releases without buyer confirmation.', pg_temp.ago(400)),
    ('order.cancel_window_hours',     '24',     'How long a buyer may cancel an unshipped order.', pg_temp.ago(400)),
    ('return.request_window_days',    '7',      'Days after delivery a return may still be opened.', pg_temp.ago(400)),
    ('commission.default_rate',       '5.0',    'Percent used when no commission_rule matches.', pg_temp.ago(400)),
    ('payout.minimum_amount',         '100.00', 'Smallest payout a seller may request.', pg_temp.ago(400)),
    ('pricing.median_min_sample',     '3',      'Below this sample size the median is ignored for AUTO_MEDIAN.', pg_temp.ago(400)),
    ('order.payment_timeout_minutes', '60',     'Minutes an unpaid order may hold its reserved cards before it is cancelled.', pg_temp.ago(400)),
    ('payment.slip_auto_approve',     'false',  'Bank-transfer slip approval is manual; this legacy switch cannot be enabled.', pg_temp.ago(400)),
    ('payment.transfer_account',
     '{"bankName":"TEST BANK","accountName":"PEGASUS TCG","accountNumber":"000-0-00000-0","promptPayId":"0000000000"}',
     'Bank account shown to buyers for bank transfers; replace before use.', pg_temp.ago(400))
ON CONFLICT (setting_key) DO UPDATE
   SET setting_value = EXCLUDED.setting_value,
       description   = EXCLUDED.description,
       updated_by    = NULL,
       updated_at    = EXCLUDED.updated_at;

-- Exactly one platform wallet, at zero: the seed's own ledger is the only thing
-- that moves it.
INSERT INTO wallet (kind, created_at, updated_at)
SELECT 'PLATFORM', pg_temp.ago(400), pg_temp.ago(400)
 WHERE NOT EXISTS (SELECT 1 FROM wallet WHERE kind = 'PLATFORM');

CREATE FUNCTION pg_temp.platform_wallet() RETURNS bigint
    LANGUAGE sql STABLE AS $$ SELECT id FROM wallet WHERE kind = 'PLATFORM' $$;

-- Order numbers count from 1 again; there are no orders left to collide with.
ALTER SEQUENCE order_number_seq RESTART WITH 1;
