-- People: staff, sellers in every onboarding state, buyers, their addresses,
-- bank details and shipping rates.
--
-- Every account signs in with Password123! (the hash below). Usernames and emails
-- used by the test-case sheet and FINANCE_DEMO.md are kept as they were.

CREATE TEMP TABLE seed_user_def ON COMMIT DROP AS
SELECT * FROM (VALUES
    -- staff
    ('admin',            'admin@example.com',         'Pegasus Admin',      'ดูแลระบบ Pegasus TCG',                                  '0890000001', false, 'ACTIVE',      420, 0.1,  0, NULL::numeric, NULL::numeric, true),
    ('support_staff',    'support@example.com',       'Pegasus Support',    'ทีมช่วยเหลือลูกค้า ตอบทุกวัน 10:00-19:00',               '0890000002', false, 'ACTIVE',      410, 0.4,  0, NULL, NULL, true),
    -- sellers
    ('pegasus_official', 'store@pegasus.example.com', 'Pegasus Official',   'ร้านทางการของ Pegasus ตรวจสภาพและแพ็กทุกชิ้นโดยทีมงาน', '0890000010', true,  'ACTIVE',      400, 0.2,  0, NULL, NULL, true),
    ('card_kingdom_th',  'cardkingdom@example.com',   'Card Kingdom TH',    'การ์ดซิงเกิลและสินค้าซีลจากกรุงเทพฯ ส่งไวภายในวันถัดไป',   '0812345601', true,  'ACTIVE',      320, 0.5,  0, NULL, NULL, true),
    ('tcg_corner',       'tcgcorner@example.com',     'TCG Corner',         'ร้านเชี่ยวชาญ One Piece และ Pokemon',                    '0812345602', false, 'ACTIVE',      260, 1,    0, NULL, NULL, true),
    ('mint_vault',       'mintvault@example.com',     'Mint Vault',         'การ์ดเกรด PSA / BGS / CGC และซิงเกิลระดับไฮเอนด์',        '0812345603', true,  'ACTIVE',      250, 2,    0, NULL, NULL, true),
    ('demo_seller',      'seller@example.com',        'Demo Seller Shop',   'ร้านตัวอย่างสำหรับเดโมฝั่งผู้ขาย',                          '0812345604', false, 'ACTIVE',      210, 3,    0, NULL, NULL, true),
    ('grand_line_cards', 'grandline@example.com',     'Grand Line Cards',   'One Piece, Digimon, Union Arena และ Dragon Ball',        '0812345605', true,  'ACTIVE',      170, 0.3,  0, NULL, NULL, true),
    ('kanto_cards',      'kanto@example.com',         'Kanto Cards',        'ร้านปิดชั่วคราว พักร้อนถึงสิ้นเดือน',                         '0812345606', false, 'ACTIVE',      160, 9,    0, NULL, NULL, true),
    ('banned_trader',    'banned@example.com',        'Banned Trader',      NULL,                                                     '0812345607', false, 'ACTIVE',      150, 21,   0, NULL, NULL, true),
    ('newbie_shop',      'newbie@example.com',        'Newbie Shop',        'เพิ่งเปิดร้าน รอยืนยันตัวตน',                               '0812345608', false, 'ACTIVE',      6,   1,    0, NULL, NULL, true),
    ('pack_rippers',     'packrippers@example.com',   'Pack Rippers',       'เปิดกล่องสดทุกสัปดาห์',                                    '0812345609', false, 'ACTIVE',      12,  2,    0, NULL, NULL, true),
    ('shady_cards',      'shady@example.com',         'Shady Cards',        NULL,                                                     '0812345610', false, 'ACTIVE',      30,  10,   0, NULL, NULL, false),
    -- buyers
    ('collector_mai',    'mai@example.com',           'Mai Collector',      'สะสมการ์ดลายภาพพิเศษของโปเกมอน',                          '0811112222', false, 'ACTIVE',      300, 0.05, 0, NULL, NULL, true),
    ('deckbuilder_ton',  'ton@example.com',           'Ton Deckbuilder',    'เล่น One Piece กับ Magic ทุกสุดสัปดาห์',                    '0823334444', false, 'ACTIVE',      280, 0.6,  0, NULL, NULL, true),
    ('demo_buyer',       'buyer@example.com',         'Demo Buyer',         NULL,                                                     '0834445555', false, 'ACTIVE',      220, 1.5,  0, NULL, NULL, true),
    ('ploy_collects',    'ploy@example.com',          'Ploy Collects',      'ชอบ Eevee ทุกร่าง',                                       '0845556666', true,  'ACTIVE',      190, 0.2,  0, NULL, NULL, true),
    ('nattapong_tcg',    'nattapong@example.com',     'Nattapong',          NULL,                                                     '0856667777', false, 'ACTIVE',      140, 4,    0, NULL, NULL, false),
    ('locked_user',      'locked@example.com',        'Locked User',        'ใส่รหัสผิดเกินกำหนด บัญชีถูกล็อกชั่วคราว',                    '0867778888', false, 'ACTIVE',      90,  15,   5, -0.25, NULL, false),
    ('suspended_user',   'suspended@example.com',     'Suspended User',     NULL,                                                     '0878889999', false, 'SUSPENDED',   80,  25,   0, NULL, NULL, false),
    ('deactivated_user', 'deactivated@example.com',   'Deactivated User',   NULL,                                                     '0889990000', false, 'DEACTIVATED', 70,  40,   0, NULL, 35, false)
) AS v(username, email, display_name, bio, phone, phone_public, status, created_days, last_login_days,
       failed_logins, locked_until_hours_ago, deleted_days, has_avatar);

-- Password123! — the same hash the older seed files use.
INSERT INTO user_account (email, password_hash, username, display_name, bio, phone, phone_public, avatar_url,
                          status, failed_login_attempts, last_login_at, locked_until,
                          created_at, updated_at, deleted_at)
SELECT d.email,
       '$argon2id$v=19$m=16384,t=2,p=1$1lZHYXd3IHzu+vWVqCFhaA$DkeuKft0NlGgWZA2peJ+vNfV5j6qKrh52t7ZhShsRwU',
       d.username, d.display_name, d.bio, d.phone, d.phone_public,
       CASE WHEN d.has_avatar THEN 'avatars/seed/' || d.username || '.png' END,
       d.status::user_status, d.failed_logins::smallint, pg_temp.ago(d.last_login_days),
       CASE WHEN d.locked_until_hours_ago IS NOT NULL THEN pg_temp.ago(0, d.locked_until_hours_ago) END,
       pg_temp.ago(d.created_days), pg_temp.ago(least(d.created_days, d.last_login_days)),
       CASE WHEN d.deleted_days IS NOT NULL THEN pg_temp.ago(d.deleted_days) END
  FROM seed_user_def d
 ORDER BY d.created_days DESC;

-- Everyone buys; staff and sellers get their extra roles from the admin.
INSERT INTO user_role (user_id, role_id, granted_at, granted_by)
SELECT u.id, r.id, u.created_at, CASE WHEN r.code = 'BUYER' THEN NULL ELSE pg_temp.uid('admin') END
  FROM (VALUES
        ('admin', 'BUYER'), ('admin', 'ADMIN'),
        ('support_staff', 'SUPPORT'),
        ('pegasus_official', 'BUYER'), ('pegasus_official', 'SELLER'),
        ('card_kingdom_th', 'BUYER'), ('card_kingdom_th', 'SELLER'),
        ('tcg_corner', 'BUYER'), ('tcg_corner', 'SELLER'),
        ('mint_vault', 'BUYER'), ('mint_vault', 'SELLER'),
        ('demo_seller', 'BUYER'), ('demo_seller', 'SELLER'),
        ('grand_line_cards', 'BUYER'), ('grand_line_cards', 'SELLER'),
        ('kanto_cards', 'BUYER'), ('kanto_cards', 'SELLER'),
        ('banned_trader', 'BUYER'), ('banned_trader', 'SELLER'),
        ('newbie_shop', 'BUYER'), ('pack_rippers', 'BUYER'), ('shady_cards', 'BUYER'),
        ('collector_mai', 'BUYER'), ('deckbuilder_ton', 'BUYER'), ('demo_buyer', 'BUYER'),
        ('ploy_collects', 'BUYER'), ('nattapong_tcg', 'BUYER'), ('locked_user', 'BUYER'),
        ('suspended_user', 'BUYER'), ('deactivated_user', 'BUYER')
       ) AS v(username, role_code)
  JOIN user_account u ON u.username = v.username
  JOIN app_role r ON r.code = v.role_code;

-- ---------- address book ----------
-- Every account that can check out has a default address. One buyer also has an
-- older address they removed (soft delete: orders may still point at it).
INSERT INTO address (user_id, label, recipient_name, phone, line1, line2, subdistrict, district, province,
                     postal_code, is_default_shipping, is_default_billing, created_at, updated_at, deleted_at)
SELECT pg_temp.uid(v.username), v.label, v.recipient, v.phone, v.line1, v.line2, v.subdistrict, v.district,
       v.province, v.postal, v.def_ship, v.def_bill, pg_temp.ago(v.days), pg_temp.ago(v.days),
       CASE WHEN v.deleted_days IS NOT NULL THEN pg_temp.ago(v.deleted_days) END
  FROM (VALUES
        ('collector_mai',   'บ้าน',      'ใหม่ ศรีสุข',       '0811112222', '88/12 ซอยสุขุมวิท 55',        'คอนโด The Base ชั้น 18', 'คลองตันเหนือ', 'วัฒนา',          'กรุงเทพมหานคร', '10110', true,  true,  290, NULL::numeric),
        ('collector_mai',   'ที่ทำงาน',  'ใหม่ ศรีสุข',       '0811112222', '1 อาคารเอ็มไพร์ทาวเวอร์',     'ถนนสาทรใต้',             'ยานนาวา',      'สาทร',           'กรุงเทพมหานคร', '10120', false, false, 200, NULL),
        ('collector_mai',   'หอพักเก่า', 'ใหม่ ศรีสุข',       '0811112222', '45 ซอยงามวงศ์วาน 2',          NULL,                     'บางเขน',       'เมืองนนทบุรี',    'นนทบุรี',       '11000', false, false, 299, 150),
        ('deckbuilder_ton', 'บ้าน',      'ต้น วงศ์ษา',        '0823334444', '12 ถนนนิมมานเหมินท์',          'ซอย 9',                  'สุเทพ',        'เมืองเชียงใหม่',  'เชียงใหม่',     '50200', true,  true,  275, NULL),
        ('demo_buyer',      'บ้าน',      'เดโม ผู้ซื้อ',       '0834445555', '99/9 หมู่บ้านพฤกษา',           'ถนนรามอินทรา',           'คันนายาว',     'คันนายาว',       'กรุงเทพมหานคร', '10230', true,  true,  215, NULL),
        ('ploy_collects',   'คอนโด',     'พลอย ใจงาม',        '0845556666', '300 ถนนมิตรภาพ',               'ห้อง 1205',              'ในเมือง',      'เมืองขอนแก่น',    'ขอนแก่น',       '40000', true,  true,  185, NULL),
        ('nattapong_tcg',   'บ้าน',      'ณัฐพงศ์ ทองดี',     '0856667777', '7 ถนนเทพกระษัตรี',            NULL,                     'รัษฎา',        'เมืองภูเก็ต',     'ภูเก็ต',        '83000', true,  true,  135, NULL),
        ('locked_user',     'บ้าน',      'ล็อก ผู้ใช้',        '0867778888', '5 ถนนพหลโยธิน',               NULL,                     'สามเสนใน',     'พญาไท',          'กรุงเทพมหานคร', '10400', true,  true,  85,  NULL),
        ('suspended_user',  'บ้าน',      'ระงับ ผู้ใช้',       '0878889999', '18 ถนนเพชรเกษม',              NULL,                     'บางหว้า',      'ภาษีเจริญ',       'กรุงเทพมหานคร', '10160', true,  true,  78,  NULL),
        ('admin',           'สำนักงาน',  'ทีมงาน Pegasus',    '0890000001', '159 อาคารเพกาซัส',            'ถนนรัชดาภิเษก',          'ดินแดง',       'ดินแดง',         'กรุงเทพมหานคร', '10400', true,  true,  410, NULL),
        ('pegasus_official','คลังสินค้า','Pegasus Official',  '0890000010', '159 อาคารเพกาซัส ชั้น 2',     'ถนนรัชดาภิเษก',          'ดินแดง',       'ดินแดง',         'กรุงเทพมหานคร', '10400', true,  true,  395, NULL),
        ('card_kingdom_th', 'ร้าน',      'การ์ด คิงดอม',      '0812345601', '22 ถนนพระราม 9',              'ห้อง B12 ชั้น 3',        'ห้วยขวาง',     'ห้วยขวาง',       'กรุงเทพมหานคร', '10310', true,  true,  315, NULL),
        ('tcg_corner',      'ร้าน',      'ทีซีจี คอร์เนอร์',  '0812345602', '89 ถนนสุขุมวิท',              NULL,                     'บางจาก',       'พระโขนง',        'กรุงเทพมหานคร', '10260', true,  true,  255, NULL),
        ('mint_vault',      'ร้าน',      'มินต์ วอลต์',       '0812345603', '501 ถนนเพชรบุรีตัดใหม่',      NULL,                     'มักกะสัน',     'ราชเทวี',        'กรุงเทพมหานคร', '10400', true,  true,  245, NULL),
        ('demo_seller',     'ร้าน',      'เดโม เซลเลอร์',     '0812345604', '10 ถนนแจ้งวัฒนะ',             NULL,                     'ทุ่งสองห้อง',  'หลักสี่',        'กรุงเทพมหานคร', '10210', true,  true,  205, NULL),
        ('grand_line_cards','ร้าน',      'แกรนด์ไลน์ การ์ด',  '0812345605', '77 ถนนศรีนครินทร์',           NULL,                     'หนองบอน',      'ประเวศ',         'กรุงเทพมหานคร', '10250', true,  true,  165, NULL),
        ('kanto_cards',     'ร้าน',      'คันโต การ์ด',       '0812345606', '3 ถนนมิตรภาพ',                NULL,                     'ในเมือง',      'เมืองนครราชสีมา', 'นครราชสีมา',    '30000', true,  true,  155, NULL)
       ) AS v(username, label, recipient, phone, line1, line2, subdistrict, district, province, postal,
              def_ship, def_bill, days, deleted_days);

-- ---------- seller onboarding ----------
INSERT INTO seller_profile (user_id, status, verified_at, suspended_reason, handling_days, vacation_mode,
                            auto_accept_orders, created_at, updated_at)
SELECT pg_temp.uid(v.username), v.status,
       CASE WHEN v.verified_days IS NOT NULL THEN pg_temp.ago(v.verified_days) END,
       v.suspended_reason, v.handling::smallint, v.vacation, v.auto_accept,
       pg_temp.ago(v.created_days), pg_temp.ago(coalesce(v.verified_days, v.created_days))
  FROM (VALUES
        -- every listing is inserted while its seller is VERIFIED; banned_trader is
        -- suspended at the end of 06_orders.sql, after its listings exist.
        ('pegasus_official', 'VERIFIED', 390,          NULL::text, 1, false, true,  398),
        ('card_kingdom_th',  'VERIFIED', 290,          NULL,       2, false, true,  305),
        ('tcg_corner',       'VERIFIED', 250,          NULL,       2, false, false, 258),
        ('mint_vault',       'VERIFIED', 240,          NULL,       3, false, false, 248),
        ('demo_seller',      'VERIFIED', 200,          NULL,       2, false, true,  208),
        ('grand_line_cards', 'VERIFIED', 160,          NULL,       2, false, true,  168),
        ('kanto_cards',      'VERIFIED', 150,          NULL,       2, true,  true,  158),
        ('banned_trader',    'VERIFIED', 140,          NULL,       4, false, true,  148),
        ('newbie_shop',      'PENDING',  NULL::numeric, NULL,      2, false, true,  2),
        ('pack_rippers',     'PENDING',  NULL,         NULL,       2, false, true,  4),
        ('shady_cards',      'REJECTED', NULL,         NULL,       2, false, true,  14),
        ('deckbuilder_ton',  'NOT_APPLIED', NULL,      NULL,       2, false, true,  60)
       ) AS v(username, status, verified_days, suspended_reason, handling, vacation, auto_accept, created_days);

-- Seller verification asks only for a name and a bank account, with a photo of
-- the bank book (a generated SAMPLE image, not a real document).
INSERT INTO seller_verification (seller_profile_id, status, submitted_at, reviewed_by, reviewed_at, rejection_reason,
                                 legal_first_name, legal_last_name, bank_code, bank_name, bank_account_number,
                                 bank_book_image_key)
SELECT pg_temp.spid(v.username), v.status, pg_temp.ago(v.submitted_days),
       CASE WHEN v.reviewed_days IS NOT NULL THEN pg_temp.uid('admin') END,
       CASE WHEN v.reviewed_days IS NOT NULL THEN pg_temp.ago(v.reviewed_days) END,
       v.reason, v.first_name, v.last_name, v.bank_code, v.bank_name, v.account,
       'verifications/seed/' || v.username || '-bankbook' || v.suffix || '.png'
  FROM (VALUES
        ('pegasus_official', 'APPROVED',     392, 390,           NULL::text,                                          'บริษัท เพกาซัส ทีซีจี', 'จำกัด',     'KBANK', 'ธนาคารกสิกรไทย',          '1012345678', ''),
        ('card_kingdom_th',  'REJECTED',     300, 298,           'รูปสมุดบัญชีไม่ชัด อ่านเลขบัญชีไม่ได้ กรุณาถ่ายใหม่',      'กิตติพงษ์',            'ศรีสวัสดิ์', 'SCB',   'ธนาคารไทยพาณิชย์',        '4021234567', '-old'),
        ('card_kingdom_th',  'APPROVED',     292, 290,           NULL,                                                'กิตติพงษ์',            'ศรีสวัสดิ์', 'SCB',   'ธนาคารไทยพาณิชย์',        '4021234568', ''),
        ('tcg_corner',       'APPROVED',     252, 250,           NULL,                                                'ธนวัฒน์',              'มีสุข',      'BBL',   'ธนาคารกรุงเทพ',           '2213456789', ''),
        ('mint_vault',       'APPROVED',     243, 240,           NULL,                                                'มินตรา',               'วงศ์ทอง',    'KTB',   'ธนาคารกรุงไทย',           '9871234560', ''),
        ('demo_seller',      'APPROVED',     202, 200,           NULL,                                                'เดโม',                 'เซลเลอร์',   'BAY',   'ธนาคารกรุงศรีอยุธยา',     '3334445556', ''),
        ('grand_line_cards', 'APPROVED',     163, 160,           NULL,                                                'ลูฟี่',                'แสงทอง',     'TTB',   'ธนาคารทหารไทยธนชาต',      '6667778889', ''),
        ('kanto_cards',      'APPROVED',     153, 150,           NULL,                                                'คันโต',                'ใจดี',       'GSB',   'ธนาคารออมสิน',            '0201234567', ''),
        ('banned_trader',    'APPROVED',     143, 140,           NULL,                                                'สมปอง',                'ไม่ซื่อ',     'KKP',   'ธนาคารเกียรตินาคินภัทร',  '7778889990', ''),
        ('newbie_shop',      'SUBMITTED',    1,   NULL::numeric, NULL,                                                'น้องใหม่',              'ขายดี',      'CIMBT', 'ธนาคารซีไอเอ็มบี ไทย',    '8001234567', ''),
        ('pack_rippers',     'UNDER_REVIEW', 3,   NULL,          NULL,                                                'แพ็ก',                 'ริปเปอร์',   'UOBT',  'ธนาคารยูโอบี',             '9101234567', ''),
        ('shady_cards',      'REJECTED',     12,  10,            'ชื่อบัญชีธนาคารไม่ตรงกับชื่อที่ลงทะเบียน',                     'ชาดี้',                'การ์ด',      'TISCO', 'ธนาคารทิสโก้',             '5551234567', '')
       ) AS v(username, status, submitted_days, reviewed_days, reason, first_name, last_name, bank_code, bank_name,
              account, suffix);

-- One payout account per verified seller: the account from their approved verification.
INSERT INTO seller_payout_account (seller_profile_id, verification_id, bank_code, bank_name, account_name,
                                   account_number, verified_at, created_at)
SELECT v.seller_profile_id, v.id, v.bank_code, v.bank_name, v.legal_first_name || ' ' || v.legal_last_name,
       v.bank_account_number, v.reviewed_at, v.reviewed_at
  FROM seller_verification v
 WHERE v.status = 'APPROVED';

-- Sellers ship themselves and price shipping themselves. Each has a default
-- (lowest display_order); one tariff is switched off to show that state.
INSERT INTO seller_shipping_option (seller_profile_id, name, carrier_code, base_fee, per_item_fee, free_threshold,
                                    est_days_min, est_days_max, is_active, display_order)
SELECT pg_temp.spid(v.username), v.name, v.carrier, v.base_fee, v.per_item, v.free_threshold,
       v.dmin::smallint, v.dmax::smallint, v.active, v.disp::smallint
  FROM (VALUES
        ('pegasus_official', 'Kerry Express',     'KERRY',         45.00, 5.00,  1500.00, 1, 2, true,  0),
        ('pegasus_official', 'ไปรษณีย์ไทย EMS',     'THAILAND_POST', 40.00, 5.00,  1500.00, 2, 3, true,  1),
        ('card_kingdom_th',  'Kerry Express',     'KERRY',         50.00, 10.00, 3000.00, 1, 2, true,  0),
        ('card_kingdom_th',  'Flash Express',     'FLASH',         40.00, 5.00,  NULL,    1, 3, true,  1),
        ('tcg_corner',       'Flash Express',     'FLASH',         40.00, 5.00,  2000.00, 1, 3, true,  0),
        ('tcg_corner',       'ไปรษณีย์ไทย EMS',     'THAILAND_POST', 45.00, 5.00,  NULL,    2, 4, true,  1),
        ('tcg_corner',       'J&T Express',       'JNT',           35.00, 5.00,  NULL,    2, 4, false, 2),
        ('mint_vault',       'Kerry Express (ประกันพัสดุ)', 'KERRY', 80.00, 20.00, 10000.00, 1, 2, true, 0),
        ('demo_seller',      'ไปรษณีย์ไทย EMS',     'THAILAND_POST', 50.00, 5.00,  NULL,    1, 3, true,  0),
        ('grand_line_cards', 'Flash Express',     'FLASH',         40.00, 5.00,  2500.00, 1, 3, true,  0),
        ('grand_line_cards', 'Kerry Express',     'KERRY',         55.00, 10.00, NULL,    1, 2, true,  1),
        ('kanto_cards',      'ไปรษณีย์ไทย EMS',     'THAILAND_POST', 45.00, 5.00,  NULL,    2, 4, true,  0),
        ('banned_trader',    'J&T Express',       'JNT',           30.00, 0.00,  NULL,    3, 5, true,  0)
       ) AS v(username, name, carrier, base_fee, per_item, free_threshold, dmin, dmax, active, disp);

-- ---------- tokens ----------
-- Only SHA-256 hashes are stored, of random values nobody keeps, so none of
-- these can be used to sign in. They show what used and revoked tokens look like.
INSERT INTO auth_token (user_id, purpose, token_hash, family_id, expires_at, used_at, revoked_at, user_agent,
                        client_ip, created_at)
SELECT pg_temp.uid(v.username), v.purpose::token_purpose,
       encode(sha256(convert_to(gen_random_uuid()::text || v.username || v.n, 'UTF8')), 'hex'),
       gen_random_uuid(), pg_temp.ago(v.expires_days),
       CASE WHEN v.used_days IS NOT NULL THEN pg_temp.ago(v.used_days) END,
       CASE WHEN v.revoked_days IS NOT NULL THEN pg_temp.ago(v.revoked_days) END,
       v.agent, v.ip, pg_temp.ago(v.created_days)
  FROM (VALUES
        ('collector_mai',   1, 'REFRESH',        -25, NULL::numeric, 2,    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)', '171.96.10.21', 5),
        ('collector_mai',   2, 'REFRESH',        5,   NULL,          NULL, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',               '171.96.10.21', 35),
        ('deckbuilder_ton', 3, 'REFRESH',        -10, 20,            19,   'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5)',            '124.120.5.77', 20),
        ('demo_buyer',      4, 'PASSWORD_RESET', 29.9, 29.95,        NULL, 'Mozilla/5.0 (Linux; Android 15)',                         '49.228.1.10',  30),
        ('locked_user',     5, 'PASSWORD_RESET', 0.5, NULL,          0.6,  'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',               '58.11.2.3',    0.7)
       ) AS v(username, n, purpose, expires_days, used_days, revoked_days, agent, ip, created_days);
