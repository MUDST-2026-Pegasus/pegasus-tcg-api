-- Demo seed: the people. One admin, two shops, two buyers and one shop waiting for
-- approval, so every role has something to try.
--
-- Every account signs in with Password123! (the same hash as the full seed).
-- Usernames, bank details and addresses match the full seed's accounts of the
-- same name, so the avatars and bank-book pictures in full-seed/images/ fit them.

CREATE TEMP TABLE seed_user_def ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('admin',         'admin@example.com',     'Pegasus Admin',    'ดูแลระบบ Pegasus TCG',                                     '0890000001', 130, 0.1),
    ('demo_seller',   'seller@example.com',    'Demo Seller Shop', 'ร้านตัวอย่าง ขายการ์ด Pokemon และ One Piece ส่งไวทุกวัน',      '0812345604', 120, 0.2),
    ('tcg_corner',    'tcgcorner@example.com', 'TCG Corner',       'ร้านเชี่ยวชาญ One Piece และ Pokemon',                       '0812345602', 118, 0.5),
    ('demo_buyer',    'buyer@example.com',     'Demo Buyer',       NULL,                                                        '0834445555', 110, 0.3),
    ('collector_mai', 'mai@example.com',       'Mai Collector',    'สะสมการ์ดลายภาพพิเศษของโปเกมอน',                             '0811112222', 105, 0.6),
    ('newbie_shop',   'newbie@example.com',    'Newbie Shop',      'เพิ่งเปิดร้าน รอยืนยันตัวตน',                                  '0812345608', 3,   1)
) AS v(username, email, display_name, bio, phone, created_days, last_login_days);

INSERT INTO user_account (email, password_hash, username, display_name, bio, phone, phone_public, avatar_url,
                          status, failed_login_attempts, last_login_at, created_at, updated_at, email_verified_at)
SELECT d.email,
       '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU',
       d.username, d.display_name, d.bio, d.phone, false, 'avatars/seed/' || d.username || '.png',
       'ACTIVE', 0, pg_temp.ago(d.last_login_days),
       pg_temp.ago(d.created_days), pg_temp.ago(least(d.created_days, d.last_login_days)), pg_temp.ago(d.created_days)
  FROM seed_user_def d
 ORDER BY d.created_days DESC;

-- Everyone can buy; the admin hands out the other roles.
INSERT INTO user_role (user_id, role_id, granted_at, granted_by)
SELECT u.id, r.id, u.created_at, CASE WHEN r.code = 'BUYER' THEN NULL ELSE pg_temp.uid('admin') END
  FROM (VALUES
        ('admin', 'BUYER'), ('admin', 'ADMIN'),
        ('demo_seller', 'BUYER'), ('demo_seller', 'SELLER'),
        ('tcg_corner', 'BUYER'), ('tcg_corner', 'SELLER'),
        ('demo_buyer', 'BUYER'),
        ('collector_mai', 'BUYER'),
        ('newbie_shop', 'BUYER'), ('newbie_shop', 'SELLER')
       ) AS v(username, role_code)
  JOIN user_account u ON u.username = v.username
  JOIN app_role r ON r.code = v.role_code;

-- ---------- address book ----------
INSERT INTO address (user_id, label, recipient_name, phone, line1, line2, subdistrict, district, province,
                     postal_code, is_default_shipping, is_default_billing, created_at, updated_at)
SELECT pg_temp.uid(v.username), v.label, v.recipient, v.phone, v.line1, v.line2, v.subdistrict, v.district,
       v.province, v.postal, true, true, pg_temp.ago(v.days), pg_temp.ago(v.days)
  FROM (VALUES
        ('admin',         'สำนักงาน', 'ทีมงาน Pegasus',   '0890000001', '159 อาคารเพกาซัส',       'ถนนรัชดาภิเษก', 'ดินแดง',      'ดินแดง',        'กรุงเทพมหานคร', '10400', 129),
        ('demo_seller',   'ร้าน',     'เดโม เซลเลอร์',    '0812345604', '10 ถนนแจ้งวัฒนะ',        NULL,            'ทุ่งสองห้อง', 'หลักสี่',       'กรุงเทพมหานคร', '10210', 119),
        ('tcg_corner',    'ร้าน',     'ทีซีจี คอร์เนอร์', '0812345602', '89 ถนนสุขุมวิท',         NULL,            'บางจาก',      'พระโขนง',       'กรุงเทพมหานคร', '10260', 117),
        ('demo_buyer',    'บ้าน',     'เดโม ผู้ซื้อ',      '0834445555', '99/9 หมู่บ้านพฤกษา',      'ถนนรามอินทรา',  'คันนายาว',    'คันนายาว',      'กรุงเทพมหานคร', '10230', 109),
        ('collector_mai', 'บ้าน',     'ใหม่ ศรีสุข',      '0811112222', '88/12 ซอยสุขุมวิท 55',   'คอนโด The Base ชั้น 18', 'คลองตันเหนือ', 'วัฒนา', 'กรุงเทพมหานคร', '10110', 104),
        ('newbie_shop',   'ร้าน',     'น้องใหม่ ขายดี',   '0812345608', '12 ถนนนิมมานเหมินท์',    'ซอย 9',         'สุเทพ',       'เมืองเชียงใหม่', 'เชียงใหม่',     '50200', 3)
       ) AS v(username, label, recipient, phone, line1, line2, subdistrict, district, province, postal, days);

-- ---------- shops ----------
-- auto_accept_orders is off, so a new paid order waits for the seller to press "accept".
INSERT INTO seller_profile (user_id, status, verified_at, handling_days, vacation_mode, auto_accept_orders,
                            created_at, updated_at)
SELECT pg_temp.uid(v.username), v.status,
       CASE WHEN v.verified_days IS NOT NULL THEN pg_temp.ago(v.verified_days) END,
       2, false, false, pg_temp.ago(v.created_days), pg_temp.ago(coalesce(v.verified_days, v.created_days))
  FROM (VALUES
        ('demo_seller', 'VERIFIED', 115::numeric, 119),
        ('tcg_corner',  'VERIFIED', 113,          117),
        ('newbie_shop', 'PENDING',  NULL,         2)
       ) AS v(username, status, verified_days, created_days);

-- Verification asks for a legal name and a bank account with a photo of the bank book
-- (a SAMPLE picture). newbie_shop's application is waiting in the admin queue.
INSERT INTO seller_verification (seller_profile_id, status, submitted_at, reviewed_by, reviewed_at,
                                 legal_first_name, legal_last_name, bank_code, bank_name, bank_account_number,
                                 bank_book_image_key)
SELECT pg_temp.spid(v.username), v.status, pg_temp.ago(v.submitted_days),
       CASE WHEN v.reviewed_days IS NOT NULL THEN pg_temp.uid('admin') END,
       CASE WHEN v.reviewed_days IS NOT NULL THEN pg_temp.ago(v.reviewed_days) END,
       v.first_name, v.last_name, v.bank_code, v.bank_name, v.account,
       'verifications/seed/' || v.username || '-bankbook.png'
  FROM (VALUES
        ('demo_seller', 'APPROVED',  117, 115::numeric, 'เดโม',     'เซลเลอร์', 'BAY',   'ธนาคารกรุงศรีอยุธยา',  '3334445556'),
        ('tcg_corner',  'APPROVED',  115, 113,          'ธนวัฒน์',   'มีสุข',    'BBL',   'ธนาคารกรุงเทพ',        '2213456789'),
        ('newbie_shop', 'SUBMITTED', 1,   NULL,         'น้องใหม่',  'ขายดี',    'CIMBT', 'ธนาคารซีไอเอ็มบี ไทย', '8001234567')
       ) AS v(username, status, submitted_days, reviewed_days, first_name, last_name, bank_code, bank_name, account);

INSERT INTO seller_payout_account (seller_profile_id, verification_id, bank_code, bank_name, account_name,
                                   account_number, verified_at, created_at)
SELECT v.seller_profile_id, v.id, v.bank_code, v.bank_name, v.legal_first_name || ' ' || v.legal_last_name,
       v.bank_account_number, v.reviewed_at, v.reviewed_at
  FROM seller_verification v
 WHERE v.status = 'APPROVED';

-- Sellers ship and price shipping themselves; the lowest display_order is the default.
INSERT INTO seller_shipping_option (seller_profile_id, name, carrier_code, base_fee, per_item_fee, free_threshold,
                                    est_days_min, est_days_max, is_active, display_order)
SELECT pg_temp.spid(v.username), v.name, v.carrier, v.base_fee, v.per_item, v.free_threshold,
       v.dmin::smallint, v.dmax::smallint, true, v.disp::smallint
  FROM (VALUES
        ('demo_seller', 'ไปรษณีย์ไทย EMS', 'THAILAND_POST', 50.00, 5.00, 2000.00::numeric, 1, 3, 0),
        ('demo_seller', 'Kerry Express',  'KERRY',         60.00, 5.00, NULL,             1, 2, 1),
        ('tcg_corner',  'Flash Express',  'FLASH',         40.00, 5.00, 2000.00,          1, 3, 0),
        ('tcg_corner',  'ไปรษณีย์ไทย EMS', 'THAILAND_POST', 45.00, 5.00, NULL,             2, 4, 1)
       ) AS v(username, name, carrier, base_fee, per_item, free_threshold, dmin, dmax, disp);
