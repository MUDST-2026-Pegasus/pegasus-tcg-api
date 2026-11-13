-- The replay: every event from 05*, 06* (and the bigger data set's 05b/06b) in time order.

-- ---------- play it all ----------
SELECT pg_temp.run_events();

-- The admin's findings on banned_trader: listings already blocked (05), now the
-- seller is suspended. Done after the replay because a listing can only be
-- created while its seller is VERIFIED.
UPDATE seller_profile
   SET status = 'SUSPENDED',
       suspended_reason = 'ขายการ์ดปลอมตามรายงานของผู้ซื้อ (คำสั่งคืนสินค้าเหตุ COUNTERFEIT)',
       updated_at = pg_temp.ago(20, 2)
 WHERE id = pg_temp.spid('banned_trader');
