# วิธีรัน Seed และบัญชีสำหรับทดสอบ

Seed ชุดนี้ใส่ข้อมูลตัวอย่างลงทุกตารางในระบบ ได้แก่ 21 บัญชี สินค้าจริง 138 รายการ (7 เกมและอุปกรณ์) 602 listing (การ์ดส่วนใหญ่มีหลายร้าน หลายสภาพ และหลายภาษาให้เลือก การ์ดยอดนิยม 13 ใบมีเกิน 10 รายการ) และ 65 คำสั่งซื้อครบทุกสถานะ รวมถึงการชำระเงิน คืนเงิน คืนสินค้า รีวิว และถอนเงิน รูปการ์ดและสินค้าเป็นรูปจริงที่โหลดเข้า MinIO ในเครื่องของแต่ละคน

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

**3) ต่ออินเทอร์เน็ต** ครั้งแรกต้องโหลดรูปประมาณ 60 MB ครั้งต่อไปไม่ต้องโหลดซ้ำ

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

```text
==> Uploading pictures to MinIO (the first run downloads about 60 MB)
Seed documents uploaded to pegasus/
Product pictures: 168 downloaded, 0 already there, 0 placeholder(s)
==> Loading the data (one transaction; nothing changes if any check fails)
 accounts | products | printings | listings | cards | orders | ledger_rows | reviews
       21 |      138 |       239 |      602 |  2313 |     65 |         329 |      25
==> Checking that every picture the data points at is in MinIO
All 328 pictures are in MinIO.
```

- **ครั้งถัดไป:** บรรทัดรูปจะเป็น `0 downloaded, 168 already there`
- **มี `placeholder(s)`:** บางรูปโหลดไม่สำเร็จ ให้รันคำสั่งเดิมอีกรอบ
- **ถ้ามี error:** seed จะยกเลิกทั้งหมด DB กลับเป็นแบบเดิม ไม่มีข้อมูลค้างครึ่ง ๆ

**หลังรันเสร็จ**
1. ถ้าเว็บเคยล็อกอินค้างไว้ ให้ **ออกจากระบบ** หรือล้างข้อมูลเว็บไซต์ `localhost:5173` ก่อน เพราะบัญชีและ id เปลี่ยนหมด
2. ถ้า API รันค้างอยู่ก่อน seed ใช้ต่อได้เลย ไม่ต้องรีสตาร์ท
3. เปิด **http://localhost:5173** แล้วล็อกอินด้วยบัญชีด้านล่าง

---

## 4. บัญชีสำหรับทดสอบ

### 🔑 รหัสผ่านทุกบัญชี: `Password123!`

ล็อกอินด้วย **อีเมล** ส่วน username ใช้ดูหน้าร้านหรือหน้าโปรไฟล์

### ผู้ดูแลระบบ

| อีเมล | Username | รหัสผ่าน | บทบาท | ใช้ทดสอบ |
| --- | --- | --- | --- | --- |
| `admin@example.com` | `admin` | `Password123!` | ADMIN | ตรวจสลิป, โอนคืนเงิน, อนุมัติถอนเงิน, อนุมัติผู้ขาย, ซ่อนรีวิว, ค่าคอมมิชชั่น, จัดการแคตตาล็อก (เมนู `/admin`) |
| `support@example.com` | `support_staff` | `Password123!` | SUPPORT | บทบาทฝ่ายช่วยเหลือ (อ่านอย่างเดียว) |

### ผู้ขาย (เมนู `/seller`)

| อีเมล | Username | รหัสผ่าน | ร้าน | ใช้ทดสอบ |
| --- | --- | --- | --- | --- |
| `store@pegasus.example.com` | `pegasus_official` | `Password123!` | Pegasus Official | สินค้าซีลและอุปกรณ์ 52 รายการ, จัดส่งออเดอร์, ถอนเงินกำลังโอน |
| `cardkingdom@example.com` | `card_kingdom_th` | `Password123!` | Card Kingdom TH | Pokémon, รับออเดอร์และจัดส่ง 2 รายการ, ราคาอัตโนมัติ, ประวัติสต็อก, ขอถอนเงิน |
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

### บัญชีที่ตั้งใจให้ล็อกอินไม่ได้

| อีเมล | Username | รหัสผ่าน | ผลที่ควรเห็น |
| --- | --- | --- | --- |
| `locked@example.com` | `locked_user` | `Password123!` | ถูกล็อก 15 นาทีหลังรัน seed (ใส่รหัสผิดเกินกำหนด) หลังจากนั้นล็อกอินได้ |
| `suspended@example.com` | `suspended_user` | `Password123!` | บัญชีถูกระงับ ล็อกอินไม่ได้ |
| `deactivated@example.com` | `deactivated_user` | `Password123!` | บัญชีปิดใช้งาน ล็อกอินไม่ได้ |

> **ทิป:** ล็อกอินหลายบทบาทพร้อมกันได้ โดยเปิดคนละ origin เช่น `http://localhost:5173` (ผู้ซื้อ), `http://seller.localhost:5173` (ผู้ขาย), `http://admin.localhost:5173` (แอดมิน)

> บัญชีเหล่านี้มีไว้ทดสอบในเครื่องเท่านั้น ห้ามใช้รหัสผ่านนี้กับระบบจริง

---

## 5. ข้อควรรู้

- **วันที่สัมพันธ์กับเวลารัน:** วันที่ในข้อมูลนับย้อนจากตอนรัน seed รันเมื่อไรก็ดูเหมือนเพิ่งเกิด
- **บางอย่างเปลี่ยนเองตามเวลา เหมือนระบบจริง:**
  - ออเดอร์ที่ยังไม่จ่ายของ `buyer@example.com` ถูกยกเลิกอัตโนมัติราว 1 ชั่วโมงหลังรัน
  - พัสดุที่ส่งแล้วถูกปิดออเดอร์อัตโนมัติเมื่อครบ 7 วันนับจากวันส่ง
- **อยากได้สถานะเริ่มต้นใหม่:** รันอีกครั้งพร้อม `-Reset`
- **รูปจริงไม่ได้เก็บใน git:** สคริปต์โหลดจากเว็บของผู้ผลิตเข้า MinIO ในเครื่อง ส่วนสลิป สมุดบัญชี ใบรับพัสดุ และรูปหลักฐานคืนสินค้า เป็นภาพจำลองที่มีลายน้ำ "ตัวอย่าง / SAMPLE"

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
