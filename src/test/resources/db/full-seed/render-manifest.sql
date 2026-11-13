-- Every picture the seed draws itself (render/render.py), one JSON object per line:
--   {"key": object key in the bucket, "op": what to draw, "src": key of the picture it starts from, ...}
-- Run after the seed has committed. Keys already in MinIO are skipped by the renderer, so the
-- committed SAMPLE documents in images/ win over drawn ones.
\pset tuples_only on
\pset format unaligned

-- Views of the official scan shown on product pages: art close-up, angled showcase, box detail.
SELECT json_build_object('key', ci.image_key, 'op', split_part(ci.image_key, '/', 3),
                         'src', (SELECT p.image_key FROM catalog_image p
                                  WHERE p.catalog_product_id = ci.catalog_product_id AND p.is_primary),
                         'name', cp.name)
  FROM catalog_image ci JOIN catalog_product cp ON cp.id = ci.catalog_product_id
 WHERE ci.image_key LIKE 'seed/gen/%';

-- The shops' own photos. The file name is the stem of the scan they were taken from.
SELECT json_build_object('key', k.image_key, 'op', split_part(k.image_key, '/', 3), 'src', s.image_key,
                         'name', k.name, 'number', k.card_number, 'set', k.set_name, 'year', k.year,
                         'grader', k.grading_company, 'grade', k.grade_value, 'cert', k.cert)
  FROM (SELECT DISTINCT ON (li.image_key) li.image_key, cp.name, cp.card_number, cs.name AS set_name,
               extract(year FROM cs.release_date)::int AS year, l.grading_company, l.grade_value,
               (SELECT u.cert_number FROM listing_unit u WHERE u.listing_id = l.id AND u.cert_number IS NOT NULL LIMIT 1) AS cert,
               regexp_replace(li.image_key, '^.*/|--[a-z]+-[0-9_]+\.jpg$|\.jpg$', '', 'g') AS stem
          FROM listing_image li
          JOIN listing l ON l.id = li.listing_id
          JOIN catalog_variant cv ON cv.id = l.catalog_variant_id
          JOIN catalog_product cp ON cp.id = cv.catalog_product_id
          LEFT JOIN card_set cs ON cs.id = cp.card_set_id
         WHERE li.image_key LIKE 'seed/gen/%'
         ORDER BY li.image_key, l.id) k
  JOIN LATERAL (SELECT ci.image_key FROM catalog_image ci
                 WHERE ci.image_key NOT LIKE 'seed/gen/%'
                   AND regexp_replace(ci.image_key, '^.*/|\.[A-Za-z0-9]+$', '', 'g') = k.stem
                 LIMIT 1) s ON true;

-- Bank-transfer slips.
SELECT json_build_object('key', p.slip_image_key, 'op', 'slip', 'amount', p.amount, 'ref', p.reference_code,
                         'payer', coalesce(o.shipping_address_snapshot->>'recipientName', u.display_name),
                         'order', o.order_number, 'at', to_char(p.created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI'),
                         'bank', (ARRAY['KBANK', 'SCB', 'BBL', 'KTB', 'BAY', 'TTB', 'GSB'])[1 + abs(hashtext(u.username)) % 7],
                         'acct', lpad((abs(hashtext(u.username || 'acct')) % 10000)::text, 4, '0'))
  FROM payment p JOIN sales_order o ON o.id = p.sales_order_id JOIN user_account u ON u.id = o.buyer_id
 WHERE p.slip_image_key IS NOT NULL;

-- Parcel receipts.
SELECT json_build_object('key', sh.proof_image_key, 'op', 'parcel', 'carrier', sh.carrier_name, 'tracking', sh.tracking_number,
                         'sender', su.display_name, 'recipient', o.shipping_address_snapshot->>'recipientName',
                         'province', o.shipping_address_snapshot->>'province')
  FROM shipment sh
  JOIN seller_order so ON so.id = sh.seller_order_id
  JOIN sales_order o ON o.id = so.sales_order_id
  JOIN seller_profile sp ON sp.id = so.seller_profile_id
  JOIN user_account su ON su.id = sp.user_id
 WHERE sh.proof_image_key IS NOT NULL;

-- Payout transfer slips.
SELECT json_build_object('key', po.slip_image_key, 'op', 'payout', 'amount', po.amount, 'ref', po.transfer_reference,
                         'name', a.account_name, 'bank', a.bank_name, 'acct', a.account_number)
  FROM payout po JOIN seller_payout_account a ON a.id = po.payout_account_id
 WHERE po.slip_image_key IS NOT NULL;

-- Return evidence: the card that came back, with what is wrong circled.
SELECT json_build_object('key', e.key, 'op', 'evidence', 'n', e.n, 'of', jsonb_array_length(rr.evidence_keys),
                         'reason', rr.reason_code,
                         'src', (SELECT ci.image_key FROM catalog_image ci
                                  WHERE ci.catalog_product_id = cv.catalog_product_id AND ci.is_primary))
  FROM return_request rr
  JOIN LATERAL jsonb_array_elements_text(rr.evidence_keys) WITH ORDINALITY AS e(key, n) ON true
  JOIN LATERAL (SELECT oi.catalog_variant_id FROM order_item oi WHERE oi.seller_order_id = rr.seller_order_id
                 ORDER BY oi.line_total DESC LIMIT 1) i ON true
  JOIN catalog_variant cv ON cv.id = i.catalog_variant_id;

-- Bank books sent with seller verification.
SELECT json_build_object('key', v.bank_book_image_key, 'op', 'bankbook', 'bank', v.bank_name, 'code', v.bank_code,
                         'name', v.legal_first_name || ' ' || v.legal_last_name, 'acct', v.bank_account_number)
  FROM seller_verification v WHERE v.bank_book_image_key IS NOT NULL;

-- Profile pictures.
SELECT json_build_object('key', u.avatar_url, 'op', 'avatar', 'name', u.display_name, 'seed', u.username)
  FROM user_account u WHERE u.avatar_url LIKE 'avatars/seed/%';
