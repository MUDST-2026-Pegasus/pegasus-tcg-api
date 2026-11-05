-- Checkout at sale prices [TCG-680].

-- What a line holds against its sale until the order is paid: cards counted in
-- price_sale_listing.sold_count (flash quota) and baht counted in
-- price_sale.subsidy_reserved (Pegasus budget). An order cancelled or timed out
-- before payment gives both back and zeroes them, so they are given back once.
-- After payment they stay as they are: the cards and the money are spent.
ALTER TABLE order_item
    ADD COLUMN sale_quota_held   int           NOT NULL DEFAULT 0,
    ADD COLUMN sale_subsidy_held decimal(14,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_item_sale_held CHECK (
        sale_quota_held >= 0 AND sale_quota_held <= quantity
        AND sale_subsidy_held >= 0
        AND (sale_id IS NOT NULL OR (sale_quota_held = 0 AND sale_subsidy_held = 0)));

-- The discount of a Pegasus campaign on this part of the order. Pegasus pays it
-- to the shop at release as SALE_SUBSIDY, so the shop's net carries it the way
-- it carries a Pegasus code's PROMO_SUBSIDY. A shop's own sale is not in it: the
-- shop simply sold for less.
ALTER TABLE seller_order
    ADD COLUMN sale_subsidy_amount decimal(14,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_seller_order_sale_subsidy CHECK (sale_subsidy_amount >= 0);
ALTER TABLE seller_order DROP CONSTRAINT ck_seller_order_commission;
ALTER TABLE seller_order ADD CONSTRAINT ck_seller_order_commission CHECK (
    commission_amount >= 0
    AND seller_net_amount = grand_total + platform_discount_amount + sale_subsidy_amount - commission_amount);

-- A checkout whose subsidy did not fit the budget ends the campaign as
-- BUDGET_EXHAUSTED even though subsidy_reserved stays below budget_cap. Raising
-- the budget clears it.
ALTER TABLE price_sale ADD COLUMN budget_exhausted_at timestamptz;
ALTER TABLE price_sale ADD CONSTRAINT ck_price_sale_budget_exhausted CHECK (
    budget_exhausted_at IS NULL OR budget_cap IS NOT NULL);

COMMENT ON COLUMN price_sale.budget_exhausted_at IS
    'Set when a checkout could not fit its subsidy into the budget; the campaign then reads BUDGET_EXHAUSTED.';

