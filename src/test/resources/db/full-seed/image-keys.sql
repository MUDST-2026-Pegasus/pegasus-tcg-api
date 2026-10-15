-- Every MinIO object key the seeded rows point at, one per line. The seed
-- scripts compare this list with the bucket to prove nothing is missing.
-- Logos are stored as full URLs under :minio_base, so the base is stripped.
SELECT DISTINCT k FROM (
    SELECT substr(logo_url, length(:'minio_base') + 2) AS k FROM game WHERE logo_url LIKE :'minio_base' || '/%'
    UNION SELECT substr(logo_url, length(:'minio_base') + 2) FROM card_set WHERE logo_url LIKE :'minio_base' || '/%'
    UNION SELECT image_key FROM catalog_category
    UNION SELECT image_key FROM catalog_image
    UNION SELECT image_url FROM catalog_variant
    UNION SELECT image_key FROM listing_image
    UNION SELECT avatar_url FROM user_account
    UNION SELECT bank_book_image_key FROM seller_verification
    UNION SELECT slip_image_key FROM payment
    UNION SELECT slip_image_key FROM payout
    UNION SELECT proof_image_key FROM shipment
    UNION SELECT jsonb_array_elements_text(evidence_keys) FROM return_request
    UNION SELECT image_key FROM collection_item
    UNION SELECT image_key_snapshot FROM order_item
    UNION SELECT image_key FROM home_banner
) keys
WHERE k IS NOT NULL AND k <> ''
ORDER BY k;
