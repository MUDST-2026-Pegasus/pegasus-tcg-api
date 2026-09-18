ALTER TABLE seller_verification ALTER COLUMN bank_book_image_key DROP NOT NULL;
UPDATE seller_verification SET bank_book_image_key = NULL WHERE bank_book_image_key = '';
