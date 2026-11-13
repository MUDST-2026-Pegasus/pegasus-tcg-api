-- TCG-773 seed: add more rows in a new migration, or through /admin/search-aliases.
INSERT INTO search_alias (alias, target) VALUES
    ('โปเกมอน', 'Pokemon'), ('โปเกม่อน', 'Pokemon'), ('โปเกมม่อน', 'Pokemon'),
    ('วันพีซ', 'One Piece'), ('วันพีช', 'One Piece'),
    ('ยูกิ', 'Yu-Gi-Oh'), ('ยูกิโอ', 'Yu-Gi-Oh'),
    ('ดิจิมอน', 'Digimon'),
    ('ดราก้อนบอล', 'Dragon Ball'),
    ('ปิกาจู', 'Pikachu'),
    ('ชาริซาร์ด', 'Charizard'), ('ลิซาร์ดอน', 'Charizard'),
    ('มิวทู', 'Mewtwo'),
    ('อีวุย', 'Eevee'),
    ('ลูฟี่', 'Luffy'),
    ('โซโล', 'Zoro'), ('โซโร', 'Zoro'),
    ('บูสเตอร์บ็อกซ์', 'Booster Box'), ('กล่องบูสเตอร์', 'Booster Box'),
    ('ซอง', 'Booster Pack'),
    ('การ์ดเกรด', 'graded'),
    ('สลีฟ', 'Sleeves'), ('ซองใส่การ์ด', 'Sleeves')
ON CONFLICT DO NOTHING;
