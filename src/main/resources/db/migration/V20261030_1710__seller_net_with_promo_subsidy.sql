-- A Pegasus code comes off what the buyer pays but not off what the shop earns:
-- Pegasus pays it back at release as PROMO_SUBSIDY [TCG-566]. So the shop's net
-- is the buyer's total plus that subsidy, less commission. With no Pegasus
-- discount (every order so far) this is the rule it replaces.
ALTER TABLE seller_order DROP CONSTRAINT ck_seller_order_commission;
ALTER TABLE seller_order ADD CONSTRAINT ck_seller_order_commission CHECK (
    commission_amount >= 0
    AND seller_net_amount = grand_total + platform_discount_amount - commission_amount);
