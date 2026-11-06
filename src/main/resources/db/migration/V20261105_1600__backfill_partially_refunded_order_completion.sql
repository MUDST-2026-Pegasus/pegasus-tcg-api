-- Partial returns may finish as COMPLETED after the refund is processed.
-- Older transitions did not set completed_at, so those sales were omitted
-- from the seller's 30-day stock forecast.
UPDATE seller_order so
   SET completed_at = history.completed_at
  FROM (
    SELECT seller_order_id, MAX(created_at) AS completed_at
      FROM seller_order_status_history
     WHERE to_status = 'COMPLETED'
     GROUP BY seller_order_id
  ) history
 WHERE so.id = history.seller_order_id
   AND so.status = 'COMPLETED'
   AND so.completed_at IS NULL;
