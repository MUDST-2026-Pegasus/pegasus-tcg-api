# วิธีรัน Seed และบัญชีสำหรับทดสอบ

Seed ชุดนี้ใส่ข้อมูลตัวอย่างลงทุกตารางในระบบ ขนาดใกล้เคียงเว็บขายการ์ดที่เปิดมาราว 9 เดือน

| ข้อมูล | จำนวน |
| --- | --- |
| บัญชี | 106 (ร้านที่เปิดขาย 29 ร้าน, ผู้ซื้อ 60+ คน, ใบสมัครผู้ขายรอตรวจ 6 ใบ) |
| สินค้า | 730 รายการ จาก 85 ชุด ครบ 7 เกม (การ์ดใบเดี่ยว 557, สินค้าซีล 159, อุปกรณ์) |
| รูปสินค้า | 3–6 รูปต่อการ์ด: รูปทางการ, รูปพิมพ์ญี่ปุ่นหรือ Parallel/Alt Art, ภาพขยายลายการ์ด, มุมเอียงโชว์ฟอยล์ |
| Listing | 3,064 รายการ ทุกชิ้นมีรูปที่ร้านถ่ายเอง 2–4 รูป (วางบน playmat, ใส่ท็อปโหลดเดอร์, มุมการ์ด, เคสเกรด) |
| คำสั่งซื้อ | 900 รายการ ย้อนหลัง 9 เดือน ครบทุกสถานะ พร้อมสลิป ใบรับพัสดุ คืนเงิน คืนสินค้า |
| อื่น ๆ | รีวิว 392, ประมูล 95 (กำลังประมูล 54), ถอนเงิน 206, โค้ดส่วนลด 49, ประวัติราคา 90 วัน, คำค้นยอดนิยม, บันทึกกิจกรรมแอดมิน 3,500+ |

ทุกหน้าของทุกบัญชีที่ใช้งานปกติมีข้อมูล seed ตรวจเองก่อน commit (ดู `99_verify.sql` ข้อ "Every page has something on it") ถ้ามีหน้าไหนว่างจะยกเลิกทั้งหมด

รูปการ์ดและสินค้าเป็นรูปจริงที่โหลดเข้า MinIO ในเครื่องของแต่ละคน ส่วนรูปที่ร้านถ่าย ภาพขยาย สลิป ใบรับพัสดุ และสมุดบัญชีของข้อมูลชุดใหม่ วาดจากข้อมูลตอนรัน seed ใน container ชั่วคราว (`full-seed/render/`) ไม่มีรูปพวกนี้ใน git

> ถ้าเป็นเครื่องใหม่ที่ยังไม่เคยรันโปรเจกต์เลย ให้ทำตาม [SEED_GUIDE.md](SEED_GUIDE.md) ก่อน มีขั้นตั้งค่า `.env` และวิธีเปิดหน้าเว็บ

---

## 1. เช็กก่อนรัน

ทุกคำสั่งรันในโฟลเดอร์ `pegasus-tcg-api`

**1) เปิด PostgreSQL และ MinIO**

```bash
docker compose up -d --wait postgres minio
```

**2) ให้ DB มีตารางครบตามโค้ดล่าสุด** (ทำเมื่อเพิ่ง pull โค้ดใหม่ หรือยังไม่เคยรัน API)

macOS / Linux / Git Bash:

```bash
./gradlew flywayMigrate
```

Windows PowerShell:

```powershell
.\gradlew.bat flywayMigrate
```

หรือจะรัน API ไปเลยก็ได้ (`./gradlew bootRun`) เพราะ API apply migration ตอนสตาร์ทอยู่แล้ว

**3) ต่ออินเทอร์เน็ต** ครั้งแรกต้องโหลดรูปประมาณ 200 MB และ image `python:3.12-slim-bookworm` สำหรับวาดรูป ใช้เวลารวมราว 15–25 นาที ครั้งต่อไปรูปที่มีแล้วไม่ต้องโหลดหรือวาดซ้ำ เหลือไม่กี่นาที

---

## 2. รัน Seed

**DB ใหม่ที่ยังว่าง**

| เครื่อง | คำสั่ง |
| --- | --- |
| Windows PowerShell | `powershell -ExecutionPolicy Bypass -File scripts\seed-full.ps1` |
| macOS / Linux / Git Bash | `bash scripts/seed-full.sh` |
| มี `make` | `make seed-full` |

**DB ที่มีข้อมูลอยู่แล้ว** (ต้องล้างก่อน)

| เครื่อง | คำสั่ง |
| --- | --- |
| Windows PowerShell | `powershell -ExecutionPolicy Bypass -File scripts\seed-full.ps1 -Reset` |
| macOS / Linux / Git Bash | `bash scripts/seed-full.sh --reset` |
| มี `make` | `make seed-full RESET=1` |

> ⚠️ **`-Reset` ลบข้อมูลทั้งหมดใน DB** ทั้งบัญชีที่สมัครเอง คำสั่งซื้อ และสินค้า แล้วใส่ seed ใหม่แทน ถ้าไม่ใส่ `-Reset` สคริปต์จะไม่ยอมรันบน DB ที่มีข้อมูล และไม่แตะอะไรเลย

ถ้าอยากเก็บข้อมูลเดิมไว้ก่อน ให้สำรอง DB ก่อนใช้ `-Reset`:

```bash
docker exec pegasus-tcg-postgres pg_dump -U postgres -Fc -f /tmp/before-seed.dump pegasus_tcg
docker cp pegasus-tcg-postgres:/tmp/before-seed.dump ./before-seed.dump
```

> บน Git Bash ให้เติม `MSYS_NO_PATHCONV=1` นำหน้าสองคำสั่งนี้ ไม่งั้น Git Bash จะแปลง `/tmp` เป็น path ของ Windows

กู้คืนภายหลัง (ใช้ได้ทั้ง PowerShell และ bash):

```bash
docker cp ./before-seed.dump pegasus-tcg-postgres:/tmp/before-seed.dump
docker exec pegasus-tcg-postgres pg_restore -U postgres -d pegasus_tcg --clean --if-exists /tmp/before-seed.dump
```

**ตัวเลือกเพิ่ม:** `-SkipImages` หรือ `--skip-images` ใส่ข้อมูลใหม่โดยไม่โหลดรูปซ้ำ ใช้เมื่อรูปอยู่ใน MinIO ครบแล้ว

---

## 3. ผลลัพธ์ที่ถูกต้อง

ข้อมูลประมูลสำหรับ demo มีตัวอย่างครบทั้ง LIVE, AWAITING_PAYMENT, SOLD, UNSOLD,
SUSPENDED และ CANCELLED โดยรายการ SOLD ผูกกับคำสั่งซื้อและการชำระเงินจริง
ส่วนรายการ AWAITING_PAYMENT มีคำสั่งซื้อที่ยังไม่จ่าย สถานะ LIVE และกำหนดชำระของ
ตัวอย่างที่เปิดอยู่จะคงอยู่ 7 วันหลังโหลด seed; โหลด seed ใหม่ก่อน demo หากเกินช่วงนี้


```text
==> Uploading pictures to MinIO (the first run downloads about 200 MB)
Seed documents uploaded to pegasus/
Product pictures: 1246 downloaded, 0 already there, 0 placeholder(s)
==> Loading the data (one transaction; nothing changes if any check fails)
 accounts | products | printings | listings | cards | orders | ledger_rows | reviews
      106 |      730 |      1628 |     3064 | 10116 |    900 |        4493 |     392
==> Drawing shop photos, close-ups and SAMPLE documents (the first run takes several minutes)
render: 6463 pictures in the data, 6463 to draw (raqm text)
render: 6463 drawn, 0 failed
==> Checking that every picture the data points at is in MinIO
All 7525 pictures are in MinIO.
```

- **ครั้งถัดไป:** บรรทัดรูปจะเป็น `0 downloaded, 1246 already there` และ `0 to draw`
- **มี `placeholder(s)` หรือ `wait for scans`:** บางรูปโหลดไม่สำเร็จ ให้รันคำสั่งเดิมอีกรอบ รูปที่วาดจากรูปนั้นจะถูกวาดใหม่เอง
- **ถ้ามี error:** seed จะยกเลิกทั้งหมด DB กลับเป็นแบบเดิม ไม่มีข้อมูลค้างครึ่ง ๆ

**หลังรันเสร็จ**
1. ถ้าเว็บเคยล็อกอินค้างไว้ ให้ **ออกจากระบบ** หรือล้างข้อมูลเว็บไซต์ `localhost:5173` ก่อน เพราะบัญชีและ id เปลี่ยนหมด
2. ถ้า API รันค้างอยู่ก่อน seed ใช้ต่อได้เลย ไม่ต้องรีสตาร์ท
3. เปิด **http://localhost:5173** แล้วล็อกอินด้วยบัญชีด้านล่าง

---

## 4. บัญชีสำหรับทดสอบ

### 🔑 รหัสผ่านทุกบัญชี: `Password123!`

ล็อกอินด้วย **อีเมล** ส่วน username ใช้ดูหน้าร้านหรือหน้าโปรไฟล์

### ⭐ บัญชีแนะนำสำหรับพรีเซ็น

ข้อมูลเยอะและครบทุกหน้า เปิดโชว์ได้ทันที

> **บัญชีเดียวครบทุกบทบาท: `demo@example.com`** (username `pegasus_demo`) มี role BUYER + SELLER + ADMIN ล็อกอินครั้งเดียวแล้วสลับไปหน้าร้าน (`/seller`) และหน้าแอดมิน (`/admin`) จากเมนูบนหัวเว็บได้ในแท็บเดียว
> - ฝั่งร้าน (ร้าน Pegasus Demo): listing 79 รายการพร้อมรูป, ออเดอร์ 27 รายการครบทุกสถานะ (มีออเดอร์รอกดเตรียมของและจัดส่ง), คืนสินค้า, รีวิว, ถอนเงิน, ประมูล, โค้ดของร้าน
> - ฝั่งผู้ซื้อ: ซื้อจากร้านอื่น 31 รายการ, ตะกร้า, wishlist, คอลเลกชัน, คูปอง, กำลังประมูลของร้านอื่น
> - ฝั่งแอดมิน: เห็นคิวชุดเดียวกับ `admin@example.com`
> - ข้อจำกัด: ซื้อหรือประมูลของร้านตัวเองไม่ได้ (ระบบกันไว้) ให้ซื้อจากร้านอื่นแทน และเป็นบัญชีเดโมเท่านั้น ระบบจริงไม่ควรให้คนเดียวถือทุกสิทธิ์

| บทบาท | อีเมล | จุดเด่น |
| --- | --- | --- |
| แอดมิน | `admin@example.com` | สลิปรอตรวจ 30+ ใบ, คืนเงินและถอนเงินรออนุมัติ, ใบสมัครผู้ขาย 6 ใบ, บันทึกกิจกรรม 3,500+ รายการ, รายงานประจำสัปดาห์ |
| ผู้ขาย | `cardkingdom@example.com` | ออเดอร์ 46 รายการครบทุกสถานะ, รีวิว 20, ถอนเงินหลายรอบ, ราคาอัตโนมัติ |
| ผู้ขาย (ร้านใหม่) | `digidestinedshop@example.com` | ออเดอร์ 34 รายการ, คืนสินค้าหลายขั้น, ประมูล, โค้ดส่วนลดของร้าน |
| ผู้ซื้อ | `kanokwan_ripper@example.com` | ออเดอร์ 15 รายการ, คอลเลกชันหลายสิบใบ, กำลังประมูล, wishlist, ตะกร้า, คูปอง |
| ผู้ซื้อ | `pongsakorn_cards@example.com` | ออเดอร์ 12 รายการหลายสถานะ, กำลังประมูล, คอลเลกชัน |

### ผู้ดูแลระบบ

| อีเมล | Username | รหัสผ่าน | บทบาท | ใช้ทดสอบ |
| --- | --- | --- | --- | --- |
| `admin@example.com` | `admin` | `Password123!` | ADMIN | ตรวจสลิป, โอนคืนเงิน, อนุมัติถอนเงิน, อนุมัติผู้ขาย, ซ่อนรีวิว, ค่าคอมมิชชั่น, จัดการแคตตาล็อก (เมนู `/admin`) |
| `support@example.com` | `support_staff` | `Password123!` | SUPPORT | บทบาทฝ่ายช่วยเหลือ (อ่านอย่างเดียว) |
| `nok.support@example.com` | `support_nok` | `Password123!` | SUPPORT | ฝ่ายช่วยเหลือคนที่สอง |

### ผู้ขาย (เมนู `/seller`)

| อีเมล | Username | รหัสผ่าน | ร้าน | ใช้ทดสอบ |
| --- | --- | --- | --- | --- |
| `store@pegasus.example.com` | `pegasus_official` | `Password123!` | Pegasus Official | สินค้าซีลทุกเกมและอุปกรณ์, จัดส่งออเดอร์, ถอนเงินกำลังโอน |
| `cardkingdom@example.com` | `card_kingdom_th` | `Password123!` | Card Kingdom TH | Pokémon, ออเดอร์ครบทุกสถานะ, ราคาอัตโนมัติ, ประวัติสต็อก, ขอถอนเงิน |
| `tcgcorner@example.com` | `tcg_corner` | `Password123!` | TCG Corner | One Piece, อนุมัติหรือปฏิเสธคำขอคืนสินค้า, listing ที่พักขาย |
| `mintvault@example.com` | `mint_vault` | `Password123!` | Mint Vault | การ์ดเกรด PSA/BGS/CGC, คืนสินค้ารอผู้ซื้อส่งกลับ, ถอนเงินครบทุกสถานะ |
| `seller@example.com` | `demo_seller` | `Password123!` | Demo Seller Shop | Magic และ Yu-Gi-Oh!, จัดส่งออเดอร์, listing ร่างและที่ปิดแล้ว |
| `grandline@example.com` | `grand_line_cards` | `Password123!` | Grand Line Cards | One Piece, Digimon, Union Arena, Dragon Ball, กดรับของคืนจากผู้ซื้อ |

### ผู้ขายในสถานะพิเศษ

| อีเมล | Username | รหัสผ่าน | สถานะ |
| --- | --- | --- | --- |
| `kanto@example.com` | `kanto_cards` | `Password123!` | เปิดโหมดพักร้อน สินค้าถูกซ่อนจากผู้ซื้อ |
| `banned@example.com` | `banned_trader` | `Password123!` | ร้านถูกระงับ listing ถูกบล็อก |
| `newbie@example.com` | `newbie_shop` | `Password123!` | สมัครเป็นผู้ขายแล้ว รอแอดมินตรวจ |
| `packrippers@example.com` | `pack_rippers` | `Password123!` | แอดมินกำลังตรวจใบสมัคร |
| `shady@example.com` | `shady_cards` | `Password123!` | ใบสมัครถูกปฏิเสธ ส่งใหม่ได้ |

### ผู้ซื้อ

| อีเมล | Username | รหัสผ่าน | ใช้ทดสอบ |
| --- | --- | --- | --- |
| `mai@example.com` | `collector_mai` | `Password123!` | 15 ออเดอร์ (รอตรวจสลิป ชำระแล้ว ส่งแล้ว ขอคืนสินค้า), ตะกร้าที่ราคาเปลี่ยน, wishlist, คอลเลกชัน |
| `ton@example.com` | `deckbuilder_ton` | `Password123!` | ยืนยันรับของ, ออเดอร์หลายร้าน, คืนเงินหลายแบบ |
| `buyer@example.com` | `demo_buyer` | `Password123!` | ออเดอร์ยังไม่จ่าย (จ่ายได้ภายในราว 45 นาทีหลังรัน seed), คำขอคืนสินค้ารอร้านตอบ |
| `ploy@example.com` | `ploy_collects` | `Password123!` | ออเดอร์ 2 ร้านในใบเดียว, คืนสินค้ากำลังส่งกลับ, ตะกร้ามีการ์ด PSA 10 |
| `nattapong@example.com` | `nattapong_tcg` | `Password123!` | คืนเงินที่รอแอดมินโอน, สลิปถูกปฏิเสธ, ตะกร้ามีของจากร้านที่พักร้อน |

### ร้านชุดใหญ่ (22 ร้าน)

ทุกร้านมี listing 35–90 รายการพร้อมรูปที่ร้านถ่าย, ออเดอร์ครบทุกสถานะ (รอชำระ ชำระแล้ว เตรียมของ ส่งแล้ว ถึงแล้ว สำเร็จ ยกเลิก คืนสินค้า), รีวิว, ถอนเงิน, ประมูล, โค้ดส่วนลดของร้าน และของที่เก็บไว้ในสต็อก รหัสผ่าน `Password123!` ทุกบัญชี

| อีเมล | Username | ร้าน | ขายอะไร |
| --- | --- | --- | --- |
| `pokeparadise@example.com` | `poke_paradise` | Poke Paradise | Pokémon · การ์ดใบเดี่ยว |
| `eeveehouse@example.com` | `eevee_house` | Eevee House | Pokémon · การ์ดใบเดี่ยว |
| `sealedsupply@example.com` | `sealed_supply` | Sealed Supply Co. | ทุกเกม · สินค้าซีล |
| `gradedgallery@example.com` | `graded_gallery` | Graded Gallery | Pokémon, One Piece · การ์ดเกรด |
| `strawhatmarket@example.com` | `strawhat_market` | Straw Hat Market | One Piece · การ์ดใบเดี่ยว |
| `redlinetcg@example.com` | `redline_tcg` | Red Line TCG | One Piece, Dragon Ball FW · การ์ดใบเดี่ยว |
| `manaforge@example.com` | `mana_forge` | Mana Forge | Magic · การ์ดใบเดี่ยว |
| `commandercorner@example.com` | `commander_corner` | Commander Corner | Magic · การ์ดใบเดี่ยว |
| `duelacademybkk@example.com` | `duel_academy_bkk` | Duel Academy BKK | Yu-Gi-Oh! · การ์ดใบเดี่ยว |
| `millenniumcards@example.com` | `millennium_cards` | Millennium Cards | Yu-Gi-Oh! · การ์ดใบเดี่ยว |
| `digidestinedshop@example.com` | `digidestined_shop` | DigiDestined Shop | Digimon · การ์ดใบเดี่ยว |
| `animearenacards@example.com` | `anime_arena_cards` | Anime Arena Cards | Union Arena · การ์ดใบเดี่ยว |
| `saiyancards@example.com` | `saiyan_cards` | Saiyan Cards | Dragon Ball FW · การ์ดใบเดี่ยว |
| `chiangmaicardclub@example.com` | `chiangmai_card_club` | Chiang Mai Card Club | Pokémon, One Piece · ใบเดี่ยวและซีล |
| `phukettcg@example.com` | `phuket_tcg` | Phuket TCG | Pokémon, Magic, Yu-Gi-Oh! · ใบเดี่ยวและซีล |
| `koratcollectibles@example.com` | `korat_collectibles` | Korat Collectibles | Pokémon, Digimon, Dragon Ball FW · ใบเดี่ยวและซีล |
| `hatyaihobby@example.com` | `hatyai_hobby` | Hatyai Hobby | One Piece, Union Arena, Pokémon · ใบเดี่ยวและซีล |
| `khonkaencards@example.com` | `khonkaen_cards` | Khon Kaen Cards | Pokémon · การ์ดใบเดี่ยว |
| `bangnabooster@example.com` | `bangna_booster` | Bangna Booster | Pokémon, One Piece, Digimon · สินค้าซีล |
| `rarepullth@example.com` | `rare_pull_th` | Rare Pull Thailand | Pokémon, One Piece, Magic, Yu-Gi-Oh!, Union Arena · การ์ดเกรด |
| `budgetbinder@example.com` | `budget_binder` | Budget Binder | Pokémon, One Piece, Digimon, Dragon Ball FW · การ์ดราคาประหยัด |
| `lumpinicollectibles@example.com` | `lumpini_collectibles` | Lumpini Collectibles | Pokémon · การ์ดใบเดี่ยว |

### ใบสมัครผู้ขายที่รอแอดมินตรวจ

| อีเมล | Username | สถานะใบสมัคร |
| --- | --- | --- |
| `flipzoneth@example.com` | `flipzone_th` | ส่งแล้ว รอแอดมินเปิดดู |
| `cardcraftbkk@example.com` | `cardcraft_bkk` | แอดมินกำลังตรวจ |
| `tinytcg@example.com` | `tiny_tcg` | ส่งแล้ว รอแอดมินเปิดดู |
| `mysticpulls@example.com` | `mystic_pulls` | แอดมินกำลังตรวจ |
| `fakeboxseller@example.com` | `fakebox_seller` | ถูกปฏิเสธ (รูปสมุดบัญชีถูกแก้ไข) |

### ผู้ซื้อชุดใหญ่ (56 คน)

ทุกคนมีออเดอร์อย่างน้อย 3 รายการ (มีที่สำเร็จแล้ว), ที่อยู่, ตะกร้า, wishlist, คอลเลกชันที่เปิดโชว์, คูปองที่เก็บไว้ และหลายคนกำลังประมูล บางคนผูกบัญชี Google ไว้ด้วย (ยังล็อกอินด้วยรหัสผ่านได้ตามปกติ)

<details>
<summary>รายชื่อผู้ซื้อทั้งหมด</summary>

| อีเมล | Username | ชื่อที่แสดง |
| --- | --- | --- |
| `somchai_gg@example.com` | `somchai_gg` | สมชาย |
| `nattapon_gg@example.com` | `nattapon_gg` | Nattapon ป. |
| `pimchanok_op@example.com` | `pimchanok_op` | พิมพ์ชนก |
| `kanokwan_ripper@example.com` | `kanokwan_ripper` | Kanokwan น. |
| `thanawat@example.com` | `thanawat` | Thanawat ว. |
| `sirinya@example.com` | `sirinya` | ศิริญญา |
| `pattarapol_th@example.com` | `pattarapol_th` | Pattarapol ก. |
| `chanida_th@example.com` | `chanida_th` | ชนิดา |
| `worawut_duel@example.com` | `worawut_duel` | วรวุฒิ |
| `jirapat_gg@example.com` | `jirapat_gg` | จิรภัทร |
| `kittipat2025@example.com` | `kittipat2025` | Kittipat ส. |
| `napat@example.com` | `napat` | ณภัทร |
| `pornpimol2025@example.com` | `pornpimol2025` | Pornpimol แ. |
| `theerapat_ripper@example.com` | `theerapat_ripper` | Theerapat ช. |
| `wanida_duel@example.com` | `wanida_duel` | Wanida ว. |
| `apichart99@example.com` | `apichart99` | Apichart อ. |
| `rattana_mtg@example.com` | `rattana_mtg` | Rattana บ. |
| `sutthipong_binder@example.com` | `sutthipong_binder` | สุทธิพงษ์ |
| `nicha2025@example.com` | `nicha2025` | ณิชา |
| `panupong_binder@example.com` | `panupong_binder` | Panupong แ. |
| `chayanin@example.com` | `chayanin` | Chayanin จ. |
| `pakorn_cards@example.com` | `pakorn_cards` | Pakorn ศ. |
| `ploypailin_mtg@example.com` | `ploypailin_mtg` | Ploypailin พ. |
| `tanakrit_pulls@example.com` | `tanakrit_pulls` | ธนกฤต |
| `wichuda2025@example.com` | `wichuda2025` | วิชุดา |
| `korawit_th@example.com` | `korawit_th` | กรวิชญ์ |
| `benjamas99@example.com` | `benjamas99` | Benjamas เ. |
| `pongsakorn_cards@example.com` | `pongsakorn_cards` | พงศกร |
| `suphansa_op@example.com` | `suphansa_op` | สุพรรษา |
| `naphat_mtg@example.com` | `naphat_mtg` | Naphat เ. |
| `thitiporn_pulls@example.com` | `thitiporn_pulls` | ฐิติพร |
| `arthit_th@example.com` | `arthit_th` | อาทิตย์ |
| `kamonchanok_gg@example.com` | `kamonchanok_gg` | Kamonchanok ร. |
| `peerapat_th@example.com` | `peerapat_th` | พีรพัฒน์ |
| `jarunee_pulls@example.com` | `jarunee_pulls` | Jarunee ใ. |
| `thanakorn_op@example.com` | `thanakorn_op` | Thanakorn ท. |
| `pichaya_tcg@example.com` | `pichaya_tcg` | Pichaya พ. |
| `weerawat99@example.com` | `weerawat99` | Weerawat ส. |
| `sasithorn_gg@example.com` | `sasithorn_gg` | Sasithorn ศ. |
| `kongpob2025@example.com` | `kongpob2025` | Kongpob แ. |
| `natcha_th@example.com` | `natcha_th` | ณัชชา |
| `chatchai_gg@example.com` | `chatchai_gg` | Chatchai ว. |
| `onanong_collects@example.com` | `onanong_collects` | Onanong อ. |
| `tawan@example.com` | `tawan` | Tawan บ. |
| `praew_ripper@example.com` | `praew_ripper` | Praew อ. |
| `boss99@example.com` | `boss99` | บอส |
| `fah20@example.com` | `fah20` | Fah แ. |
| `mint_binder@example.com` | `mint_binder` | Mint จ. |
| `beam_pulls@example.com` | `beam_pulls` | Beam ศ. |
| `ice_collects@example.com` | `ice_collects` | ไอซ์ |
| `nut_tcg@example.com` | `nut_tcg` | Nut บ. |
| `palm_cards@example.com` | `palm_cards` | Palm ส. |
| `earth_pkm@example.com` | `earth_pkm` | เอิร์ธ |
| `gun99@example.com` | `gun99` | Gun เ. |
| `june2025@example.com` | `june2025` | จูน |
| `film_th@example.com` | `film_th` | Film ป. |

</details>

### บัญชีที่ตั้งใจให้ล็อกอินไม่ได้

| อีเมล | Username | รหัสผ่าน | ผลที่ควรเห็น |
| --- | --- | --- | --- |
| `locked@example.com` | `locked_user` | `Password123!` | ถูกล็อก 15 นาทีหลังรัน seed (ใส่รหัสผิดเกินกำหนด) หลังจากนั้นล็อกอินได้ |
| `suspended@example.com` | `suspended_user` | `Password123!` | บัญชีถูกระงับ ล็อกอินไม่ได้ |
| `deactivated@example.com` | `deactivated_user` | `Password123!` | บัญชีปิดใช้งาน ล็อกอินไม่ได้ |

> **ทิป:** ล็อกอินหลายบทบาทพร้อมกันได้ โดยเปิดคนละ origin เช่น `http://localhost:5173` (ผู้ซื้อ), `http://seller.localhost:5173` (ผู้ขาย), `http://admin.localhost:5173` (แอดมิน)

> บัญชีเหล่านี้มีไว้ทดสอบในเครื่องเท่านั้น ห้ามใช้รหัสผ่านนี้กับระบบจริง

### โค้ดส่วนลด (TCG-575)

วันที่ทั้งหมดนับจากวันที่รัน seed ส่วน `PEGASUS10` / `MINMIN50` ไม่มีใน seed เพราะ e2e สร้างเอง

| โค้ด | ของใคร | ใช้ทดสอบ |
| --- | --- | --- |
| `WELCOME100` | Pegasus | หน้าแรกลำดับ 1 · ลด ฿100 ขั้นต่ำ ฿1,000 · แจก 500 ใบ (`collector_mai`, `ploy_collects` เก็บแล้ว) |
| `PEGASUS15` | Pegasus | หน้าแรกลำดับ 2 · 15% สูงสุด ฿300 ขั้นต่ำ ฿1,500 |
| `FREESHIP` | Pegasus | หน้าแรกลำดับ 3 · ส่งฟรีร้านละไม่เกิน ฿60 ขั้นต่ำ ฿500 |
| `FLASH30` | Pegasus | หน้าแรกลำดับ 4 · แจก 1 ใบ `demo_buyer` เก็บไปแล้ว → คนอื่นเห็น "หมดแล้ว" |
| `NEWBIE50` | Pegasus | เฉพาะผู้ซื้อใหม่ |
| `POKEMON10` | Pegasus | 10% เฉพาะเกม Pokemon TCG |
| `VIP200` | Pegasus | PRIVATE + ต้องเก็บก่อนใช้ (`demo_buyer` เก็บแล้ว) · คนอื่นพิมพ์ใช้ → `PROMO_NOT_CLAIMED` |
| `SUMMER25` | Pegasus | หยุดชั่วคราว |
| `SOON10` | Pegasus | ยังไม่เริ่ม (เริ่มอีก 5 วัน) |
| `OLDDEAL20` | Pegasus | หมดอายุแล้ว |
| `KINGDOM50` | `card_kingdom_th` | ให้กดเก็บที่หน้าร้าน · ลด ฿50 ขั้นต่ำ ฿500 |
| `KINGDOM10P` | `card_kingdom_th` | PRIVATE 10% สูงสุด ฿150 |
| `CORNER5` | `tcg_corner` | 5% · ใช้กับออเดอร์ที่หมดเวลาชำระของ `ploy_collects` แล้วคืนสิทธิ์ (RELEASED) |
| `CORNER100` | `tcg_corner` | Pegasus หยุดพร้อมเหตุผล → ร้านเปิดเองไม่ได้ |

---

## 5. ข้อควรรู้

- **วันที่สัมพันธ์กับเวลารัน:** วันที่ในข้อมูลนับย้อนจากตอนรัน seed รันเมื่อไรก็ดูเหมือนเพิ่งเกิด
- **บางอย่างเปลี่ยนเองตามเวลา เหมือนระบบจริง:**
  - ออเดอร์ที่ยังไม่จ่ายของ `buyer@example.com` ถูกยกเลิกอัตโนมัติราว 1 ชั่วโมงหลังรัน
  - พัสดุที่ส่งแล้วถูกปิดออเดอร์อัตโนมัติเมื่อครบ 7 วันนับจากวันส่ง
- **อยากได้สถานะเริ่มต้นใหม่:** รันอีกครั้งพร้อม `-Reset`
- **รูปจริงไม่ได้เก็บใน git:** สคริปต์โหลดจากเว็บของผู้ผลิตเข้า MinIO ในเครื่อง ส่วนสลิป สมุดบัญชี ใบรับพัสดุ และรูปหลักฐานคืนสินค้า เป็นภาพจำลองที่มีลายน้ำ "ตัวอย่าง / SAMPLE"
- **รูปที่วาดตอนรัน seed:** รูปที่ร้านถ่าย (playmat, ท็อปโหลดเดอร์, มุมการ์ด, เคสเกรดที่มีคำว่า DEMO บนป้าย), ภาพขยายและมุมเอียงบนหน้าสินค้า และเอกสาร SAMPLE ของข้อมูลชุดใหม่ วาดด้วย `full-seed/render/render.py` ใน container `python:3.12-slim-bookworm` ที่สร้างแล้วลบทิ้งเอง ฟอนต์ Sarabun (OFL) อยู่ใน `render/fonts/`
- **ข้อมูลชุดใหญ่สร้างจาก generator:** ไฟล์ที่ลงท้าย `b_*_more.sql`, `06z_replay.sql`, `11_activity.sql` และรายการรูปท้าย `image-sources.txt` สร้างจากราคาตลาดและข้อมูลการ์ดจริง ณ วันที่ทำ ถ้าต้องแก้ข้อมูลให้แก้ไฟล์ SQL ตรง ๆ

---

## 6. ปัญหาที่เจอบ่อย

| อาการ | วิธีแก้ |
| --- | --- |
| `This database already has data...` | DB มีข้อมูลอยู่แล้ว ใส่ `-Reset` / `--reset` / `RESET=1` |
| `database ... is missing the latest tables` | รัน `./gradlew flywayMigrate` หรือรัน API ก่อน (ข้อ 1) |
| `container pegasus-tcg-postgres is not running` | `docker compose up -d --wait postgres minio` |
| `running scripts is disabled on this system` | รันผ่าน `powershell -ExecutionPolicy Bypass -File ...` ตามตารางข้อ 2 |
| `password authentication failed` ตอนรัน Gradle หรือ API | พอร์ต 5432 ชนกับ PostgreSQL ในเครื่อง ตั้ง `DB_PORT=5436` และแก้ `DB_URL` ใน `.env` ให้ใช้พอร์ตเดียวกัน |
| เว็บไม่มีรูป หรือเห็นรูป "ยังโหลดรูปไม่สำเร็จ" | รัน seed อีกรอบ (ไม่ใส่ `--skip-images`) และเช็ก `MINIO_PUBLIC_ENDPOINT=http://localhost:9000` ใน `.env` |
| ล็อกอินแล้วข้อมูลแปลก ๆ | ออกจากระบบหรือล้างข้อมูลเว็บไซต์ แล้วล็อกอินใหม่ |
| `drawing the pictures failed` | ขั้นวาดรูปต้องใช้อินเทอร์เน็ต (pip) ต่อเน็ตแล้วรันคำสั่งเดิมพร้อม `-Reset` อีกรอบ รูปที่วาดแล้วจะไม่วาดซ้ำ |
| `Missing from MinIO` ตามด้วยรายการ `seed/gen/...` | ขั้นวาดรูปไม่ครบ รันอีกครั้ง (ไม่ใส่ `--skip-images`) |


## Auction demo data (TCG-626)

The full seed loads `09_auctions.sql` with live auctions (with and without bids, one ending soon, and one with Buy Now), awaiting payment, sold, unsold (including an unpaid winner), and suspended auctions. The accounts `demo_buyer`, `deckbuilder_ton`, and `ploy_collects` have one, two, and three active unpaid-win strikes respectively; `ploy_collects` also has an active bidding ban. `99_verify.sql` checks that every demo status and penalty step exists. Open auctions keep their cards in `ON_AUCTION`; closed unsold and suspended auctions return them to stock.

After loading the seed, run the standalone financial reconciliation with:

```sh
psql -X -v ON_ERROR_STOP=1 -f scripts/db/verify-financial-ledger.sql
```
