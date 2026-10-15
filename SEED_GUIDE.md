# คู่มือรัน Full Seed (ข้อมูลตัวอย่างทั้งระบบ)

ทำตามลำดับนี้ตั้งแต่เครื่องเปล่า แล้วทุกคนจะได้ข้อมูลชุดเดียวกัน คือ 21 บัญชี สินค้าจริง 138 รายการ คำสั่งซื้อ 65 รายการครบทุกสถานะ และภาพการ์ดหรือสินค้าจริงที่โหลดจาก MinIO ในเครื่องตัวเอง

รายละเอียดเชิงเทคนิคอยู่ใน [README หัวข้อ Full demo data](README.md#full-demo-data)

> ถ้าเครื่องรันโปรเจกต์ได้อยู่แล้วและอยากรัน seed อย่างเดียว ดู [RUN_SEED.md](RUN_SEED.md) ซึ่งสั้นกว่า และมีตารางบัญชีพร้อมรหัสผ่านครบ

---

## 0. สิ่งที่ต้องมีในเครื่อง

| ใช้ทำอะไร | โปรแกรม |
| --- | --- |
| รัน PostgreSQL และ MinIO | Docker Desktop (เปิดไว้ตลอด) |
| รัน API | Java 25 หรือจะรัน API ใน Docker แทนก็ได้ (ดูขั้นที่ 4 ทางเลือก B) |
| รันหน้าเว็บ | Node.js 22 และ pnpm 10.6.5 |
| ดาวน์โหลดภาพตอน seed ครั้งแรก | อินเทอร์เน็ต (ประมาณ 60 MB) |

> Windows: ใช้ PowerShell ได้ทุกขั้นตอน ไม่ต้องติดตั้ง `make`

---

## 1. ดึงโค้ด

```bash
git clone https://github.com/MUDST-2026-Pegasus/pegasus-tcg-api.git
git clone https://github.com/MUDST-2026-Pegasus/pegasus-tcg-web.git
```

ถ้าเคย clone แล้ว ให้อัปเดตเป็นโค้ดล่าสุดของ branch `develop` ทั้งสอง repo

```bash
cd pegasus-tcg-api
git switch develop
git pull
```

> ต้องมีโฟลเดอร์ `src/test/resources/db/full-seed/` และ `scripts/seed-full.ps1` อยู่ใน repo ถ้าไม่เห็น แปลว่า branch นี้ยังไม่มี full seed

---

## 2. ตั้งค่า `.env` ของ API

อยู่ในโฟลเดอร์ `pegasus-tcg-api`

```bash
cp .env.example .env
```

แก้ค่าใน `.env` อย่างน้อยตามนี้

| ตัวแปร | ใส่อะไร |
| --- | --- |
| `JWT_SECRET` | สตริงสุ่ม base64 (วิธีสร้างอยู่ด้านล่าง) |
| `POSTGRES_PASSWORD` และ `DB_PASSWORD` | รหัสผ่านเดียวกันทั้งสองช่อง |
| `MINIO_ACCESS_KEY` | เช่น `minioadmin` |
| `MINIO_SECRET_KEY` | รหัสผ่านอย่างน้อย 8 ตัวอักษร |
| `MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000` |

สร้าง `JWT_SECRET`:

```bash
openssl rand -base64 48
```

ถ้าไม่มี `openssl` ใช้ PowerShell แทน:

```powershell
[Convert]::ToBase64String((1..48 | ForEach-Object { [byte](Get-Random -Maximum 256) }))
```

> **ถ้าเครื่องมี PostgreSQL ติดตั้งไว้แล้ว (พอร์ต 5432 ไม่ว่าง):** ตั้ง `DB_PORT=5436` และแก้ `DB_URL=jdbc:postgresql://localhost:5436/pegasus_tcg` ให้พอร์ตตรงกัน ไม่งั้น API จะต่อผิดเครื่องและเจอ `password authentication failed`

---

## 3. เปิด PostgreSQL และ MinIO

```bash
docker compose up -d --wait postgres minio
```

เช็กว่าขึ้นแล้ว:

```bash
docker compose ps
```

ต้องเห็น `pegasus-tcg-postgres` และ `pegasus-tcg-minio` เป็น `healthy`

---

## 4. รัน API หนึ่งครั้ง เพื่อให้ Flyway สร้างตาราง

**ทางเลือก A: รันบนเครื่อง (มี Java 25)**

```bash
./gradlew bootRun
```

Windows:

```powershell
.\gradlew.bat bootRun
```

รอจนเห็นข้อความ `Started PegasusTcgApiApplication` แล้วเปิดค้างไว้ หน้าเว็บต้องใช้ API ตัวนี้ จากนั้นเปิดเทอร์มินัลใหม่ทำขั้นต่อไป

**ทางเลือก B: รัน API ใน Docker (ไม่ต้องมี Java)**

```bash
docker compose up -d --build
```

> ถ้าเคยรัน API มาก่อน ให้หยุดแล้วรันใหม่ด้วยโค้ดล่าสุด เพื่อให้ migration ตัวใหม่ถูก apply ถ้ายังไม่ได้ apply สคริปต์ seed จะหยุดและบอกให้รัน API ก่อน

---

## 5. รัน seed

อยู่ในโฟลเดอร์ `pegasus-tcg-api` เลือกคำสั่งตามเครื่อง

**DB ใหม่ที่ยังว่าง (มีแค่ตารางจาก migration):**

| เครื่อง | คำสั่ง |
| --- | --- |
| Windows PowerShell | `powershell -ExecutionPolicy Bypass -File scripts\seed-full.ps1` |
| macOS / Linux / Git Bash | `bash scripts/seed-full.sh` |
| มี `make` | `make seed-full` |

**DB ที่มีข้อมูลอยู่แล้ว (เคยสมัครบัญชี เคย seed หรือเคยสั่งซื้อ):**

| เครื่อง | คำสั่ง |
| --- | --- |
| Windows PowerShell | `powershell -ExecutionPolicy Bypass -File scripts\seed-full.ps1 -Reset` |
| macOS / Linux / Git Bash | `bash scripts/seed-full.sh --reset` |
| มี `make` | `make seed-full RESET=1` |

> ⚠️ `-Reset` / `--reset` / `RESET=1` **ลบข้อมูลทั้งหมดใน DB** (บัญชีที่สมัครเอง คำสั่งซื้อ สินค้า) แล้วใส่ seed ใหม่ เก็บไว้แค่ประวัติ migration กับรายการ role ถ้าไม่ใส่ตัวเลือกนี้ สคริปต์จะไม่ยอมรันบน DB ที่มีข้อมูลและไม่แตะอะไรเลย

**ผลที่ควรเห็น (สำเร็จ):**

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

- **ครั้งถัดไป:** จะเห็น `168 already there` เพราะภาพที่โหลดแล้วไม่ต้องโหลดซ้ำ
- **มี placeholder:** แปลว่าบางภาพโหลดไม่สำเร็จ (เน็ตหรือเซิร์ฟเวอร์ต้นทาง) รันคำสั่งเดิมอีกรอบได้เลย
- **อยากโหลดเฉพาะข้อมูลใหม่:** เติม `-SkipImages` หรือ `--skip-images` จะข้ามการโหลดภาพ

---

## 6. เปิดหน้าเว็บ

อยู่ในโฟลเดอร์ `pegasus-tcg-web`

```bash
corepack enable
corepack prepare pnpm@10.6.5 --activate
pnpm install --frozen-lockfile
pnpm dev
```

เปิด **http://localhost:5173**

> ถ้า API ไม่ได้อยู่ที่พอร์ต 8080 ให้สร้างไฟล์ `.env.local` ใส่ `VITE_API_PROXY_TARGET=http://localhost:<พอร์ต API>` แล้วรัน `pnpm dev` ใหม่

> ถ้าเคยล็อกอินเว็บไว้ก่อน `-Reset` ให้ **ออกจากระบบ** หรือล้างข้อมูลเว็บไซต์ของ `localhost:5173` ก่อน เพราะ id ผู้ใช้เปลี่ยนหลัง reset

**เช็กเร็ว ๆ ว่า seed ขึ้นครบ:**

- **หน้าแรก:** มีสไลด์ 5 อัน (Mega Evolution, Final Fantasy, One Piece, Union Arena, Digimon) และโลโก้ 7 เกม
- **หน้าสินค้า:** มีรูปการ์ดจริง เช่น http://localhost:5173/products/charizard-ex-sir-199-165 ต้องเห็นรูปการ์ด ราคา 2 รายการ (สภาพ NM และ LP) ท่าโจมตี และผู้สะสมที่เปิดโชว์
- **ภาพเอกสาร:** สลิป สมุดบัญชี และใบรับพัสดุ เป็นภาพจำลองที่มีลายน้ำ "ตัวอย่าง / SAMPLE"

---

## 7. บัญชีสำหรับทดสอบ

**ทุกบัญชีใช้รหัสผ่าน `Password123!`**

> ทิป: ถ้าจะล็อกอินหลายบทบาทพร้อมกัน ให้เปิดคนละ origin เช่น `http://localhost:5173` (ผู้ซื้อ), `http://seller.localhost:5173` (ผู้ขาย), `http://admin.localhost:5173` (แอดมิน) แต่ละ origin จำการล็อกอินแยกกัน

### ผู้ดูแลระบบ

| อีเมล | บทบาท | ทดสอบอะไร |
| --- | --- | --- |
| `admin@example.com` | ADMIN | ดูรายละเอียดด้านล่าง |
| `support@example.com` | SUPPORT | บัญชีบทบาท Support (อ่านอย่างเดียว) |

สิ่งที่ `admin@example.com` ทดสอบได้ในหน้า `/admin`:

- **`/admin/payments`:** สลิปรอตรวจ 1 รายการ (Mai กล่อง FB07 ยอด ฿2,950) ยอดและเลขอ้างอิงในสลิปตรงกับระบบ ลองอนุมัติหรือปฏิเสธ
- **`/admin/refunds`:**
  - พร้อมโอน 1 รายการ (Nattapong คืน Zapdos ex SIR ฿930)
  - กำลังโอน 1 รายการ (Ton คืน Tezzeret หลังโอนครั้งแรกไม่สำเร็จ)
- **`/admin/payouts`:**
  - คำขอใหม่: Card Kingdom TH
  - อนุมัติแล้ว: TCG Corner
  - กำลังโอน: Pegasus Official
  - ประวัติ: จ่ายแล้ว ปฏิเสธ และโอนไม่สำเร็จ (Mint Vault)
- **`/admin/sellers`:** ใบสมัครรอตรวจ 2 ราย (Newbie Shop ส่งแล้ว, Pack Rippers กำลังตรวจ) มีรูปสมุดบัญชี SAMPLE
- **`/admin/reviews`:** รีวิว 1★ ที่ถูกซ่อน (ร้าน Card Kingdom TH)
- **`/admin/commission`:**
  - กฎที่ใช้งาน: ทั่วไป 5%, One Piece 4%, กล่องสุ่ม 3% (ขั้นต่ำ 30 สูงสุด 250), Pegasus Official 0%, Mint Vault 4% (เริ่ม 30 วันก่อน)
  - กฎที่ไม่มีผล: กฎหมดอายุ และกฎที่ปิดไว้
- **`/admin/users`:** บัญชีถูกระงับ ปิดใช้งาน ล็อก และผู้ขายที่ถูกระงับ
- **`/admin/orders`:** คำสั่งซื้อครบทุกสถานะ
- **`/admin/catalog`:** 7 เกม 25 ชุด 138 สินค้า 14 หมวด

### ผู้ขาย (เมนู `/seller`)

| อีเมล | ร้าน | ทดสอบอะไร |
| --- | --- | --- |
| `store@pegasus.example.com` | Pegasus Official | ดูรายละเอียดด้านล่าง |
| `cardkingdom@example.com` | Card Kingdom TH | ดูรายละเอียดด้านล่าง |
| `tcgcorner@example.com` | TCG Corner | ดูรายละเอียดด้านล่าง |
| `mintvault@example.com` | Mint Vault | ดูรายละเอียดด้านล่าง |
| `seller@example.com` | Demo Seller Shop | ดูรายละเอียดด้านล่าง |
| `grandline@example.com` | Grand Line Cards | ดูรายละเอียดด้านล่าง |
| `kanto@example.com` | Kanto Cards | เปิดโหมดพักร้อน สินค้าทั้ง 4 รายการถูกซ่อนจากผู้ซื้อ |
| `banned@example.com` | Banned Trader | ร้านถูกระงับ (ขายการ์ดปลอม) และ listing ถูกบล็อก |
| `newbie@example.com` | Newbie Shop | สมัครผู้ขายแล้ว รอแอดมินตรวจ |
| `packrippers@example.com` | Pack Rippers | อยู่ระหว่างแอดมินตรวจ |
| `shady@example.com` | Shady Cards | ใบสมัครถูกปฏิเสธพร้อมเหตุผล ลองส่งใหม่ได้ |

**Pegasus Official** (สินค้าซีลและอุปกรณ์ 52 รายการ):
- ออเดอร์ "กำลังเตรียม" (แผ่นรองเล่นของ Ploy) ลองกดจัดส่ง
- ออเดอร์ที่ส่งแล้ว 1 รายการ และร่าง listing 1 รายการ
- ถอนเงินกำลังโอน
- รีวิว 5.0

**Card Kingdom TH** (Pokémon 23 รายการ):
- ออเดอร์ชำระแล้ว 2 รายการ (Charizard ex SIR ของ Mai, ซอง 151 ของ Ploy) ลองรับออเดอร์และจัดส่ง
- ราคาอัตโนมัติ (Pikachu ex) และการ์ดสภาพ LP
- ประวัติเติมสต็อก การ์ดในมือ และการตัดสต็อกเสีย
- ถอนเงินจ่ายแล้ว 2 ครั้ง และคำขอใหม่ 1 รายการ
- ยอดชดเชย +150

**TCG Corner** (One Piece และ Pokémon):
- คำขอคืนสินค้าใหม่ (Charizard ex ×2 ของ Demo Buyer) ลองอนุมัติหรือปฏิเสธ
- คืนสินค้าที่รับของแล้ว (Zapdos)
- listing ที่พักขายไว้
- ตัวเลือกจัดส่ง 3 แบบ (ปิดไว้ 1 แบบ)

**Mint Vault** (การ์ดเกรด PSA, BGS, CGC):
- คืนสินค้ารอผู้ซื้อส่งกลับ (Mega Kangaskhan)
- ถอนเงินครบ 4 สถานะ (จ่ายแล้ว ปฏิเสธ โอนไม่สำเร็จ จ่ายแล้ว)

**Demo Seller Shop** (Magic และ Yu-Gi-Oh!):
- ออเดอร์กำลังเตรียม (Vivi ของ Nattapong) ลองจัดส่ง
- listing ที่ปิดถาวร 1 รายการ และร่าง 1 รายการ
- ราคาอัตโนมัติ (Sol Ring)

**Grand Line Cards** (One Piece, Digimon, Union Arena, Dragon Ball):
- ออเดอร์ชำระแล้ว (Sayo) ลองรับออเดอร์และจัดส่ง
- ออเดอร์ส่งแล้ว 1 รายการ และส่งถึงแล้ว 1 รายการ
- คืนสินค้ากำลังส่งกลับ (Goku GT) ลองกดรับของคืน

### ผู้ซื้อ

| อีเมล | ทดสอบอะไร |
| --- | --- |
| `mai@example.com` | ดูรายละเอียดด้านล่าง |
| `ton@example.com` | ดูรายละเอียดด้านล่าง |
| `buyer@example.com` | ดูรายละเอียดด้านล่าง |
| `ploy@example.com` | ดูรายละเอียดด้านล่าง |
| `nattapong@example.com` | ดูรายละเอียดด้านล่าง |
| `locked@example.com` | ใส่รหัสผิดเกินกำหนด บัญชีถูกล็อก 15 นาทีหลังรัน seed หลังจากนั้นล็อกอินได้ |
| `suspended@example.com` | บัญชีถูกระงับ ล็อกอินไม่ได้ |
| `deactivated@example.com` | บัญชีปิดใช้งานแล้ว ล็อกอินไม่ได้ |

**Mai** (`mai@example.com`, 15 ออเดอร์):
- สลิปรอตรวจ (กล่อง FB07)
- ชำระแล้ว (Charizard ex SIR) ยกเลิกได้ภายใน 24 ชั่วโมงหลังจ่าย
- ส่งแล้ว (Mega Gardevoir) ลองกดยืนยันรับของ
- คืนสินค้าที่ร้านอนุมัติแล้ว (Mega Kangaskhan) ลองกรอกเลขพัสดุส่งกลับ
- ตะกร้า 2 ชิ้น โดยกล่อง Mega Evolution ราคาเปลี่ยนจาก 7,200 เป็น 6,990
- Wishlist 3 รายการ และคอลเลกชัน 21 ใบ (โชว์สาธารณะ 4 ใบ รวม Charizard PSA 10)
- ที่อยู่ 2 แห่ง

**Ton** (`ton@example.com`):
- ส่งถึงแล้ว (Gohan + Syn Shenron) ลองยืนยันรับของ
- ออเดอร์ที่ร้านหนึ่งเสร็จแล้ว อีกร้านยังส่งอยู่
- คืนเงิน 3 แบบ: กำลังโอน, คืนแล้ว และร้านยกเลิกพร้อมคืนเงิน
- ออเดอร์ที่ยกเลิกเองก่อนจ่าย
- คอลเลกชัน 28 ใบ

**Demo Buyer** (`buyer@example.com`):
- ออเดอร์ยังไม่จ่าย (Niv-Mizzet) ลองจ่ายภายใน 1 ชั่วโมงหลังรัน seed
- ชำระแล้ว (Sayo) และส่งแล้ว (กล่อง Mega Evolution)
- ขอคืนสินค้ารอร้านตอบ (Charizard ex ×2)

**Ploy** (`ploy@example.com`):
- ออเดอร์ 2 ร้านในใบเดียว: ร้านหนึ่งกำลังเตรียม อีกร้านชำระแล้ว
- คืนสินค้ากำลังส่งกลับ (Goku GT)
- ออเดอร์ที่หมดเวลาชำระ
- ตะกร้ามี Umbreon PSA 10 (฿95,000)
- คอลเลกชัน 27 ใบ

**Nattapong** (`nattapong@example.com`):
- ออเดอร์กำลังเตรียม (Vivi)
- คืนสินค้าที่ร้านรับของแล้ว รอแอดมินโอนคืน
- คืนเงินเต็มจำนวน 2 รายการ (หนึ่งในนั้นเป็นการ์ดปลอมจากร้านที่ถูกระงับ)
- สลิปถูกปฏิเสธจนออเดอร์ถูกยกเลิก
- ตะกร้ามีสินค้าจากร้านที่พักร้อน (ซื้อไม่ได้)

---

## 8. สิ่งที่ควรรู้

- **วันที่สัมพันธ์กับเวลารัน seed:** วันที่ทั้งหมดนับย้อนจากตอนรัน seed เช่น "สั่งเมื่อ 3 วันก่อน" รันวันไหนก็ดูเหมือนข้อมูลเพิ่งเกิด
- **บางอย่างเปลี่ยนเองตามเวลา เหมือนระบบจริง:**
  - ออเดอร์ที่ยังไม่จ่ายของ `buyer@example.com` จะถูกยกเลิกอัตโนมัติประมาณ 1 ชั่วโมงหลังรัน seed
  - ออเดอร์ที่ส่งแล้วจะถูกปิดอัตโนมัติเมื่อครบ 7 วันนับจากวันส่ง (ราว 2–3 วันหลังรัน seed)
  - ปุ่มยกเลิกของ Mai ใช้ได้แค่ช่วงเวลาที่ระบบกำหนด
- **อยากได้สถานะเริ่มต้นใหม่:** รัน seed อีกรอบพร้อม `-Reset`
- **ภาพจริงไม่ได้เก็บใน git:** สคริปต์ดาวน์โหลดจากเว็บของผู้ผลิตเข้า MinIO ในเครื่องแต่ละคน ส่วนเอกสารการเงินและรูปหลักฐานเป็นภาพจำลองทั้งหมด ไม่ใช่เอกสารจริง

---

## 9. แก้ปัญหาที่เจอบ่อย

| อาการ | สาเหตุและวิธีแก้ |
| --- | --- |
| `This database already has data...` | DB มีข้อมูลอยู่แล้ว รันใหม่พร้อม `-Reset` / `--reset` / `RESET=1` |
| `database ... is missing the latest tables` | ยังไม่ได้รัน API ด้วยโค้ดล่าสุด ทำขั้นที่ 4 ก่อน |
| `container pegasus-tcg-postgres is not running` | ยังไม่ได้เปิด container ทำขั้นที่ 3 |
| `password authentication failed` ตอนรัน API | พอร์ต 5432 ชนกับ PostgreSQL ที่ติดตั้งในเครื่อง ดูหมายเหตุในขั้นที่ 2 |
| `running scripts is disabled on this system` | รันผ่าน `powershell -ExecutionPolicy Bypass -File ...` ตามขั้นที่ 5 |
| หน้าเว็บไม่มีรูป หรือเห็นรูป "ยังโหลดรูปไม่สำเร็จ" | รัน seed อีกรอบ (ไม่ใส่ `--skip-images`) และเช็กว่า `MINIO_PUBLIC_ENDPOINT=http://localhost:9000` |
| ล็อกอินแล้วเห็นข้อมูลแปลก ๆ หรือโดนเด้งออก | ออกจากระบบหรือล้างข้อมูลเว็บไซต์ แล้วล็อกอินใหม่ (ต้องทำหลัง `-Reset` ทุกครั้ง) |
| `make: command not found` | ใช้คำสั่ง PowerShell หรือ bash ในขั้นที่ 5 แทน |
