-- Run with psql against the same database used by Cypress:
--   psql -X -v ON_ERROR_STOP=1 -f scripts/db/verify-financial-ledger.sql
-- Any mismatch raises an exception and returns a non-zero exit code.
DO $$
DECLARE
    bad bigint;
BEGIN
    -- Cached wallet balances must equal the signed sum of every posted ledger row.
    SELECT count(*) INTO bad
      FROM wallet w
      LEFT JOIN (
          SELECT wallet_id,
                 sum(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS balance
            FROM ledger_entry
           GROUP BY wallet_id
      ) l ON l.wallet_id = w.id
     WHERE w.balance_available + w.balance_pending <> coalesce(l.balance, 0);
    IF bad > 0 THEN
        RAISE EXCEPTION 'ledger check: % wallet balance(s) differ from signed ledger total', bad;
    END IF;

    -- Each immutable balance_after snapshot must match that wallet's running ledger balance.
    SELECT count(*) INTO bad
      FROM (
          SELECT balance_after,
                 sum(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
                   OVER (PARTITION BY wallet_id ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS expected
            FROM ledger_entry
      ) ledger_rows
     WHERE balance_after <> expected;
    IF bad > 0 THEN
        RAISE EXCEPTION 'ledger check: % ledger balance_after snapshot(s) are wrong', bad;
    END IF;

    -- For each released seller order, escrow cash plus the Pegasus subsidy is exactly
    -- commission plus seller earnings. Includes released holds with partial refunds.
    SELECT count(*) INTO bad
      FROM (
          SELECT so.id,
                 coalesce(sum(le.amount) FILTER (WHERE le.entry_type = 'ESCROW_RELEASE' AND le.direction = 'DEBIT'), 0) AS released,
                 coalesce(sum(le.amount) FILTER (WHERE le.entry_type = 'PROMO_SUBSIDY' AND le.direction = 'DEBIT'), 0) AS subsidy,
                 coalesce(sum(le.amount) FILTER (WHERE le.entry_type = 'COMMISSION_FEE' AND le.direction = 'CREDIT'), 0) AS commission,
                 coalesce(sum(le.amount) FILTER (WHERE le.entry_type = 'SELLER_EARNING' AND le.direction = 'CREDIT'), 0) AS earning
            FROM seller_order so
            JOIN escrow_hold eh ON eh.seller_order_id = so.id
            LEFT JOIN ledger_entry le ON le.reference_type = 'SELLER_ORDER' AND le.reference_id = so.id
           WHERE eh.released_amount > 0
           GROUP BY so.id
      ) released_orders
     WHERE released + subsidy <> commission + earning;
    IF bad > 0 THEN
        RAISE EXCEPTION 'ledger check: % released seller order(s) fail release + subsidy = commission + seller earning', bad;
    END IF;

    -- Coupon counters must reflect claims and redemptions that actually committed.
    SELECT count(*) INTO bad
      FROM promotion p
     WHERE p.claimed_count <> (SELECT count(*) FROM promotion_claim c WHERE c.promotion_id = p.id)
        OR p.used_count <> (SELECT count(*) FROM promotion_redemption r
                             WHERE r.promotion_id = p.id AND r.status IN ('RESERVED', 'CONSUMED'));
    IF bad > 0 THEN
        RAISE EXCEPTION 'ledger check: % promotion claimed/used counter(s) differ from their rows', bad;
    END IF;
END;
$$;

SELECT 'Financial ledger and promotion counters are consistent.' AS result;
