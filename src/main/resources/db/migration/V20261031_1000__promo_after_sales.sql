-- After-sales for discount codes [TCG-570].

-- The shop's own code part of each card's discount. The rest of discount_allocated
-- is Pegasus's. A return takes the shop part off the commission base and the
-- Pegasus part off the subsidy, so both must be known per card. 0 on older rows.
ALTER TABLE order_item
    ADD COLUMN seller_discount_allocated decimal(14,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_item_seller_discount_allocated CHECK (
        seller_discount_allocated >= 0 AND seller_discount_allocated <= discount_allocated);

-- Pegasus pays a shop back for a Pegasus code when the money is released
-- (PROMO_SUBSIDY), out of its own available balance. That balance is commission
-- earned so far; when promotions cost more than it, the difference is Pegasus's
-- own money and the platform balance may go below zero. Shops' wallets never can.
ALTER TABLE wallet DROP CONSTRAINT ck_wallet_non_negative;
ALTER TABLE wallet ADD CONSTRAINT ck_wallet_non_negative CHECK (
    balance_pending >= 0 AND (balance_available >= 0 OR kind = 'PLATFORM'));
