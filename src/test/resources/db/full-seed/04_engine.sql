-- The replay engine.
--
-- Listings, stock, orders, payments, shipping, escrow, refunds, returns and
-- payouts are not inserted as finished rows. 05 and 06 describe what happened
-- and when, as events; run_events() then plays them in time order through the
-- functions below, which write the same rows, in the same way, as the services
-- do (CheckoutService, PaymentService, OrderLifecycleService, EscrowReleaseService,
-- RefundService, ReturnService, PayoutService, InventoryLedger). That is what
-- keeps wallets equal to the ledger, listing counts equal to the cards, and
-- every ledger row's balance_after in the right order.
--
-- A step that the real code would refuse (wrong status, not enough stock or
-- balance) raises here, so a mistake in the scenario fails the whole seed.

CREATE TEMP TABLE seed_event (
    id   serial PRIMARY KEY,
    at   timestamptz NOT NULL,
    kind text        NOT NULL,
    a    jsonb       NOT NULL DEFAULT '{}'
) ON COMMIT DROP;

CREATE TEMP TABLE seed_listing_ref (ref text PRIMARY KEY, listing_id bigint NOT NULL) ON COMMIT DROP;
CREATE TEMP TABLE seed_order_ref   (okey text PRIMARY KEY, sales_order_id bigint NOT NULL) ON COMMIT DROP;
CREATE TEMP TABLE seed_payout_ref  (pkey text PRIMARY KEY, payout_id bigint NOT NULL) ON COMMIT DROP;

-- Orders: who, when; which listings; which tariff per seller.
CREATE TEMP TABLE seed_order_def (
    okey  text PRIMARY KEY,
    buyer text NOT NULL,
    note  text
) ON COMMIT DROP;
CREATE TEMP TABLE seed_line_def (
    line_no serial,
    okey    text NOT NULL,
    seller  text NOT NULL,
    lref    text NOT NULL,
    qty     int  NOT NULL
) ON COMMIT DROP;
CREATE TEMP TABLE seed_sub_def (
    okey   text NOT NULL,
    seller text NOT NULL,
    option_name text,
    PRIMARY KEY (okey, seller)
) ON COMMIT DROP;

-- ---------- lookups ----------

CREATE FUNCTION pg_temp.lid(p_ref text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE found_id bigint;
BEGIN
    SELECT listing_id INTO found_id FROM seed_listing_ref WHERE ref = p_ref;
    IF found_id IS NULL THEN
        RAISE EXCEPTION 'seed: no listing %', p_ref;
    END IF;
    RETURN found_id;
END;
$$;

CREATE FUNCTION pg_temp.oid_(p_okey text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE found_id bigint;
BEGIN
    SELECT sales_order_id INTO found_id FROM seed_order_ref WHERE okey = p_okey;
    IF found_id IS NULL THEN
        RAISE EXCEPTION 'seed: order % not placed yet', p_okey;
    END IF;
    RETURN found_id;
END;
$$;

CREATE FUNCTION pg_temp.soid(p_okey text, p_seller text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE found_id bigint;
BEGIN
    SELECT so.id INTO found_id
      FROM seller_order so
     WHERE so.sales_order_id = pg_temp.oid_(p_okey)
       AND so.seller_profile_id = pg_temp.spid(p_seller);
    IF found_id IS NULL THEN
        RAISE EXCEPTION 'seed: order % has no part for %', p_okey, p_seller;
    END IF;
    RETURN found_id;
END;
$$;

CREATE FUNCTION pg_temp.seller_user(p_seller_profile bigint) RETURNS bigint
    LANGUAGE sql STABLE AS $$ SELECT user_id FROM seller_profile WHERE id = p_seller_profile $$;

CREATE FUNCTION pg_temp.setting_num(p_key text) RETURNS numeric
    LANGUAGE sql STABLE AS $$ SELECT (setting_value #>> '{}')::numeric FROM platform_setting WHERE setting_key = p_key $$;

-- ---------- money ----------

-- WalletLedgerService.post: one balance update and one immutable ledger row.
CREATE FUNCTION pg_temp.post(p_wallet bigint, p_bucket text, p_type text, p_dir text, p_amount numeric,
                             p_ref_type text, p_ref bigint, p_at timestamptz, p_memo text DEFAULT NULL)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    w     wallet%ROWTYPE;
    delta numeric(16,2);
BEGIN
    IF p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION 'seed: ledger amount must be positive (% %)', p_type, p_amount;
    END IF;
    SELECT * INTO w FROM wallet WHERE id = p_wallet FOR UPDATE;
    delta := CASE p_dir WHEN 'CREDIT' THEN p_amount ELSE -p_amount END;
    IF p_bucket = 'AVAILABLE' THEN
        w.balance_available := w.balance_available + delta;
    ELSE
        w.balance_pending := w.balance_pending + delta;
    END IF;
    IF w.balance_available < 0 OR w.balance_pending < 0 THEN
        RAISE EXCEPTION 'seed: wallet % would go negative on % %', p_wallet, p_type, p_amount;
    END IF;
    UPDATE wallet
       SET balance_available = w.balance_available, balance_pending = w.balance_pending,
           version = version + 1, updated_at = p_at
     WHERE id = p_wallet;
    INSERT INTO ledger_entry (wallet_id, entry_type, direction, amount, balance_after, reference_type,
                              reference_id, memo, created_at)
    VALUES (p_wallet, p_type, p_dir, p_amount, w.balance_available + w.balance_pending, p_ref_type,
            p_ref, p_memo, p_at);
END;
$$;

CREATE FUNCTION pg_temp.ensure_seller_wallet(p_user bigint, p_at timestamptz) RETURNS bigint
    LANGUAGE plpgsql AS $$
DECLARE found_id bigint;
BEGIN
    INSERT INTO wallet (kind, user_id, created_at, updated_at)
    VALUES ('SELLER', p_user, p_at, p_at)
    ON CONFLICT (user_id) DO NOTHING;
    SELECT id INTO found_id FROM wallet WHERE user_id = p_user;
    RETURN found_id;
END;
$$;

-- LedgerService.quoteCommission: SELLER > CATEGORY > GAME > GLOBAL, then priority.
CREATE FUNCTION pg_temp.quote_commission(p_seller bigint, p_subtotal numeric, p_variants bigint[], p_at timestamptz,
                                         OUT rate numeric, OUT fee numeric)
    LANGUAGE plpgsql AS $$
DECLARE
    games  smallint[];
    cats   int[];
    g      smallint;
    c      int;
    r      commission_rule%ROWTYPE;
    fixed  numeric := 0;
    min_f  numeric;
    max_f  numeric;
BEGIN
    IF p_subtotal <= 0 THEN
        rate := 0; fee := 0; RETURN;
    END IF;
    SELECT array_agg(DISTINCT p.game_id), array_agg(DISTINCT p.category_id)
      INTO games, cats
      FROM catalog_variant v JOIN catalog_product p ON p.id = v.catalog_product_id
     WHERE v.id = ANY (p_variants);
    g := CASE WHEN cardinality(games) = 1 THEN games[1] END;
    c := CASE WHEN cardinality(cats) = 1 THEN cats[1] END;

    SELECT * INTO r
      FROM commission_rule cr
     WHERE cr.is_active
       AND cr.effective_from <= p_at
       AND (cr.effective_to IS NULL OR cr.effective_to > p_at)
       AND ((cr.scope = 'SELLER' AND cr.seller_profile_id = p_seller)
         OR (cr.scope = 'CATEGORY' AND cr.category_id = c)
         OR (cr.scope = 'GAME' AND cr.game_id = g)
         OR cr.scope = 'GLOBAL')
     ORDER BY CASE cr.scope WHEN 'SELLER' THEN 4 WHEN 'CATEGORY' THEN 3 WHEN 'GAME' THEN 2 ELSE 1 END DESC,
              cr.priority DESC, cr.id DESC
     LIMIT 1;

    IF FOUND THEN
        rate := r.rate_percent; fixed := r.fixed_fee; min_f := r.min_fee; max_f := r.max_fee;
    ELSE
        rate := pg_temp.setting_num('commission.default_rate');
    END IF;
    fee := round(p_subtotal * rate / 100, 2) + fixed;
    IF min_f IS NOT NULL THEN fee := greatest(fee, min_f); END IF;
    IF max_f IS NOT NULL THEN fee := least(fee, max_f); END IF;
    fee := greatest(least(fee, p_subtotal), 0);
END;
$$;

-- ---------- stock (InventoryLedger) ----------

CREATE FUNCTION pg_temp.cost_receive(p_seller bigint, p_variant bigint, p_cond text, p_qty int, p_cost numeric,
                                     p_at timestamptz, OUT created boolean, OUT avg_after numeric)
    LANGUAGE plpgsql AS $$
DECLARE c seller_variant_cost%ROWTYPE;
BEGIN
    SELECT * INTO c FROM seller_variant_cost
     WHERE seller_profile_id = p_seller AND catalog_variant_id = p_variant AND condition_code = p_cond
       FOR UPDATE;
    created := NOT FOUND;
    IF created THEN
        INSERT INTO seller_variant_cost (seller_profile_id, catalog_variant_id, condition_code, updated_at)
        VALUES (p_seller, p_variant, p_cond, p_at)
        RETURNING * INTO c;
    END IF;
    UPDATE seller_variant_cost
       SET total_quantity    = c.total_quantity + p_qty,
           total_cost        = round(c.total_cost + p_cost * p_qty, 2),
           average_unit_cost = round(round(c.total_cost + p_cost * p_qty, 2) / (c.total_quantity + p_qty), 4),
           last_movement_at  = p_at,
           updated_at        = p_at
     WHERE id = c.id
     RETURNING average_unit_cost INTO avg_after;
END;
$$;

-- Cards leaving at the running average; returns the cost each left at (2 dp).
CREATE FUNCTION pg_temp.cost_issue(p_seller bigint, p_variant bigint, p_cond text, p_n int, p_at timestamptz)
    RETURNS numeric LANGUAGE plpgsql AS $$
DECLARE
    c        seller_variant_cost%ROWTYPE;
    consumed numeric;
    left_qty int;
BEGIN
    SELECT * INTO c FROM seller_variant_cost
     WHERE seller_profile_id = p_seller AND catalog_variant_id = p_variant AND condition_code = p_cond
       FOR UPDATE;
    IF NOT FOUND OR c.total_quantity < p_n THEN
        RAISE EXCEPTION 'seed: cost books for seller % variant % % hold fewer than % cards', p_seller, p_variant, p_cond, p_n;
    END IF;
    consumed := round(c.average_unit_cost * p_n, 2);
    left_qty := c.total_quantity - p_n;
    UPDATE seller_variant_cost
       SET total_quantity   = left_qty,
           total_cost       = CASE WHEN left_qty = 0 THEN 0 ELSE round(greatest(c.total_cost - consumed, 0), 2) END,
           last_movement_at = p_at,
           updated_at       = p_at
     WHERE id = c.id;
    RETURN round(c.average_unit_cost, 2);
END;
$$;

-- ListingUnitService.stockIn: one card per row, one ledger line per card, one batch per call.
CREATE FUNCTION pg_temp.stock_in(p_seller bigint, p_variant bigint, p_cond text, p_listing bigint, p_qty int,
                                 p_cost numeric, p_at timestamptz, p_actor bigint,
                                 p_cert text DEFAULT NULL, p_note text DEFAULT NULL)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    r       record;
    batch   uuid := gen_random_uuid();
    unit_id bigint;
BEGIN
    SELECT * INTO r FROM pg_temp.cost_receive(p_seller, p_variant, p_cond, p_qty, p_cost, p_at);
    FOR i IN 1..p_qty LOOP
        INSERT INTO listing_unit (seller_profile_id, catalog_variant_id, condition_code, listing_id, status,
                                  cert_number, acquisition_cost, acquired_at, unit_note, created_at, updated_at)
        VALUES (p_seller, p_variant, p_cond, p_listing,
                CASE WHEN p_listing IS NULL THEN 'IN_STOCK' ELSE 'LISTED' END,
                CASE WHEN p_cert IS NULL THEN NULL WHEN p_qty = 1 THEN p_cert ELSE p_cert || '-' || i END,
                p_cost, p_at, p_note, p_at, p_at)
        RETURNING id INTO unit_id;
        INSERT INTO inventory_movement (seller_profile_id, listing_id, listing_unit_id, catalog_variant_id,
                                        condition_code, movement_type, quantity_delta, unit_cost, created_by,
                                        created_at, stock_in_batch_uid, average_cost_after)
        VALUES (p_seller, p_listing, unit_id, p_variant, p_cond,
                CASE WHEN r.created THEN 'INITIAL_STOCK' ELSE 'RESTOCK' END, 1, p_cost, p_actor,
                p_at, batch, r.avg_after);
    END LOOP;
END;
$$;

-- ---------- listings (ListingService) ----------

CREATE FUNCTION pg_temp.create_listing(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    sp      bigint := pg_temp.spid(a->>'seller');
    actor   bigint := pg_temp.seller_user(pg_temp.spid(a->>'seller'));
    variant bigint := pg_temp.vid(a->>'sku');
    l_id    bigint;
    st      text   := coalesce(a->>'status', 'ACTIVE');
    mode    text   := coalesce(a->>'mode', 'MANUAL');
BEGIN
    INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, grading_company, grade_value,
                         price, pricing_mode, auto_price_offset_percent, auto_price_floor, auto_price_ceiling,
                         status, lot_label, public_note, published_at, created_at, updated_at)
    VALUES (sp, variant, a->>'cond', a->>'grader', (a->>'grade')::numeric,
            (a->>'price')::numeric, mode, (a->>'offset')::numeric, (a->>'floor')::numeric, (a->>'ceiling')::numeric,
            st, a->>'lot', a->>'note', CASE WHEN st = 'DRAFT' THEN NULL ELSE p_at END, p_at, p_at)
    RETURNING id INTO l_id;
    INSERT INTO seed_listing_ref VALUES (a->>'ref', l_id);

    INSERT INTO listing_price_history (listing_id, old_price, new_price, pricing_mode, changed_by, reason, created_at)
    VALUES (l_id, NULL, (a->>'price')::numeric, mode, actor, 'listing created', p_at);

    -- The seller's own photos. Seed sellers photograph the card next to its official art,
    -- so the listing reuses the catalogue picture already in MinIO.
    IF coalesce((a->>'photos')::int, 1) > 0 THEN
        INSERT INTO listing_image (listing_id, image_key, sort_order, is_primary, created_at)
        SELECT l_id, img.image_key, (row_number() OVER (ORDER BY img.is_primary DESC, img.sort_order) - 1)::smallint,
               row_number() OVER (ORDER BY img.is_primary DESC, img.sort_order) = 1, p_at
          FROM catalog_image img
          JOIN catalog_variant cv ON cv.catalog_product_id = img.catalog_product_id
         WHERE cv.id = variant
           AND (img.catalog_variant_id IS NULL OR img.catalog_variant_id = variant)
         ORDER BY img.is_primary DESC, img.sort_order
         LIMIT coalesce((a->>'photos')::int, 1);
    END IF;

    IF coalesce((a->>'qty')::int, 0) > 0 THEN
        PERFORM pg_temp.stock_in(sp, variant, a->>'cond', l_id, (a->>'qty')::int, (a->>'cost')::numeric,
                                 p_at, actor, a->>'cert', a->>'unit_note');
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.price_change(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    l      listing%ROWTYPE;
    by_job boolean := coalesce((a->>'job')::boolean, false);
    mode   text;
BEGIN
    SELECT * INTO l FROM listing WHERE id = pg_temp.lid(a->>'ref') FOR UPDATE;
    mode := CASE WHEN by_job THEN 'AUTO_MEDIAN' ELSE coalesce(a->>'mode', l.pricing_mode) END;
    INSERT INTO listing_price_history (listing_id, old_price, new_price, pricing_mode, changed_by, reason, created_at)
    VALUES (l.id, l.price, (a->>'price')::numeric, mode,
            CASE WHEN by_job THEN NULL ELSE pg_temp.seller_user(l.seller_profile_id) END,
            coalesce(a->>'reason', CASE WHEN by_job THEN 'median of active listings' ELSE 'set by seller' END), p_at);
    UPDATE listing
       SET price = (a->>'price')::numeric, pricing_mode = mode, updated_at = p_at,
           last_auto_priced_at = CASE WHEN by_job THEN p_at ELSE last_auto_priced_at END
     WHERE id = l.id;
END;
$$;

CREATE FUNCTION pg_temp.listing_status(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE l_id bigint := pg_temp.lid(a->>'ref');
BEGIN
    IF a->>'status' = 'DELISTED' THEN
        -- Cards on sale go back into the seller's hands; the listing is closed for good.
        UPDATE listing_unit SET listing_id = NULL, status = 'IN_STOCK', updated_at = p_at
         WHERE listing_id = l_id AND status = 'LISTED';
    END IF;
    UPDATE listing SET status = a->>'status', updated_at = p_at WHERE id = l_id;
END;
$$;

CREATE FUNCTION pg_temp.restock(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE l listing%ROWTYPE;
BEGIN
    SELECT * INTO l FROM listing WHERE id = pg_temp.lid(a->>'ref');
    PERFORM pg_temp.stock_in(l.seller_profile_id, l.catalog_variant_id, l.condition_code, l.id,
                             (a->>'qty')::int, (a->>'cost')::numeric, p_at, pg_temp.seller_user(l.seller_profile_id));
    UPDATE listing SET updated_at = p_at WHERE id = l.id;
END;
$$;

-- Cards kept in hand, on no listing.
CREATE FUNCTION pg_temp.stock_hand(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE sp bigint := pg_temp.spid(a->>'seller');
BEGIN
    PERFORM pg_temp.stock_in(sp, pg_temp.vid(a->>'sku'), a->>'cond', NULL, (a->>'qty')::int,
                             (a->>'cost')::numeric, p_at, pg_temp.seller_user(sp), a->>'cert', a->>'unit_note');
END;
$$;

-- ListingUnitService.writeOff
CREATE FUNCTION pg_temp.write_off(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    sp   bigint := pg_temp.spid(a->>'seller');
    u    listing_unit%ROWTYPE;
    cost numeric;
BEGIN
    SELECT * INTO u FROM listing_unit
     WHERE seller_profile_id = sp AND catalog_variant_id = pg_temp.vid(a->>'sku')
       AND condition_code = a->>'cond' AND status IN ('IN_STOCK', 'RETURNED')
     ORDER BY acquired_at, id LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: nothing in hand to write off for % %', a->>'seller', a->>'sku';
    END IF;
    UPDATE listing_unit SET listing_id = NULL, status = 'WRITTEN_OFF', updated_at = p_at WHERE id = u.id;
    cost := pg_temp.cost_issue(sp, u.catalog_variant_id, u.condition_code, 1, p_at);
    INSERT INTO inventory_movement (seller_profile_id, listing_id, listing_unit_id, catalog_variant_id, condition_code,
                                    movement_type, quantity_delta, unit_cost, note, created_by, created_at)
    VALUES (sp, u.listing_id, u.id, u.catalog_variant_id, u.condition_code, 'LOSS', -1, cost, a->>'note',
            pg_temp.seller_user(sp), p_at);
END;
$$;

-- ---------- orders (OrderLifecycleService) ----------

CREATE FUNCTION pg_temp.can_move(p_from text, p_to text) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS $$
    SELECT p_to = ANY (CASE p_from
        WHEN 'PENDING_PAYMENT'  THEN ARRAY['PAID', 'CANCELLED']
        WHEN 'PAID'             THEN ARRAY['PREPARING', 'SHIPPED', 'CANCELLED']
        WHEN 'PREPARING'        THEN ARRAY['SHIPPED', 'CANCELLED']
        WHEN 'SHIPPED'          THEN ARRAY['DELIVERED', 'COMPLETED', 'RETURN_REQUESTED']
        WHEN 'DELIVERED'        THEN ARRAY['COMPLETED', 'RETURN_REQUESTED']
        WHEN 'RETURN_REQUESTED' THEN ARRAY['SHIPPED', 'DELIVERED', 'RETURNED', 'COMPLETED', 'REFUNDED']
        WHEN 'RETURNED'         THEN ARRAY['COMPLETED', 'REFUNDED']
        ELSE ARRAY[]::text[] END)
$$;

CREATE FUNCTION pg_temp.so_move(p_so bigint, p_to text, p_at timestamptz, p_actor bigint, p_note text)
    RETURNS text LANGUAGE plpgsql AS $$
DECLARE old_status text;
BEGIN
    SELECT status INTO old_status FROM seller_order WHERE id = p_so FOR UPDATE;
    IF NOT pg_temp.can_move(old_status, p_to) THEN
        RAISE EXCEPTION 'seed: seller order % cannot move % -> %', p_so, old_status, p_to;
    END IF;
    UPDATE seller_order SET status = p_to, updated_at = p_at WHERE id = p_so;
    INSERT INTO seller_order_status_history (seller_order_id, from_status, to_status, changed_by, note, created_at)
    VALUES (p_so, old_status, p_to, p_actor, p_note, p_at);
    RETURN old_status;
END;
$$;

-- OrderLifecycleService.rollupSalesOrderStatus
CREATE FUNCTION pg_temp.rollup(p_order bigint, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    so            sales_order%ROWTYPE;
    sts           text[];
    all_terminal  boolean;
    any_completed boolean;
    new_status    text;
    paid          timestamptz;
    comp          timestamptz;
    canc          timestamptz;
BEGIN
    SELECT * INTO so FROM sales_order WHERE id = p_order FOR UPDATE;
    SELECT array_agg(DISTINCT status ORDER BY status) INTO sts FROM seller_order WHERE sales_order_id = p_order;
    all_terminal  := NOT EXISTS (SELECT 1 FROM unnest(sts) s WHERE s NOT IN ('COMPLETED', 'CANCELLED', 'REFUNDED'));
    any_completed := 'COMPLETED' = ANY (sts);
    paid := so.paid_at; comp := so.completed_at; canc := so.cancelled_at;

    IF sts = ARRAY['CANCELLED'] THEN
        new_status := 'CANCELLED'; canc := coalesce(canc, p_at);
    ELSIF sts = ARRAY['REFUNDED'] THEN
        new_status := 'REFUNDED';
    ELSIF all_terminal AND any_completed THEN
        new_status := 'COMPLETED'; comp := coalesce(comp, p_at);
    ELSIF any_completed THEN
        new_status := 'PARTIALLY_COMPLETED';
    ELSIF all_terminal THEN
        new_status := CASE WHEN 'CANCELLED' = ANY (sts) THEN 'CANCELLED' ELSE 'REFUNDED' END;
        IF new_status = 'CANCELLED' THEN canc := coalesce(canc, p_at); END IF;
    ELSIF sts = ARRAY['PENDING_PAYMENT'] THEN
        new_status := 'PENDING_PAYMENT';
    ELSE
        new_status := 'PAID'; paid := coalesce(paid, p_at);
    END IF;

    UPDATE sales_order
       SET status = new_status, paid_at = paid, completed_at = comp, cancelled_at = canc, updated_at = p_at
     WHERE id = p_order;
END;
$$;

-- CheckoutService.checkout: price each seller's part, reserve the oldest cards,
-- write the order, its sub-orders, lines and the exact cards behind each line.
CREATE FUNCTION pg_temp.place_order(p_okey text, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    od        seed_order_def%ROWTYPE;
    buyer     bigint;
    addr      address%ROWTYPE;
    s         record;
    l         record;
    opt       seller_shipping_option%ROWTYPE;
    subtotal  numeric;
    cnt       int;
    fee       numeric;
    q         record;
    order_no  text;
    o_id      bigint;
    so_id     bigint;
    oi_id     bigint;
    units     bigint[];
    items_sum numeric := 0;
    ship_sum  numeric := 0;
BEGIN
    SELECT * INTO od FROM seed_order_def WHERE okey = p_okey;
    buyer := pg_temp.uid(od.buyer);
    SELECT * INTO addr FROM address
     WHERE user_id = buyer AND deleted_at IS NULL AND is_default_shipping ORDER BY id LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: % has no default address', od.buyer;
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS seed_tmp_sub (
        seller text, sp bigint, subtotal numeric, fee numeric, option_id bigint,
        rate numeric, commission numeric, first_line int) ON COMMIT DROP;
    DELETE FROM seed_tmp_sub;

    FOR s IN SELECT d.seller, min(d.line_no) AS first_line FROM seed_line_def d
              WHERE d.okey = p_okey GROUP BY d.seller ORDER BY min(d.line_no) LOOP
        SELECT sum(li.price * d.qty), sum(d.qty), array_agg(li.catalog_variant_id)
          INTO subtotal, cnt, units
          FROM seed_line_def d JOIN listing li ON li.id = pg_temp.lid(d.lref)
         WHERE d.okey = p_okey AND d.seller = s.seller;

        SELECT o.* INTO opt
          FROM seller_shipping_option o
          LEFT JOIN seed_sub_def sd ON sd.okey = p_okey AND sd.seller = s.seller
         WHERE o.seller_profile_id = pg_temp.spid(s.seller) AND o.is_active
           AND (sd.option_name IS NULL OR o.name = sd.option_name)
         ORDER BY o.display_order, o.id LIMIT 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'seed: % has no usable shipping option for %', s.seller, p_okey;
        END IF;
        fee := CASE WHEN opt.free_threshold IS NOT NULL AND subtotal >= opt.free_threshold THEN 0
                    ELSE opt.base_fee + opt.per_item_fee * greatest(cnt - 1, 0) END;
        SELECT * INTO q FROM pg_temp.quote_commission(pg_temp.spid(s.seller), subtotal, units, p_at);
        INSERT INTO seed_tmp_sub VALUES (s.seller, pg_temp.spid(s.seller), subtotal, fee, opt.id, q.rate, q.fee, s.first_line);
        items_sum := items_sum + subtotal;
        ship_sum  := ship_sum + fee;
    END LOOP;

    order_no := 'PGS-' || to_char(p_at AT TIME ZONE 'UTC', 'YYYYMMDD') || 'Z-'
                || lpad(nextval('order_number_seq')::text, 6, '0');
    INSERT INTO sales_order (order_number, buyer_id, status, items_subtotal, shipping_total, discount_total, grand_total,
                             shipping_address_id, shipping_address_snapshot, buyer_note, idempotency_key,
                             placed_at, updated_at)
    VALUES (order_no, buyer, 'PENDING_PAYMENT', items_sum, ship_sum, 0, items_sum + ship_sum, addr.id,
            jsonb_build_object('id', addr.id, 'recipientName', addr.recipient_name, 'phone', addr.phone,
                               'line1', addr.line1, 'line2', addr.line2, 'subdistrict', addr.subdistrict,
                               'district', addr.district, 'province', addr.province,
                               'postalCode', addr.postal_code, 'countryCode', addr.country_code),
            od.note, 'seed-' || p_okey, p_at, p_at)
    RETURNING id INTO o_id;
    INSERT INTO seed_order_ref VALUES (p_okey, o_id);

    FOR s IN SELECT * FROM seed_tmp_sub ORDER BY first_line LOOP
        INSERT INTO seller_order (sales_order_id, seller_profile_id, seller_order_number, status, items_subtotal,
                                  shipping_fee, discount_amount, grand_total, commission_rate_percent,
                                  commission_amount, seller_net_amount, shipping_option_id, created_at, updated_at)
        VALUES (o_id, s.sp, order_no || '-S' || s.sp, 'PENDING_PAYMENT', s.subtotal, s.fee, 0, s.subtotal + s.fee,
                s.rate, s.commission, s.subtotal + s.fee - s.commission, s.option_id, p_at, p_at)
        RETURNING id INTO so_id;
        INSERT INTO seller_order_status_history (seller_order_id, from_status, to_status, changed_by, note, created_at)
        VALUES (so_id, NULL, 'PENDING_PAYMENT', buyer, 'Order placed', p_at);

        FOR l IN SELECT d.qty, li.*, p.name AS product_name, g.name AS game_name, cv.language_code, cv.finish,
                        cv.edition, cv.printing_note,
                        (SELECT image_key FROM catalog_image ci WHERE ci.catalog_product_id = p.id AND ci.is_primary) AS image_key
                   FROM seed_line_def d
                   JOIN listing li ON li.id = pg_temp.lid(d.lref)
                   JOIN catalog_variant cv ON cv.id = li.catalog_variant_id
                   JOIN catalog_product p ON p.id = cv.catalog_product_id
                   LEFT JOIN game g ON g.id = p.game_id
                  WHERE d.okey = p_okey AND d.seller = s.seller
                  ORDER BY d.line_no LOOP
            IF l.status <> 'ACTIVE' THEN
                RAISE EXCEPTION 'seed: listing % is % when % is placed', l.id, l.status, p_okey;
            END IF;
            units := ARRAY(SELECT id FROM listing_unit
                            WHERE listing_id = l.id AND status = 'LISTED' AND deleted_at IS NULL
                            ORDER BY acquired_at, id LIMIT l.qty);
            IF coalesce(array_length(units, 1), 0) < l.qty THEN
                RAISE EXCEPTION 'seed: listing % has fewer than % cards for %', l.id, l.qty, p_okey;
            END IF;
            UPDATE listing_unit SET status = 'RESERVED', updated_at = p_at WHERE id = ANY (units);

            INSERT INTO order_item (seller_order_id, listing_id, catalog_variant_id, quantity, unit_price, line_total,
                                    unit_cost_snapshot, product_name_snapshot, variant_label_snapshot,
                                    condition_snapshot, game_name_snapshot, image_key_snapshot, created_at)
            VALUES (so_id, l.id, l.catalog_variant_id, l.qty, l.price, l.price * l.qty,
                    coalesce((SELECT average_unit_cost FROM seller_variant_cost
                               WHERE seller_profile_id = l.seller_profile_id
                                 AND catalog_variant_id = l.catalog_variant_id
                                 AND condition_code = l.condition_code), 0),
                    l.product_name,
                    l.language_code
                      || CASE WHEN l.finish <> 'NOT_APPLICABLE' THEN ' / ' || l.finish ELSE '' END
                      || CASE WHEN l.edition <> 'NOT_APPLICABLE' THEN ' / ' || l.edition ELSE '' END
                      || CASE WHEN coalesce(l.printing_note, '') <> '' THEN ' / ' || l.printing_note ELSE '' END,
                    l.condition_code, l.game_name, l.image_key, p_at)
            RETURNING id INTO oi_id;
            INSERT INTO order_item_unit (order_item_id, listing_unit_id, created_at)
            SELECT oi_id, unnest(units), p_at;
        END LOOP;
    END LOOP;
END;
$$;

-- PaymentService.succeed
CREATE FUNCTION pg_temp.pay_succeed(p_payment bigint, p_actor bigint, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    pay      payment%ROWTYPE;
    platform bigint := pg_temp.platform_wallet();
    so       seller_order%ROWTYPE;
BEGIN
    UPDATE payment SET status = 'SUCCEEDED', paid_at = p_at, verified_at = p_at, verified_by = p_actor
     WHERE id = p_payment AND status IN ('PENDING', 'AWAITING_VERIFICATION')
    RETURNING * INTO pay;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: payment % is not payable', p_payment;
    END IF;
    PERFORM pg_temp.post(platform, 'AVAILABLE', 'PAYMENT_IN', 'CREDIT', pay.amount, 'PAYMENT', pay.id, p_at);
    FOR so IN SELECT * FROM seller_order WHERE sales_order_id = pay.sales_order_id AND status = 'PENDING_PAYMENT'
               ORDER BY id LOOP
        INSERT INTO escrow_hold (seller_order_id, wallet_id, amount, held_at)
        VALUES (so.id, platform, so.grand_total, p_at);
        PERFORM pg_temp.post(platform, 'AVAILABLE', 'ESCROW_HOLD', 'DEBIT', so.grand_total, 'SELLER_ORDER', so.id, p_at);
        PERFORM pg_temp.post(platform, 'PENDING', 'ESCROW_HOLD', 'CREDIT', so.grand_total, 'SELLER_ORDER', so.id, p_at);
    END LOOP;
    FOR so IN SELECT * FROM seller_order WHERE sales_order_id = pay.sales_order_id AND status = 'PENDING_PAYMENT'
               ORDER BY id LOOP
        PERFORM pg_temp.so_move(so.id, 'PAID', p_at, p_actor, 'Payment completed');
    END LOOP;
    PERFORM pg_temp.rollup(pay.sales_order_id, p_at);
END;
$$;

CREATE FUNCTION pg_temp.unpaid_total(p_order bigint) RETURNS numeric
    LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(grand_total), 0) FROM seller_order WHERE sales_order_id = p_order AND status = 'PENDING_PAYMENT'
$$;

CREATE FUNCTION pg_temp.pay_create(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE o_id bigint := pg_temp.oid_(a->>'okey');
BEGIN
    INSERT INTO payment (sales_order_id, method, status, amount, idempotency_key, created_at)
    VALUES (o_id, 'BANK_TRANSFER', 'PENDING', pg_temp.unpaid_total(o_id), 'seed-pay-' || (a->>'okey'), p_at);
END;
$$;

CREATE FUNCTION pg_temp.pay_slip(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE payment
       SET slip_image_key = 'payments/seed/' || (a->>'okey') || '.png',
           reference_code = a->>'ref',
           status = 'AWAITING_VERIFICATION'
     WHERE sales_order_id = pg_temp.oid_(a->>'okey') AND status IN ('PENDING', 'AWAITING_VERIFICATION');
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: no open payment for %', a->>'okey';
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.pay_verify(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE p_id bigint;
BEGIN
    SELECT id INTO p_id FROM payment
     WHERE sales_order_id = pg_temp.oid_(a->>'okey') AND status = 'AWAITING_VERIFICATION';
    IF p_id IS NULL THEN
        RAISE EXCEPTION 'seed: no slip to verify for %', a->>'okey';
    END IF;
    PERFORM pg_temp.pay_succeed(p_id, pg_temp.uid('admin'), p_at);
END;
$$;

CREATE FUNCTION pg_temp.pay_reject(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE payment SET status = 'FAILED', failure_reason = a->>'reason', verified_by = pg_temp.uid('admin'),
                       verified_at = p_at
     WHERE sales_order_id = pg_temp.oid_(a->>'okey') AND status = 'AWAITING_VERIFICATION';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: no slip to reject for %', a->>'okey';
    END IF;
END;
$$;

-- The sandbox gateway: paid the moment it is created, verified by nobody.
CREATE FUNCTION pg_temp.pay_mock(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    o_id bigint := pg_temp.oid_(a->>'okey');
    p_id bigint;
BEGIN
    INSERT INTO payment (sales_order_id, method, status, amount, idempotency_key, created_at)
    VALUES (o_id, 'MOCK_GATEWAY', 'PENDING', pg_temp.unpaid_total(o_id), 'mock:' || gen_random_uuid(), p_at)
    RETURNING id INTO p_id;
    PERFORM pg_temp.pay_succeed(p_id, NULL, p_at);
END;
$$;

CREATE FUNCTION pg_temp.accept(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE so_id bigint := pg_temp.soid(a->>'okey', a->>'seller');
BEGIN
    PERFORM pg_temp.so_move(so_id, 'PREPARING', p_at, pg_temp.uid(a->>'seller'), 'Seller accepted the order');
    UPDATE seller_order SET accepted_at = p_at WHERE id = so_id;
END;
$$;

-- OrderLifecycleService.shipSellerOrder: tracking typed by the seller, cards
-- leave the books at the running average, escrow gets its release date.
CREATE FUNCTION pg_temp.ship(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    so       seller_order%ROWTYPE;
    opt      seller_shipping_option%ROWTYPE;
    actor    bigint := pg_temp.uid(a->>'seller');
    tracking text;
    auto_at  timestamptz;
    oi       record;
    cost     numeric;
    n        int;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = pg_temp.soid(a->>'okey', a->>'seller');
    SELECT * INTO opt FROM seller_shipping_option WHERE id = so.shipping_option_id;
    tracking := CASE opt.carrier_code
                    WHEN 'THAILAND_POST' THEN 'EB' || lpad((so.id * 7919 % 1000000000)::text, 9, '0') || 'TH'
                    WHEN 'KERRY'         THEN 'KBKK' || lpad((so.id * 104729 % 10000000000)::text, 10, '0')
                    WHEN 'FLASH'         THEN 'TH' || lpad((so.id * 15485863 % 1000000000000)::text, 12, '0') || 'A'
                    ELSE lpad((so.id * 32452843 % 1000000000000)::text, 12, '0') END;
    auto_at := p_at + make_interval(days => pg_temp.setting_num('escrow.auto_release_days')::int);

    PERFORM pg_temp.so_move(so.id, 'SHIPPED', p_at, actor, 'Order shipped with tracking ' || tracking);
    UPDATE seller_order SET shipped_at = p_at, auto_complete_at = auto_at WHERE id = so.id;

    INSERT INTO shipment (seller_order_id, carrier_code, carrier_name, tracking_number, status, shipped_at,
                          estimated_delivery_date, proof_image_key, created_by, created_at, updated_at)
    VALUES (so.id, opt.carrier_code,
            CASE opt.carrier_code WHEN 'KERRY' THEN 'Kerry Express' WHEN 'FLASH' THEN 'Flash Express'
                                  WHEN 'THAILAND_POST' THEN 'ไปรษณีย์ไทย' WHEN 'JNT' THEN 'J&T Express'
                                  ELSE opt.name END,
            tracking, 'IN_TRANSIT', p_at, (p_at + make_interval(days => coalesce(opt.est_days_max, 3)))::date,
            CASE WHEN coalesce((a->>'proof')::boolean, true)
                 THEN 'shipments/seed/' || (a->>'okey') || '-' || (a->>'seller') || '.png' END,
            actor, p_at, p_at);

    FOR oi IN SELECT * FROM order_item WHERE seller_order_id = so.id ORDER BY id LOOP
        UPDATE listing_unit lu SET status = 'SOLD', sold_at = p_at, updated_at = p_at
          FROM order_item_unit oiu
         WHERE oiu.order_item_id = oi.id AND lu.id = oiu.listing_unit_id AND lu.status = 'RESERVED';
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n <> oi.quantity THEN
            RAISE EXCEPTION 'seed: order item % held % cards, expected %', oi.id, n, oi.quantity;
        END IF;
        cost := pg_temp.cost_issue(so.seller_profile_id, oi.catalog_variant_id, oi.condition_snapshot, oi.quantity, p_at);
        INSERT INTO inventory_movement (seller_profile_id, listing_id, listing_unit_id, catalog_variant_id,
                                        condition_code, movement_type, quantity_delta, unit_cost, reference_type,
                                        reference_id, created_by, created_at)
        SELECT so.seller_profile_id, lu.listing_id, lu.id, lu.catalog_variant_id, lu.condition_code, 'SALE', -1, cost,
               'ORDER_ITEM', oi.id, actor, p_at
          FROM order_item_unit oiu JOIN listing_unit lu ON lu.id = oiu.listing_unit_id
         WHERE oiu.order_item_id = oi.id
         ORDER BY lu.id;
    END LOOP;

    UPDATE escrow_hold SET release_due_at = auto_at WHERE seller_order_id = so.id;
    PERFORM pg_temp.rollup(so.sales_order_id, p_at);
END;
$$;

CREATE FUNCTION pg_temp.deliver(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE so seller_order%ROWTYPE;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = pg_temp.soid(a->>'okey', a->>'seller');
    PERFORM pg_temp.so_move(so.id, 'DELIVERED', p_at, NULL, 'Delivered by the carrier');
    UPDATE seller_order SET delivered_at = p_at WHERE id = so.id;
    UPDATE shipment SET status = 'DELIVERED', delivered_at = p_at, updated_at = p_at WHERE seller_order_id = so.id;
    PERFORM pg_temp.rollup(so.sales_order_id, p_at);
END;
$$;

-- EscrowReleaseService.release
CREATE FUNCTION pg_temp.escrow_release(p_so bigint, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    so         seller_order%ROWTYPE;
    hold       escrow_hold%ROWTYPE;
    rel        numeric;
    returned   numeric;
    base       numeric;
    commission numeric;
    net        numeric;
    seller_w   bigint;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = p_so;
    SELECT * INTO hold FROM escrow_hold WHERE seller_order_id = p_so FOR UPDATE;
    IF NOT FOUND OR hold.status <> 'HELD' THEN
        RETURN;
    END IF;
    rel := hold.amount - hold.refunded_amount;
    IF rel <= 0 THEN
        RETURN;
    END IF;
    SELECT coalesce(sum(oi.unit_price * ri.quantity), 0) INTO returned
      FROM return_item ri
      JOIN return_request rr ON rr.id = ri.return_request_id
      JOIN order_item oi ON oi.id = ri.order_item_id
      JOIN refund rf ON rf.return_request_id = rr.id
     WHERE rr.seller_order_id = p_so AND rr.received_at IS NOT NULL AND rf.status = 'PROCESSED';
    base := greatest(so.items_subtotal - returned, 0);
    commission := CASE WHEN so.items_subtotal = 0 THEN 0
                       ELSE round(so.commission_amount * base / so.items_subtotal, 2) END;
    commission := least(commission, rel);
    net := rel - commission;
    seller_w := pg_temp.ensure_seller_wallet(pg_temp.seller_user(so.seller_profile_id), p_at);

    PERFORM pg_temp.post(hold.wallet_id, 'PENDING', 'ESCROW_RELEASE', 'DEBIT', rel, 'SELLER_ORDER', p_so, p_at);
    IF commission > 0 THEN
        PERFORM pg_temp.post(hold.wallet_id, 'AVAILABLE', 'COMMISSION_FEE', 'CREDIT', commission, 'SELLER_ORDER', p_so, p_at);
    END IF;
    IF net > 0 THEN
        PERFORM pg_temp.post(seller_w, 'AVAILABLE', 'SELLER_EARNING', 'CREDIT', net, 'SELLER_ORDER', p_so, p_at);
    END IF;
    UPDATE escrow_hold
       SET released_amount = rel,
           status = CASE WHEN hold.refunded_amount > 0 THEN 'PARTIALLY_RELEASED' ELSE 'RELEASED' END,
           released_at = p_at
     WHERE id = hold.id;
    INSERT INTO commission_charge (seller_order_id, base_amount, rate_percent, fixed_fee, charged_amount, calculated_at)
    VALUES (p_so, base, so.commission_rate_percent, 0, commission, p_at);
END;
$$;

-- OrderRepository.grantPurchasedUnitsToCollection: one keepsake row per card,
-- leaving out cards that came back on a refunded return.
CREATE FUNCTION pg_temp.grant_collection(p_so bigint, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    buyer    bigint;
    returned bigint[] := ARRAY[]::bigint[];
    item     record;
BEGIN
    SELECT o.buyer_id INTO buyer FROM seller_order so JOIN sales_order o ON o.id = so.sales_order_id WHERE so.id = p_so;
    FOR item IN SELECT ri.order_item_id, ri.quantity
                  FROM return_item ri
                  JOIN return_request rr ON rr.id = ri.return_request_id
                  JOIN refund rf ON rf.return_request_id = rr.id
                 WHERE rr.seller_order_id = p_so AND rr.received_at IS NOT NULL AND rf.status = 'PROCESSED' LOOP
        returned := returned || ARRAY(SELECT listing_unit_id FROM order_item_unit
                                       WHERE order_item_id = item.order_item_id
                                       ORDER BY listing_unit_id LIMIT item.quantity);
    END LOOP;
    INSERT INTO collection_item (user_id, catalog_variant_id, condition_code, quantity, source, source_order_item_id,
                                 source_listing_unit_id, acquired_price, acquired_at, is_public, created_at, updated_at)
    SELECT buyer, oi.catalog_variant_id, coalesce(oi.condition_snapshot, 'NM'), 1, 'PURCHASE', oi.id,
           oiu.listing_unit_id, oi.unit_price, p_at, false, p_at, p_at
      FROM order_item_unit oiu
      JOIN order_item oi ON oi.id = oiu.order_item_id
     WHERE oi.seller_order_id = p_so
       AND NOT (oiu.listing_unit_id = ANY (returned))
       AND NOT EXISTS (SELECT 1 FROM collection_item ci
                        WHERE ci.source_order_item_id = oi.id AND ci.source_listing_unit_id = oiu.listing_unit_id)
     ORDER BY oi.id, oiu.listing_unit_id;
END;
$$;

-- confirmBuyerOrderReceived / autoCompleteSellerOrder
CREATE FUNCTION pg_temp.complete(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    so     seller_order%ROWTYPE;
    by_buyer boolean := coalesce((a->>'by_buyer')::boolean, true);
    buyer  bigint;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = pg_temp.soid(a->>'okey', a->>'seller');
    SELECT buyer_id INTO buyer FROM sales_order WHERE id = so.sales_order_id;
    IF NOT by_buyer AND (so.auto_complete_at IS NULL OR so.auto_complete_at > p_at) THEN
        RAISE EXCEPTION 'seed: % is not due for auto-completion at %', so.seller_order_number, p_at;
    END IF;
    PERFORM pg_temp.so_move(so.id, 'COMPLETED', p_at, CASE WHEN by_buyer THEN buyer END,
                            CASE WHEN by_buyer THEN 'Buyer confirmed receipt'
                                 ELSE 'Auto-completed by escrow release scheduler' END);
    UPDATE seller_order SET delivered_at = p_at, completed_at = p_at WHERE id = so.id;
    PERFORM pg_temp.escrow_release(so.id, p_at);
    PERFORM pg_temp.grant_collection(so.id, p_at);
    PERFORM pg_temp.rollup(so.sales_order_id, p_at);
END;
$$;

-- RefundService.processed
CREATE FUNCTION pg_temp.refund_processed(p_refund bigint, p_admin bigint, p_reference text, p_at timestamptz)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    rf    refund%ROWTYPE;
    hold  escrow_hold%ROWTYPE;
    total numeric;
    full_refund boolean;
    o_id  bigint;
BEGIN
    SELECT * INTO rf FROM refund WHERE id = p_refund FOR UPDATE;
    IF rf.status <> (CASE WHEN rf.method = 'BANK_TRANSFER' THEN 'PROCESSING' ELSE 'APPROVED' END) THEN
        RAISE EXCEPTION 'seed: refund % is %', p_refund, rf.status;
    END IF;
    SELECT * INTO hold FROM escrow_hold WHERE seller_order_id = rf.seller_order_id FOR UPDATE;
    IF hold.status <> 'HELD' OR hold.amount - hold.refunded_amount < rf.amount THEN
        RAISE EXCEPTION 'seed: refund % exceeds what escrow still holds', p_refund;
    END IF;
    PERFORM pg_temp.post(hold.wallet_id, 'PENDING', 'REFUND_OUT', 'DEBIT', rf.amount, 'REFUND', rf.id, p_at);
    total := hold.refunded_amount + rf.amount;
    full_refund := total = hold.amount;
    UPDATE escrow_hold
       SET refunded_amount = total,
           status = CASE WHEN rf.return_request_id IS NULL THEN 'CANCELLED' WHEN full_refund THEN 'REFUNDED' ELSE 'HELD' END
     WHERE id = hold.id;
    UPDATE refund_transfer_attempt
       SET status = 'PROCESSED', transfer_reference = p_reference, completed_by = p_admin, completed_at = p_at
     WHERE refund_id = rf.id AND status = 'PROCESSING';
    UPDATE refund SET status = 'PROCESSED', processed_by = p_admin, processed_at = p_at, transfer_reference = p_reference
     WHERE id = rf.id;

    IF rf.return_request_id IS NOT NULL THEN
        UPDATE return_request SET status = 'REFUNDED' WHERE id = rf.return_request_id;
        PERFORM pg_temp.so_move(rf.seller_order_id, CASE WHEN full_refund THEN 'REFUNDED' ELSE 'COMPLETED' END,
                                p_at, p_admin, 'Return refund transferred');
        SELECT sales_order_id INTO o_id FROM seller_order WHERE id = rf.seller_order_id;
        PERFORM pg_temp.rollup(o_id, p_at);
        IF NOT full_refund THEN
            PERFORM pg_temp.escrow_release(rf.seller_order_id, p_at);
        END IF;
        UPDATE return_request SET status = 'CLOSED', closed_at = p_at WHERE id = rf.return_request_id;
        IF NOT full_refund THEN
            PERFORM pg_temp.grant_collection(rf.seller_order_id, p_at);
        END IF;
    END IF;
END;
$$;

-- RefundService.onCancel
CREATE FUNCTION pg_temp.refund_on_cancel(p_so bigint, p_reason text, p_actor bigint, p_at timestamptz)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    so     seller_order%ROWTYPE;
    hold   escrow_hold%ROWTYPE;
    v_method text;
    r_id   bigint;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = p_so;
    SELECT * INTO hold FROM escrow_hold WHERE seller_order_id = p_so FOR UPDATE;
    IF NOT FOUND THEN
        IF pg_temp.unpaid_total(so.sales_order_id) = 0 THEN
            UPDATE payment SET status = CASE WHEN p_actor IS NULL THEN 'EXPIRED' ELSE 'CANCELLED' END
             WHERE sales_order_id = so.sales_order_id AND status = 'PENDING';
        END IF;
        RETURN;
    END IF;
    IF hold.status <> 'HELD' THEN
        RAISE EXCEPTION 'seed: escrow for % is %', p_so, hold.status;
    END IF;
    SELECT p.method INTO v_method FROM payment p WHERE p.sales_order_id = so.sales_order_id AND p.status = 'SUCCEEDED';
    INSERT INTO refund (sales_order_id, seller_order_id, amount, reason, status, method, idempotency_key, requested_at)
    VALUES (so.sales_order_id, p_so, hold.amount - hold.refunded_amount, p_reason, 'APPROVED', v_method,
            'cancel:' || p_so, p_at)
    RETURNING id INTO r_id;
    IF v_method = 'MOCK_GATEWAY' THEN
        PERFORM pg_temp.refund_processed(r_id, NULL, 'MOCK:' || r_id, p_at);
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.cancel_part(p_so bigint, p_at timestamptz, p_actor bigint, p_reason text, p_note text)
    RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_temp.so_move(p_so, 'CANCELLED', p_at, p_actor, p_note);
    UPDATE seller_order SET cancelled_at = p_at, cancel_reason = p_reason WHERE id = p_so;
    -- The cards go back on sale, if the listing is still there.
    UPDATE listing_unit lu SET status = 'LISTED', updated_at = p_at
      FROM order_item_unit oiu JOIN order_item oi ON oi.id = oiu.order_item_id
     WHERE oi.seller_order_id = p_so AND lu.id = oiu.listing_unit_id AND lu.status = 'RESERVED'
       AND EXISTS (SELECT 1 FROM listing l WHERE l.id = lu.listing_id AND l.deleted_at IS NULL);
    PERFORM pg_temp.refund_on_cancel(p_so, p_reason, p_actor, p_at);
END;
$$;

CREATE FUNCTION pg_temp.cancel_buyer(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    o_id  bigint := pg_temp.oid_(a->>'okey');
    buyer bigint;
    so    record;
BEGIN
    SELECT buyer_id INTO buyer FROM sales_order WHERE id = o_id;
    IF EXISTS (SELECT 1 FROM payment WHERE sales_order_id = o_id AND status = 'AWAITING_VERIFICATION') THEN
        RAISE EXCEPTION 'seed: % has a payment under review and cannot be cancelled', a->>'okey';
    END IF;
    FOR so IN SELECT id FROM seller_order WHERE sales_order_id = o_id
                AND status IN ('PENDING_PAYMENT', 'PAID', 'PREPARING') ORDER BY id LOOP
        PERFORM pg_temp.cancel_part(so.id, p_at, buyer, a->>'reason', a->>'reason');
    END LOOP;
    PERFORM pg_temp.rollup(o_id, p_at);
END;
$$;

CREATE FUNCTION pg_temp.cancel_seller(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE so_id bigint := pg_temp.soid(a->>'okey', a->>'seller');
BEGIN
    PERFORM pg_temp.cancel_part(so_id, p_at, pg_temp.uid(a->>'seller'), a->>'reason',
                                'Cancelled by seller: ' || (a->>'reason'));
    PERFORM pg_temp.rollup(pg_temp.oid_(a->>'okey'), p_at);
END;
$$;

-- PendingPaymentTimeoutScheduler -> expireUnpaidSellerOrder
CREATE FUNCTION pg_temp.expire(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    o_id bigint := pg_temp.oid_(a->>'okey');
    so   record;
BEGIN
    IF EXISTS (SELECT 1 FROM payment WHERE sales_order_id = o_id AND status IN ('AWAITING_VERIFICATION', 'SUCCEEDED')) THEN
        RAISE EXCEPTION 'seed: % has a payment, the sweep would not cancel it', a->>'okey';
    END IF;
    FOR so IN SELECT id FROM seller_order WHERE sales_order_id = o_id AND status = 'PENDING_PAYMENT' ORDER BY id LOOP
        PERFORM pg_temp.cancel_part(so.id, p_at, NULL,
                                    'Cancelled automatically: payment was not received in time',
                                    'Cancelled automatically: payment was not received in time');
    END LOOP;
    PERFORM pg_temp.rollup(o_id, p_at);
END;
$$;

CREATE FUNCTION pg_temp.refund_of(p_okey text, p_seller text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE r_id bigint;
BEGIN
    SELECT id INTO r_id FROM refund WHERE seller_order_id = pg_temp.soid(p_okey, p_seller) ORDER BY id DESC LIMIT 1;
    IF r_id IS NULL THEN
        RAISE EXCEPTION 'seed: no refund for % %', p_okey, p_seller;
    END IF;
    RETURN r_id;
END;
$$;

-- The buyer says where the money should go.
CREATE FUNCTION pg_temp.refund_dest(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE refund SET bank_name = a->>'bank', account_name = a->>'name', account_number = a->>'account'
     WHERE id = pg_temp.refund_of(a->>'okey', a->>'seller') AND status = 'APPROVED' AND method = 'BANK_TRANSFER';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: refund for % is not waiting for a destination', a->>'okey';
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.refund_start(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE rf refund%ROWTYPE;
BEGIN
    SELECT * INTO rf FROM refund WHERE id = pg_temp.refund_of(a->>'okey', a->>'seller');
    IF rf.status <> 'APPROVED' OR rf.account_number IS NULL THEN
        RAISE EXCEPTION 'seed: refund % cannot start a transfer', rf.id;
    END IF;
    INSERT INTO refund_transfer_attempt (refund_id, bank_name, account_name, account_number, status, started_by, started_at)
    VALUES (rf.id, rf.bank_name, rf.account_name, rf.account_number, 'PROCESSING', pg_temp.uid('admin'), p_at);
    UPDATE refund SET status = 'PROCESSING' WHERE id = rf.id;
END;
$$;

CREATE FUNCTION pg_temp.refund_fail(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE r_id bigint := pg_temp.refund_of(a->>'okey', a->>'seller');
BEGIN
    UPDATE refund_transfer_attempt
       SET status = 'FAILED', failure_reason = a->>'reason', completed_by = pg_temp.uid('admin'), completed_at = p_at
     WHERE refund_id = r_id AND status = 'PROCESSING';
    UPDATE refund SET status = 'APPROVED' WHERE id = r_id AND status = 'PROCESSING';
END;
$$;

CREATE FUNCTION pg_temp.refund_done(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_temp.refund_processed(pg_temp.refund_of(a->>'okey', a->>'seller'), pg_temp.uid('admin'),
                                     a->>'ref', p_at);
END;
$$;

-- ---------- returns (ReturnService) ----------

CREATE FUNCTION pg_temp.return_of(p_okey text, p_seller text) RETURNS bigint
    LANGUAGE plpgsql STABLE AS $$
DECLARE r_id bigint;
BEGIN
    SELECT id INTO r_id FROM return_request WHERE seller_order_id = pg_temp.soid(p_okey, p_seller) ORDER BY id DESC LIMIT 1;
    IF r_id IS NULL THEN
        RAISE EXCEPTION 'seed: no return for % %', p_okey, p_seller;
    END IF;
    RETURN r_id;
END;
$$;

CREATE FUNCTION pg_temp.refund_on_return(p_so bigint, p_return bigint, p_reason text, p_amount numeric, p_at timestamptz)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    so     seller_order%ROWTYPE;
    hold   escrow_hold%ROWTYPE;
    v_method text;
    r_id   bigint;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = p_so;
    SELECT p.method INTO v_method FROM payment p WHERE p.sales_order_id = so.sales_order_id AND p.status = 'SUCCEEDED';
    SELECT * INTO hold FROM escrow_hold WHERE seller_order_id = p_so;
    IF hold.amount - hold.refunded_amount < p_amount THEN
        RAISE EXCEPTION 'seed: return refund exceeds escrow for %', p_so;
    END IF;
    INSERT INTO refund (sales_order_id, seller_order_id, return_request_id, amount, reason, status, method,
                        idempotency_key, requested_at)
    VALUES (so.sales_order_id, p_so, p_return, p_amount, p_reason, 'APPROVED', v_method, 'return:' || p_return, p_at)
    RETURNING id INTO r_id;
    IF v_method = 'MOCK_GATEWAY' THEN
        PERFORM pg_temp.refund_processed(r_id, NULL, 'MOCK:' || r_id, p_at);
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.return_request(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    so    seller_order%ROWTYPE;
    buyer bigint;
    rr_id bigint;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = pg_temp.soid(a->>'okey', a->>'seller');
    SELECT buyer_id INTO buyer FROM sales_order WHERE id = so.sales_order_id;
    IF so.status NOT IN ('SHIPPED', 'DELIVERED') OR so.auto_complete_at IS NULL OR so.auto_complete_at <= p_at THEN
        RAISE EXCEPTION 'seed: return window closed for %', so.seller_order_number;
    END IF;
    INSERT INTO return_request (seller_order_id, buyer_id, reason_code, description, evidence_keys, requested_at)
    VALUES (so.id, buyer, a->>'reason', a->>'description', coalesce(a->'evidence', '[]'::jsonb), p_at)
    RETURNING id INTO rr_id;
    -- Every line of the parcel unless the scenario names a part of one.
    INSERT INTO return_item (return_request_id, order_item_id, quantity)
    SELECT rr_id, oi.id, coalesce((a->>'qty')::int, oi.quantity)
      FROM order_item oi
     WHERE oi.seller_order_id = so.id
       AND (a->>'sku' IS NULL OR oi.catalog_variant_id = pg_temp.vid(a->>'sku'));
    PERFORM pg_temp.so_move(so.id, 'RETURN_REQUESTED', p_at, buyer, 'Return requested');
    PERFORM pg_temp.rollup(so.sales_order_id, p_at);
END;
$$;

CREATE FUNCTION pg_temp.return_approve(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    rr        return_request%ROWTYPE;
    so        seller_order%ROWTYPE;
    v_amount  numeric;
    ship_back boolean := coalesce((a->>'ship_back')::boolean, true);
BEGIN
    SELECT * INTO rr FROM return_request WHERE id = pg_temp.return_of(a->>'okey', a->>'seller') FOR UPDATE;
    SELECT * INTO so FROM seller_order WHERE id = rr.seller_order_id;
    v_amount := coalesce((a->>'amount')::numeric, so.grand_total);
    IF rr.status <> 'REQUESTED'
       OR (a->>'resolution' = 'FULL_REFUND' AND v_amount <> so.grand_total)
       OR (a->>'resolution' = 'PARTIAL_REFUND' AND v_amount >= so.grand_total) THEN
        RAISE EXCEPTION 'seed: cannot approve return % as % %', rr.id, a->>'resolution', v_amount;
    END IF;
    UPDATE return_request
       SET status = CASE WHEN ship_back THEN 'AWAITING_RETURN_SHIPMENT' ELSE 'APPROVED' END,
           resolution = a->>'resolution', refund_amount = v_amount,
           reviewed_by = pg_temp.uid(a->>'seller'), reviewed_at = p_at
     WHERE id = rr.id;
    IF NOT ship_back THEN
        PERFORM pg_temp.refund_on_return(so.id, rr.id, 'Return approved', v_amount, p_at);
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.return_reject(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    rr return_request%ROWTYPE;
    so seller_order%ROWTYPE;
BEGIN
    SELECT * INTO rr FROM return_request WHERE id = pg_temp.return_of(a->>'okey', a->>'seller') FOR UPDATE;
    SELECT * INTO so FROM seller_order WHERE id = rr.seller_order_id;
    UPDATE return_request
       SET status = 'REJECTED', resolution = 'REJECTED', reviewed_by = pg_temp.uid(a->>'seller'),
           reviewed_at = p_at, closed_at = p_at
     WHERE id = rr.id;
    PERFORM pg_temp.so_move(so.id, CASE WHEN so.delivered_at IS NULL THEN 'SHIPPED' ELSE 'DELIVERED' END,
                            p_at, pg_temp.uid(a->>'seller'), 'Return rejected');
    PERFORM pg_temp.rollup(so.sales_order_id, p_at);
END;
$$;

CREATE FUNCTION pg_temp.return_ship(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE return_request SET status = 'IN_TRANSIT', return_tracking_number = a->>'tracking'
     WHERE id = pg_temp.return_of(a->>'okey', a->>'seller') AND status = 'AWAITING_RETURN_SHIPMENT';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: return for % is not waiting to be posted', a->>'okey';
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.return_receive(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    rr      return_request%ROWTYPE;
    so      seller_order%ROWTYPE;
    v_restock boolean := coalesce((a->>'restock')::boolean, true);
    actor   bigint := pg_temp.uid(a->>'seller');
    item    record;
    u       listing_unit%ROWTYPE;
    cost    numeric;
BEGIN
    SELECT * INTO rr FROM return_request WHERE id = pg_temp.return_of(a->>'okey', a->>'seller') FOR UPDATE;
    SELECT * INTO so FROM seller_order WHERE id = rr.seller_order_id;
    IF rr.status <> 'IN_TRANSIT' THEN
        RAISE EXCEPTION 'seed: return % is %, not IN_TRANSIT', rr.id, rr.status;
    END IF;
    FOR item IN SELECT * FROM return_item WHERE return_request_id = rr.id ORDER BY id LOOP
        UPDATE return_item SET restock = v_restock WHERE id = item.id;
        IF v_restock THEN
            FOR u IN SELECT lu.* FROM order_item_unit oiu JOIN listing_unit lu ON lu.id = oiu.listing_unit_id
                      WHERE oiu.order_item_id = item.order_item_id AND lu.status = 'SOLD'
                      ORDER BY lu.id LIMIT item.quantity LOOP
                -- Booked back in at the cost it left at, so the average does not move.
                SELECT m.unit_cost INTO cost FROM inventory_movement m
                 WHERE m.listing_unit_id = u.id AND m.movement_type = 'SALE' ORDER BY m.id DESC LIMIT 1;
                cost := coalesce(cost, u.acquisition_cost, 0);
                UPDATE listing_unit SET status = 'RETURNED', listing_id = NULL, updated_at = p_at WHERE id = u.id;
                PERFORM pg_temp.cost_receive(u.seller_profile_id, u.catalog_variant_id, u.condition_code, 1, cost, p_at);
                INSERT INTO inventory_movement (seller_profile_id, listing_id, listing_unit_id, catalog_variant_id,
                                                condition_code, movement_type, quantity_delta, unit_cost,
                                                reference_type, reference_id, created_by, created_at)
                VALUES (u.seller_profile_id, u.listing_id, u.id, u.catalog_variant_id, u.condition_code,
                        'RETURN_RESTOCK', 1, cost, 'RETURN_ITEM', item.id, actor, p_at);
            END LOOP;
        END IF;
    END LOOP;
    UPDATE return_request SET status = 'RECEIVED', received_at = p_at WHERE id = rr.id;
    PERFORM pg_temp.so_move(so.id, 'RETURNED', p_at, actor, 'Returned items received');
    PERFORM pg_temp.rollup(so.sales_order_id, p_at);
    PERFORM pg_temp.refund_on_return(so.id, rr.id, 'Returned items received', rr.refund_amount, p_at);
END;
$$;

-- ---------- reviews ----------

CREATE FUNCTION pg_temp.review(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE so seller_order%ROWTYPE;
BEGIN
    SELECT * INTO so FROM seller_order WHERE id = pg_temp.soid(a->>'okey', a->>'seller');
    INSERT INTO seller_review (seller_order_id, seller_profile_id, reviewer_id, rating, title, comment,
                               reply_comment, replied_at, is_visible, created_at, updated_at)
    SELECT so.id, so.seller_profile_id, o.buyer_id, (a->>'rating')::smallint, a->>'title', a->>'comment',
           a->>'reply', CASE WHEN a->>'reply' IS NOT NULL THEN p_at + interval '5 hours' END,
           coalesce((a->>'visible')::boolean, true), p_at,
           CASE WHEN a->>'reply' IS NOT NULL THEN p_at + interval '5 hours' ELSE p_at END
      FROM sales_order o WHERE o.id = so.sales_order_id;
END;
$$;

-- ---------- payouts (PayoutService) ----------

CREATE FUNCTION pg_temp.payout_request(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    sp        bigint := pg_temp.spid(a->>'seller');
    w         wallet%ROWTYPE;
    reserved  numeric;
    available numeric;
    v_amount  numeric;
    acct      bigint;
    p_id      bigint;
BEGIN
    SELECT * INTO w FROM wallet WHERE user_id = pg_temp.seller_user(sp);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'seed: % has no wallet yet', a->>'seller';
    END IF;
    SELECT coalesce(sum(p.amount), 0) INTO reserved FROM payout p
     WHERE p.seller_profile_id = sp AND p.status IN ('REQUESTED', 'APPROVED', 'PROCESSING');
    available := w.balance_available - reserved;
    v_amount := coalesce((a->>'amount')::numeric, floor(available * (a->>'share')::numeric / 100 / 100) * 100);
    IF v_amount < pg_temp.setting_num('payout.minimum_amount') OR v_amount > available THEN
        RAISE EXCEPTION 'seed: % cannot request a payout of % (withdrawable %)', a->>'seller', v_amount, available;
    END IF;
    SELECT id INTO acct FROM seller_payout_account WHERE seller_profile_id = sp;
    INSERT INTO payout (seller_profile_id, wallet_id, payout_account_id, amount, status, requested_at)
    VALUES (sp, w.id, acct, v_amount, 'REQUESTED', p_at)
    RETURNING id INTO p_id;
    INSERT INTO seed_payout_ref VALUES (a->>'pkey', p_id);
END;
$$;

CREATE FUNCTION pg_temp.payout_move(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    p      payout%ROWTYPE;
    target text := a->>'to';
    linked numeric := 0;
    so     record;
BEGIN
    SELECT * INTO p FROM payout WHERE id = (SELECT payout_id FROM seed_payout_ref WHERE pkey = a->>'pkey') FOR UPDATE;
    IF NOT ((p.status = 'REQUESTED' AND target IN ('APPROVED', 'REJECTED'))
         OR (p.status = 'APPROVED' AND target = 'PROCESSING')
         OR (p.status = 'PROCESSING' AND target IN ('PAID', 'FAILED'))) THEN
        RAISE EXCEPTION 'seed: payout % cannot move % -> %', a->>'pkey', p.status, target;
    END IF;
    IF target = 'PAID' THEN
        PERFORM pg_temp.post(p.wallet_id, 'AVAILABLE', 'PAYOUT_OUT', 'DEBIT', p.amount, 'PAYOUT', p.id, p_at);
    END IF;
    UPDATE payout
       SET status = target,
           approved_by = CASE WHEN target = 'APPROVED' THEN pg_temp.uid('admin') ELSE approved_by END,
           approved_at = CASE WHEN target = 'APPROVED' THEN p_at ELSE approved_at END,
           paid_at = CASE WHEN target = 'PAID' THEN p_at ELSE paid_at END,
           transfer_reference = CASE WHEN target = 'PAID' THEN a->>'ref' ELSE transfer_reference END,
           slip_image_key = CASE WHEN target = 'PAID' THEN 'payouts/seed/' || (a->>'pkey') || '.png' ELSE slip_image_key END,
           rejection_reason = CASE WHEN target IN ('REJECTED', 'FAILED') THEN a->>'reason' ELSE rejection_reason END
     WHERE id = p.id;
    IF target = 'PAID' THEN
        -- PayoutRepository.linkPaidOrders: oldest completed sub-orders that fit in the amount.
        FOR so IN SELECT id, seller_net_amount FROM seller_order
                   WHERE seller_profile_id = p.seller_profile_id AND status = 'COMPLETED' AND payout_id IS NULL
                   ORDER BY completed_at ASC NULLS LAST, id LOOP
            EXIT WHEN linked + so.seller_net_amount > p.amount;
            linked := linked + so.seller_net_amount;
            UPDATE seller_order SET payout_id = p.id WHERE id = so.id;
        END LOOP;
    END IF;
END;
$$;

-- An admin correction: always a pair of ADJUSTMENT rows across two wallets.
CREATE FUNCTION pg_temp.adjust(a jsonb, p_at timestamptz) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE seller_w bigint := pg_temp.ensure_seller_wallet(pg_temp.uid(a->>'seller'), p_at);
BEGIN
    PERFORM pg_temp.post(pg_temp.platform_wallet(), 'AVAILABLE', 'ADJUSTMENT', 'DEBIT', (a->>'amount')::numeric,
                         'ADJUSTMENT', (a->>'n')::bigint, p_at, a->>'memo');
    PERFORM pg_temp.post(seller_w, 'AVAILABLE', 'ADJUSTMENT', 'CREDIT', (a->>'amount')::numeric,
                         'ADJUSTMENT', (a->>'n')::bigint, p_at, a->>'memo');
END;
$$;

-- ---------- the player ----------

CREATE FUNCTION pg_temp.ev(p_days numeric, p_hours numeric, p_kind text, p_args jsonb) RETURNS void
    LANGUAGE sql AS $$
    INSERT INTO seed_event (at, kind, a) VALUES (pg_temp.ago(p_days, p_hours), p_kind, p_args)
$$;

CREATE FUNCTION pg_temp.run_events() RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    e      seed_event%ROWTYPE;
    anchor timestamptz := (SELECT anchor FROM seed_clock);
BEGIN
    FOR e IN SELECT * FROM seed_event ORDER BY at, id LOOP
        IF e.at > anchor THEN
            RAISE EXCEPTION 'seed: event % (%) is in the future: %', e.id, e.kind, e.at;
        END IF;
        BEGIN
            CASE e.kind
                WHEN 'listing'        THEN PERFORM pg_temp.create_listing(e.a, e.at);
                WHEN 'price'          THEN PERFORM pg_temp.price_change(e.a, e.at);
                WHEN 'listing_status' THEN PERFORM pg_temp.listing_status(e.a, e.at);
                WHEN 'restock'        THEN PERFORM pg_temp.restock(e.a, e.at);
                WHEN 'stock_hand'     THEN PERFORM pg_temp.stock_hand(e.a, e.at);
                WHEN 'write_off'      THEN PERFORM pg_temp.write_off(e.a, e.at);
                WHEN 'place'          THEN PERFORM pg_temp.place_order(e.a->>'okey', e.at);
                WHEN 'pay_create'     THEN PERFORM pg_temp.pay_create(e.a, e.at);
                WHEN 'pay_slip'       THEN PERFORM pg_temp.pay_slip(e.a, e.at);
                WHEN 'pay_verify'     THEN PERFORM pg_temp.pay_verify(e.a, e.at);
                WHEN 'pay_reject'     THEN PERFORM pg_temp.pay_reject(e.a, e.at);
                WHEN 'pay_mock'       THEN PERFORM pg_temp.pay_mock(e.a, e.at);
                WHEN 'accept'         THEN PERFORM pg_temp.accept(e.a, e.at);
                WHEN 'ship'           THEN PERFORM pg_temp.ship(e.a, e.at);
                WHEN 'deliver'        THEN PERFORM pg_temp.deliver(e.a, e.at);
                WHEN 'complete'       THEN PERFORM pg_temp.complete(e.a, e.at);
                WHEN 'cancel_buyer'   THEN PERFORM pg_temp.cancel_buyer(e.a, e.at);
                WHEN 'cancel_seller'  THEN PERFORM pg_temp.cancel_seller(e.a, e.at);
                WHEN 'expire'         THEN PERFORM pg_temp.expire(e.a, e.at);
                WHEN 'refund_dest'    THEN PERFORM pg_temp.refund_dest(e.a, e.at);
                WHEN 'refund_start'   THEN PERFORM pg_temp.refund_start(e.a, e.at);
                WHEN 'refund_fail'    THEN PERFORM pg_temp.refund_fail(e.a, e.at);
                WHEN 'refund_done'    THEN PERFORM pg_temp.refund_done(e.a, e.at);
                WHEN 'return_request' THEN PERFORM pg_temp.return_request(e.a, e.at);
                WHEN 'return_approve' THEN PERFORM pg_temp.return_approve(e.a, e.at);
                WHEN 'return_reject'  THEN PERFORM pg_temp.return_reject(e.a, e.at);
                WHEN 'return_ship'    THEN PERFORM pg_temp.return_ship(e.a, e.at);
                WHEN 'return_receive' THEN PERFORM pg_temp.return_receive(e.a, e.at);
                WHEN 'review'         THEN PERFORM pg_temp.review(e.a, e.at);
                WHEN 'payout_request' THEN PERFORM pg_temp.payout_request(e.a, e.at);
                WHEN 'payout'         THEN PERFORM pg_temp.payout_move(e.a, e.at);
                WHEN 'adjust'         THEN PERFORM pg_temp.adjust(e.a, e.at);
                ELSE RAISE EXCEPTION 'seed: unknown event kind %', e.kind;
            END CASE;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'seed event #% % % at % failed: %', e.id, e.kind, e.a, e.at, SQLERRM;
        END;
    END LOOP;
END;
$$;
