# TCG-390 · Testing BE-02 — Catalog, Inventory, Cart & Order/Checkout

เอกสารนี้อธิบายเทสทั้งหมดที่เขียนใน ticket **TCG-390** (Backend Dev B) ว่าแต่ละไฟล์เทสอะไร ใช้เทคนิคอะไร รันยังไง และตรงไหนที่โค้ดจริงต่างจาก Testing Strategy

- **Branch:** `feat/TCG-390-Testing-BE-02`
- **Requirement ที่ครอบคลุม:** CR2 (Catalog), CR3 (Cart), CR4 (Order/Checkout), CR7 (Order lifecycle, Timeout, Escrow)
- **กฎที่ยึด:** ไม่แตะ `src/main/**` เลย แก้แค่ `src/test/**` และเพิ่ม test dependency ของ Cucumber ใน `build.gradle` (เป็น `testImplementation` ทั้งหมด)
- **หลักการเขียน:** ทุกเทส assert ตาม **พฤติกรรมของโค้ดจริง** ถ้าต่างจากเอกสาร จะเขียนเหตุผลไว้ในเทสและในหัวข้อ [ส่วนที่โค้ดต่างจากเอกสาร](#7-ส่วนที่โค้ดต่างจากเอกสาร-findings)

---

## 1. สรุปตัวเลข

| ประเภท | จำนวน (ตามที่ Gradle นับ) | เครื่องมือ |
|---|:---:|---|
| Unit | 53 | JUnit 5 + Mockito |
| API / Controller | 23 | MockMvc (standalone) |
| Integration | 73 | Spring Boot Test + Testcontainers (PostgreSQL 17) |
| Concurrency (API + Integration) | 30 รอบ | JDK `HttpClient` + `ExecutorService` + `CountDownLatch` |
| Acceptance (BDD) | 18 scenarios | Cucumber-JVM 8 |
| **รวมที่เพิ่ม** | **197** | ทั้งโปรเจกต์ 431 → 628 เทส: ผ่าน 625, ข้าม 3 (bug ที่รู้แล้ว ดูข้อ 8), fail 0 |

Coverage ของ package `service/` (JaCoCo): **Line 87.8% / Branch 76.4%** ผ่านเกณฑ์ Plan A (≥ 80% / ≥ 75%) ก่อนเริ่มงานอยู่ที่ Line 82.7% / Branch 71.2%

---

## 2. วิธีรัน

### สิ่งที่ต้องมี
1. **Docker Desktop เปิดอยู่** เพราะ Integration, Concurrency และ Cucumber สร้าง Postgres เองผ่าน Testcontainers
2. **Postgres ของ docker-compose รันอยู่** เพราะตอน compile ตัว build จะ migrate แล้ว generate jOOQ จาก DB นี้
3. **ไฟล์ `.env`** มี `JWT_SECRET`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`

### คำสั่ง (รันใน `pegasus-tcg-api`, PowerShell ใช้ `.\gradlew.bat` แทน `./gradlew`)

รันเทสทั้งโปรเจกต์ (รวม Cucumber ด้วย เพราะ `CucumberTestRunner` เป็น JUnit `@Suite` ที่ task `test` เจอเองอัตโนมัติ):
```bash
./gradlew test
```

รันเฉพาะเทสของ TCG-390 ทั้งหมดในคำสั่งเดียว:
```bash
./gradlew test --tests '*AddressServiceTest' --tests '*CartServiceTest' --tests '*LedgerServiceTest' --tests '*PlatformSettingServiceTest' --tests '*CatalogProductControllerTest' --tests '*AddressControllerTest' --tests '*CatalogSearchServiceIntegrationTest' --tests '*ListingBrowseServiceIntegrationTest' --tests '*CheckoutServiceIntegrationTest' --tests '*OrderStateTransitionIntegrationTest' --tests '*CheckoutRaceConditionIntegrationTest' --tests '*CucumberTestRunner'
```

รันเฉพาะ Cucumber:
```bash
./gradlew test --tests '*CucumberTestRunner'
```

รันซ้ำทั้งที่โค้ดไม่เปลี่ยน (เช่น stress test ของ concurrency) ให้เติม `--rerun`:
```bash
./gradlew test --tests '*CheckoutRaceConditionIntegrationTest' --rerun
```

**จาก IntelliJ:** ในแท็บ Project กด `Ctrl` ค้างแล้วเลือกไฟล์เทส คลิกขวา แล้วเลือก **Run Tests**

### รายงาน (อยู่ใน `build/reports/`, คลิกขวาแล้วเลือก Open In → Browser)

| รายงาน | ไฟล์ |
|---|---|
| ผลเทส | `build/reports/tests/test/index.html` |
| Cucumber (Living Documentation) | `build/reports/cucumber/cucumber.html` |
| Coverage (ถ้ารันพร้อม JaCoCo) | `build/reports/jacoco/test/html/index.html` |

> ถ้า Gradle ขึ้น `Unable to establish loopback connection` ให้ตั้งค่า
> `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/uds` (สร้างโฟลเดอร์ `C:\uds` ก่อน) แล้วรันใหม่

---

## 3. โครงสร้างไฟล์

```
src/test/
├── README-TCG-390.md                              ← ไฟล์นี้
├── java/com/pegasus/pegasustcgapi/
│   ├── service/
│   │   ├── AddressServiceTest.java                   Unit
│   │   ├── CartServiceTest.java                      Unit   (เพิ่มกลุ่ม ECC ในไฟล์เดิม)
│   │   ├── LedgerServiceTest.java                    Unit
│   │   ├── PlatformSettingServiceTest.java           Unit
│   │   ├── CatalogSearchServiceIntegrationTest.java  Integration
│   │   ├── ListingBrowseServiceIntegrationTest.java  Integration (PWC)
│   │   ├── CheckoutServiceIntegrationTest.java       Integration
│   │   └── OrderStateTransitionIntegrationTest.java  Integration (ST-01..13, Timeout, Escrow)
│   ├── controller/
│   │   ├── CatalogProductControllerTest.java         API (MockMvc)
│   │   ├── AddressControllerTest.java                API (MockMvc)
│   │   └── CheckoutRaceConditionIntegrationTest.java Concurrency (HTTP จริง)
│   ├── acceptance/
│   │   ├── CucumberTestRunner.java                   ตัวรัน Cucumber (JUnit Platform Suite)
│   │   ├── CucumberSpringConfiguration.java          ให้ Cucumber ใช้ Spring context เดียวกับ Integration
│   │   └── steps/MarketplaceSteps.java               Step Definitions
│   └── support/                                      ตัวช่วย ไม่ใช่เทส
│       ├── PostgresIntegrationTest.java              base class ของ Integration ทุกตัว
│       ├── IntegrationTestConfig.java
│       ├── TestData.java                             สร้างข้อมูลทดสอบ
│       ├── MutableClock.java                         นาฬิกาที่เลื่อนเวลาได้
│       └── ApiClient.java                            ยิง HTTP จริงพร้อม JWT
└── resources/features/
    ├── browse_and_cart.feature                       CR2, CR3
    └── place_order.feature                           CR4, CR7
```

---

## 4. รายละเอียดแต่ละไฟล์

### 4.1 Unit Test: เทสตรรกะของ service อย่างเดียว (mock repository ทั้งหมด, ไม่มี DB)

#### `AddressServiceTest` (9 เคส) · CR4 US-18 สมุดที่อยู่
| กลุ่ม | เคส |
|---|---|
| Reading | ดึงเฉพาะที่อยู่ของเจ้าของ · id ของคนอื่นได้ `ADDRESS_NOT_FOUND` |
| Creating | ที่อยู่แรกเป็น default shipping และ billing อัตโนมัติ · ที่อยู่ถัดไปที่ไม่ใช่ default ไม่ไปแตะตัวเดิม · ตั้ง default shipping ใหม่แล้วตัวเก่าถูกยกเลิก (billing ไม่ถูกแตะ) |
| Updating/Deleting | แก้ของตัวเองได้ · แก้ของคนอื่นได้ 404 และไม่แตะ default · ลบเป็น soft delete · ลบของคนอื่นได้ 404 |

#### `CartServiceTest`, กลุ่ม **ECC: quantity vs stock** (14 เคส) · CR3 US-14/15
สต็อกในเทส = 10 แบ่ง Equivalence Class ได้ 3 กลุ่ม และเทสที่ขอบของแต่ละกลุ่ม

| Partition | ค่าที่เทส | ผลที่ต้องได้ |
|---|---|---|
| qty ≤ 0 (Invalid) | 0, -1, `Integer.MIN_VALUE` | 400 `VALIDATION_FAILED` และยังไม่มีการสร้างตะกร้า |
| 1 ≤ qty ≤ stock (Valid) | 1, 5, 10, ไม่ส่ง qty (= 1) | ใส่ตะกร้าได้ |
| qty > stock (Invalid) | 11, 50, `Integer.MAX_VALUE` | 409 `INSUFFICIENT_STOCK` |
| แก้จำนวนในตะกร้า | 0, -3 / = 10 / = 11 | 400 / สำเร็จ / 409 |

#### `LedgerServiceTest` (17 เคส) · CR6/CR7 ค่าคอมมิชชัน
| กลุ่ม | เคส |
|---|---|
| ปัดเศษ 2 ตำแหน่ง (HALF_UP) | **3% ของ 175.50 = 5.27** · 5% ของ 0.01 = 0.00 · 5% ของ 0.10 = 0.01 · 3% ของ 33.33 = 1.00 · 7.5% ของ 12.34 = 0.93 · 3.5% ของ 999,999.99 = 35,000.00 และค่าที่ได้มีทศนิยม 2 ตำแหน่งเสมอ |
| ECC ของ rate | 0% ได้ 0 · 3.5% · 100% ได้เท่ายอดขาย · **−5%, −0.01%, 100.01%, 105% ต้องถูกปฏิเสธ** (`@Disabled` รอแก้ bug ดูข้อ 8) |
| ไม่มีอะไรให้คิด | ยอด ≤ 0 หรือ null ได้ `NONE` โดยไม่ไปอ่านค่า rate |
| Escrow release | เรียก `release()` แล้วต้องไม่ error |

#### `PlatformSettingServiceTest` (13 เคส) · CR6 ค่าที่แอดมินตั้งได้
| กลุ่ม | เคส |
|---|---|
| อ่านค่า | ตัวเลขมีหรือไม่มีเครื่องหมายคำพูดแบบ JSON ก็อ่านได้ · แปลงเป็นนาที ชั่วโมง วัน ได้ · ไม่มี setting นั้นได้ `SETTING_NOT_FOUND` · ค่าที่ไม่ใช่ตัวเลขทำให้ error ทันที |
| แก้ commission rate | 0, 3, 3.5, 100 บันทึกได้ · key ที่ไม่มีได้ 404 และไม่มีการเขียน · ค่าที่ไม่ใช่ JSON ได้ 400 · **ค่าที่อยู่นอก 0–100 หรือไม่ใช่ตัวเลขต้องได้ 400** (`@Disabled` รอแก้ bug ดูข้อ 8) |

### 4.2 API Test (MockMvc): ยิง HTTP เข้า controller โดย service เป็น mock

#### `CatalogProductControllerTest` (9 เคส) · CR2
- `GET /catalog/products` ส่ง `q`, `gameId`, `categoryId`, `productType`, `sort`, `page`, `size` ไปถึง service ครบ
- ไม่ส่งพารามิเตอร์เลย ได้ค่า default (page 0, size 20, ดูได้เฉพาะสินค้า active)
- ตัวกรอง `attr.*` ส่งผ่านไปถึง service
- พารามิเตอร์ผิดชนิด (`productType`, `gameId`, `page`) ได้ 400 ที่บอกชื่อ field
- service ปฏิเสธ (เช่น sort ผิด) ได้ 400 ตามนั้น
- เปิดหน้าสินค้าด้วย slug ได้ 200 · สินค้าไม่มีหรือปิดขายแล้วได้ 404

#### `AddressControllerTest` (14 เคส) · CR4 US-18
- `POST /addresses` ถูกต้องได้ **201** และ countryCode default เป็น `TH`
- Validation ได้ **400**: postal code ไม่ครบ 5 หลักหรือมีตัวอักษร · เบอร์โทรผิด · ชื่อว่าง · จังหวัดว่าง · country code ผิด · มีขึ้นบรรทัดใหม่ในที่อยู่
- JSON พังได้ 400 `MALFORMED_REQUEST`
- ดูที่อยู่ของคนอื่นได้ 404 (ไม่ใช่ 403 เพื่อไม่บอกว่ามีอยู่จริง) · PUT ได้ 200 · DELETE ได้ 200 / 404
- ไม่ login ได้ **401** `UNAUTHENTICATED`

### 4.3 Integration Test: ต่อ PostgreSQL จริงผ่าน Testcontainers

ทุกไฟล์ extends `support/PostgresIntegrationTest` จึงใช้ container และ Spring context ตัวเดียวกัน (เปิดครั้งเดียว) ปิด cron ทั้งหมดระหว่างเทส และใช้ `MutableClock` เลื่อนเวลาเอง

#### `CatalogSearchServiceIntegrationTest` (12 เคส) · CR2 US-08/10
- ค้นชื่อแบบไม่สนตัวพิมพ์เล็ก-ใหญ่และค้นด้วยบางส่วนของชื่อได้ (`Charizard`, `charizard`, `CHARIZARD EX`, `izard`, `  chariz  `)
- ค้นชื่ออย่างเดียวโดยไม่เลือกเกมก็เจอ และได้ rarity กับจำนวน variant กลับมาถูก
- อักขระ `%`, `_`, `\` ถูกค้นแบบตัวอักษรตรงๆ ไม่ถูกตีความเป็น wildcard
- กรองตามเกมแยกเกมได้ถูก แม้การ์ดชื่อซ้ำกันข้ามเกม
- สินค้าที่ปิดขายแล้วคนทั่วไปมองไม่เห็น แต่แอดมินเห็น
- ไม่เจอผลลัพธ์ได้หน้าว่าง ไม่ใช่ error

#### `ListingBrowseServiceIntegrationTest` (17 เคส) · CR2 US-08/13 · **PWC**
ดูข้อ 5.2 สำหรับตาราง PWC และมีเพิ่ม:
- listing ที่ขายหมดยังเปิดหน้าของตัวเองได้ (สถานะ `SOLD_OUT`)
- เรียงตาม `price`, `price_desc`, `newest` · sort ที่ไม่รู้จักถูกปฏิเสธ
- `minPrice`/`maxPrice` รวมค่าขอบ · min > max ถูกปฏิเสธ
- หน้าร้านของผู้ขายแสดงเฉพาะของร้านนั้น · username ที่ไม่มีได้ `USER_NOT_FOUND`

#### `CheckoutServiceIntegrationTest` (7 เคส) · CR4 US-17/20, CR6/CR7
| เคส | เช็คอะไร |
|---|---|
| สั่งซื้อแล้วจองการ์ด | การ์ดเป็น `RESERVED` ตามจำนวน · `order_item_unit` ชี้การ์ดใบที่ถูกจองจริง · ตัวนับสต็อกถูกต้องและไม่ติดลบ · ตะกร้าว่าง · history เริ่มที่ `PENDING_PAYMENT` |
| ตะกร้าจาก 2 ร้าน | ได้ 1 sales order กับ 2 seller order และยอดแยกถูกต้อง |
| ค่าคอมถูกบันทึกค้างไว้ | 3% ของ 175.50 = **5.27** ผู้ขายได้ **170.23** และถ้าแอดมินเปลี่ยน rate ภายหลังออเดอร์เดิมไม่เปลี่ยน |
| ที่อยู่ถูก snapshot | แก้สมุดที่อยู่ภายหลังแล้วออเดอร์เดิมไม่เปลี่ยน |
| สต็อกหมดระหว่างทาง | ได้ 409 ไม่มีออเดอร์ค้างครึ่งๆ กลางๆ และตะกร้ายังอยู่ |
| ECC rate −5% | checkout ถูกปฏิเสธ ไม่มีออเดอร์ การ์ดยังขายอยู่ ตะกร้ายังอยู่ (ตอนนี้ DB CHECK เป็นตัวปฏิเสธ ถ้าแก้ bug แล้วเทสนี้ก็ยังผ่าน) |
| ECC rate 105% | checkout ต้องถูกปฏิเสธ และไม่มี sub-order ที่ยอดผู้ขายติดลบ (`@Disabled` รอแก้ bug ดูข้อ 8) |

#### `OrderStateTransitionIntegrationTest` (37 เคส) · CR7 US-40..49
| กลุ่ม | เคส |
|---|---|
| ตาราง ST-01..ST-13 | เช็คทั้ง 13 เคสกับ `SellerOrderStatus.canMoveTo` (ดูข้อ 5.3) |
| Valid moves ผ่าน service จริง | ST-01 จ่ายเงิน · ST-02 ผู้ซื้อยกเลิกก่อนจ่าย · ST-03 ยกเลิกตอน PREPARING ได้ · ST-04 ส่งของ (บันทึก tracking, การ์ดเป็น `SOLD`, `auto_complete_at` = +7 วัน) · PAID → SHIPPED ตรงได้ · ST-06 ผู้ซื้อยืนยันรับของ (ปล่อย escrow 1 ครั้งและการ์ดเข้า collection) |
| Invalid moves ได้ **409** และข้อมูลไม่เปลี่ยน | ST-09, ST-10, ST-11, ST-12, ST-13 และ ST-07/08 (after-sales ไม่มีทางไปต่อ) |
| **Payment Timeout 5 นาที** | `testTimeoutAutoRelease`: เกิน 5:01 นาทีแล้วได้ `CANCELLED` การ์ดกลับเป็น `LISTED` ตัวนับกลับมาครบ และ history บันทึก `changed_by = NULL` · ที่ 4:59 ยังไม่ยกเลิก · ออเดอร์ที่จ่ายแล้วไม่ถูกยกเลิก · รัน sweep 2 ครั้งได้ผลเดียว · **Atomic**: ถ้าคืนสต็อกพังการยกเลิกก็ rollback ด้วยแล้วรอบถัดไปทำซ้ำได้ · การ์ดที่คืนมาคนอื่นซื้อต่อได้ทันที |
| **Escrow** | `testEscrowRelease`: ไม่ปล่อยเงินตอน PAID หรือ SHIPPED ปล่อย **ครั้งเดียว** ตอนผู้ซื้อยืนยัน และยืนยันซ้ำได้ 409 · ออเดอร์ที่ยังไม่จ่ายยืนยันไม่ได้ · ออเดอร์ที่ถูกยกเลิกไม่ปล่อยเงิน · ยอดผู้ขายคงเดิมตลอด lifecycle · ผู้ซื้อไม่ยืนยันก็ปิดออเดอร์เองหลังครบระยะ escrow |

> สถานะที่ไม่มี endpoint พาไปถึง (PREPARING, DELIVERED, REFUNDED) ถูกเตรียมโดยเขียนลง DB ตรงๆ ในขั้น arrange ของเทส

### 4.4 Concurrency: `CheckoutRaceConditionIntegrationTest` (30 รอบ) · [CRITICAL] CR4 US-17

ยิง `POST /api/v1/checkout` ผ่าน HTTP จริงพร้อม JWT จริง ทุก thread ถูกพักไว้ที่ `CountDownLatch` แล้วปล่อยพร้อมกันผ่าน `ExecutorService` และแต่ละสถานการณ์เป็น `@RepeatedTest` (stress loop)

| สถานการณ์ | รอบ | ผลที่ต้องได้ |
|---|:---:|---|
| 2 คนแย่งการ์ดใบสุดท้าย | 20 | **201 หนึ่งคน, 409 หนึ่งคน** · available = 0, reserved = 1 · มีออเดอร์ 1 ใบ · สต็อกไม่ติดลบ |
| 8 คนแย่งการ์ด 3 ใบ | 5 | สำเร็จ 3 คนพอดี ถูกปฏิเสธ 5 คน ไม่ขายเกินสต็อก |
| กดซ้ำด้วย Idempotency-Key เดิม | 5 | 201 + 200 (replay) ได้ออเดอร์เดียว และการจองของ request ที่แพ้ถูก rollback |

ทดสอบความเสถียรแล้วโดยรันติดกัน 4 ครั้ง (120 รอบ) ผ่านทั้งหมด

### 4.5 Acceptance (Cucumber): ยิง HTTP จริงผ่าน `support/ApiClient`

#### `browse_and_cart.feature` · @CR2 @CR3 (11 scenarios)
- ค้นหาการ์ดด้วยบางส่วนของชื่อ
- ดูตลาดของเกมเดียว
- กรองตามสภาพการ์ด NM / LP (Scenario Outline) · สภาพที่ไม่มีใครขาย (MP) ได้หน้าว่าง
- ผู้ใช้ที่ยังไม่ login หยิบของครั้งแรกได้ cart session key
- **ECC**: ใส่ 1 และ 5 ใบได้ · ใส่ 0 และ −1 ได้ 400 · ใส่ 6 ใบได้ 409 และตะกร้ายังว่าง

#### `place_order.feature` · @CR4 @CR7 (7 scenarios)
- Checkout แล้วการ์ดถูกจอง (201, `PENDING_PAYMENT`, available 0 / reserved 1)
- ไม่ส่ง Idempotency-Key ได้ 400 และไม่มีการจอง
- ไม่จ่ายภายใน 5 นาทีได้ `CANCELLED` การ์ดกลับมาขาย และถูกบันทึกว่าระบบเป็นคนยกเลิก
- จ่ายทันเวลาแล้ว sweep ไม่ยกเลิก
- จ่าย → ส่ง (ยังไม่ปล่อย escrow) → ยืนยันรับได้ `COMPLETED` ค่าคอม 5.27 ผู้ขายได้ 170.23 และปล่อย escrow ครั้งเดียว
- ออเดอร์ที่ยกเลิกแล้วจ่ายไม่ได้ (409) · ออเดอร์ที่ยังไม่จ่ายส่งของไม่ได้ (409)

---

## 5. เทคนิคการออกแบบ Test Case

### 5.1 ECC (Equivalence Class Partitioning)
| ตัวแปร | Invalid (ต่ำ) | Valid | Invalid (สูง) | ไฟล์ |
|---|---|---|---|---|
| Quantity vs Stock | qty ≤ 0 ได้ 400 | 1..stock ใส่ได้ | qty > stock ได้ 409 | `CartServiceTest`, `browse_and_cart.feature` |
| Commission Rate | −5%, −0.01% ต้องถูกปฏิเสธ | 0%, 3%, 3.5%, 100% | 100.01%, 105% ต้องถูกปฏิเสธ | `LedgerServiceTest`, `PlatformSettingServiceTest`, `CheckoutServiceIntegrationTest` |

### 5.2 PWC (Pairwise): Game × Rarity × Condition × In-stock
ถ้าทำครบทุกกรณีต้องเทส 3 × 3 × 3 × 2 = **54 เคส** แต่ใช้ L9 orthogonal array เหลือ **9 เคส** และทุกคู่ของค่าจาก 2 ตัวแปรใดก็ได้ยังถูกเทสอย่างน้อย 1 ครั้ง

| # | Game | Rarity | Condition | In-stock |
|:---:|---|---|:---:|:---:|
| P1 | Pokémon | Common | NM | ✔ |
| P2 | Pokémon | Secret Rare | LP | ✘ |
| P3 | Pokémon | Ultra Rare | MP | ✔ |
| P4 | Yu-Gi-Oh | Common | LP | ✔ |
| P5 | Yu-Gi-Oh | Secret Rare | MP | ✔ |
| P6 | Yu-Gi-Oh | Ultra Rare | NM | ✘ |
| P7 | One Piece | Common | MP | ✘ |
| P8 | One Piece | Secret Rare | NM | ✔ |
| P9 | One Piece | Ultra Rare | LP | ✔ |

- Game และ Condition เป็น filter ของ `GET /listings`
- In-stock คือ listing ACTIVE หรือขายหมดแล้ว (ตลาดแสดงเฉพาะ ACTIVE)
- โค้ดไม่มี filter rarity จึงเช็คว่า rarity ของการ์ดที่ได้กลับมาถูกต้องแทน
- ทั้ง 9 แถวถูก seed พร้อมกัน แต่ละแถวจึงมีอีก 8 แถวเป็นตัวหลอก

### 5.3 State Transition (ST-01 ถึง ST-13)
| ID | จาก → ไป | เอกสาร | โค้ดจริง | วิธีเทส |
|:---:|---|:---:|:---:|---|
| ST-01 | PENDING_PAYMENT → PAID | Valid | Valid | ผ่าน service |
| ST-02 | PENDING_PAYMENT → CANCELLED | Valid | Valid | ผ่าน timeout sweep และการยกเลิกของผู้ซื้อ |
| ST-03 | PAID → PREPARING | Valid | Valid | ตาราง (ไม่มี endpoint) |
| ST-04 | PREPARING → SHIPPED | Valid | Valid | ผ่าน service |
| ST-05 | SHIPPED → DELIVERED | Valid | Valid | ตาราง (ไม่มี endpoint) |
| ST-06 | DELIVERED → COMPLETED | Valid | Valid | ผ่าน service |
| ST-07 | DELIVERED → RETURN_REQUESTED | Valid | **Invalid** | ตาราง (after-sales ยังไม่มีในโค้ด) |
| ST-08 | RETURN_REQUESTED → REFUNDED | Valid | **Invalid** | ตาราง (after-sales ยังไม่มีในโค้ด) |
| ST-09 | COMPLETED → PENDING_PAYMENT | Invalid | Invalid | ตาราง + service ได้ 409 |
| ST-10 | CANCELLED → PAID | Invalid | Invalid | service ได้ 409 |
| ST-11 | PENDING_PAYMENT → SHIPPED | Invalid | Invalid | service ได้ 409 |
| ST-12 | DELIVERED → PREPARING | Invalid | Invalid | ตาราง + service ได้ 409 |
| ST-13 | REFUNDED → COMPLETED | Invalid | Invalid | service ได้ 409 |

### 5.4 RBT (Risk-Based Testing)
เวลาส่วนใหญ่ลงไปกับจุดเสี่ยงสูง (🔥): Concurrency (30 รอบ + stress), Timeout (6 เคส รวมเรื่อง atomic), Commission/Escrow (ข้อ 4.1, 4.3, 4.5) ส่วน Address และ Catalog ทำ Happy Path + Validation

---

## 6. Requirement Traceability (CR2, CR3, CR4, CR7)

| Requirement | เทส | สถานะ |
|---|---|:---:|
| CR2 US-08/10/13 ค้นหา / กรอง / สภาพการ์ด | `CatalogSearchServiceIntegrationTest`, `ListingBrowseServiceIntegrationTest`, `CatalogProductControllerTest`, `browse_and_cart.feature` | Implemented |
| CR3 US-14/15 ใส่ตะกร้า / ปรับจำนวน | `CartServiceTest` (ECC), `browse_and_cart.feature` | Implemented |
| CR4 US-17 สั่งซื้อและตัดสต็อก | `CheckoutServiceIntegrationTest`, `CheckoutRaceConditionIntegrationTest`, `place_order.feature` | Implemented |
| CR4 US-18/20 สมุดที่อยู่และ snapshot | `AddressServiceTest`, `AddressControllerTest`, `CheckoutServiceIntegrationTest#addressIsSnapshotted` | Implemented |
| CR7 US-40..44 Order lifecycle | `OrderStateTransitionIntegrationTest` | Implemented |
| CR7 US-45 Timeout + คืนสต็อก | `OrderStateTransitionIntegrationTest#testTimeoutAutoRelease`, `place_order.feature` | Implemented |
| CR7 US-46/47 Escrow | `OrderStateTransitionIntegrationTest#testEscrowRelease`, `place_order.feature` | Implemented (payout ยังไม่มีในโค้ด) |
| CR7 US-48/49 Return / Refund | `OrderStateTransitionIntegrationTest` (ST-07/08) | Blocked: after-sales ยังไม่มีในโค้ด |

---

## 7. ส่วนที่โค้ดต่างจากเอกสาร (Findings)

| # | เรื่อง | เอกสาร / Ticket | โค้ดจริง | ควรทำอะไร |
|:---:|---|---|---|---|
| 1 | **Bug:** Commission rate ไม่มี validation | −5% และ 105% ต้องได้ 400 | −5%: DB CHECK ปฏิเสธ ทำให้ **checkout ทั้งแพลตฟอร์มล้ม** · 105%: รับออเดอร์และผู้ขายได้เงินติดลบ | ดูข้อ 8 |
| 2 | Escrow | สถานะ `ESCROW_HELD` และห้ามเบิก payout ก่อนยืนยัน | ไม่มีโค้ดส่วนไหนเขียนตาราง `escrow_hold` · `LedgerService.release()` แค่เขียน log · ยังไม่มี payout | เทสได้แค่ "ไม่ปล่อยเงินก่อนยืนยัน" |
| 3 | Optimistic Locking | ใช้ optimistic lock | ใช้ pessimistic lock (`FOR UPDATE SKIP LOCKED`) | ผลที่ต้องได้เหมือนกัน (201 / 409, สต็อก ≥ 0) |
| 4 | Status ของ race | คนแรกได้ 200 | checkout สำเร็จได้ **201 Created** | — |
| 5 | Invalid transition | 400 | **409** `ORDER_STATUS_TRANSITION` | แก้เอกสาร |
| 6 | ST-07 / ST-08 | Valid | after-sales ยังไม่มีในโค้ด | รอฟีเจอร์ |
| 7 | PAID → PREPARING, SHIPPED → DELIVERED | มี action | ไม่มี endpoint | รอฟีเจอร์ |
| 8 | Timeout | 5 นาที | ค่า default ใน migration คือ **60 นาที** | ตั้งค่า `order.payment_timeout_minutes` เป็น 5 |
| 9 | สถานะการ์ดที่คืน | `AVAILABLE` | `LISTED` | แก้เอกสาร |
| 10 | Rarity filter | ใช้ใน PWC | ไม่มี filter rarity | ใช้ rarity เป็นข้อมูลของการ์ดแทน |
| 11 | ชื่อ class | `CatalogService`, `OrderService` | `CatalogSearchService` + `ListingBrowseService`, `CheckoutService` + `OrderLifecycleService` + `LedgerService` | ตั้งชื่อไฟล์เทสตาม class จริง |

ข้อสังเกตเพิ่มเติม (ยังไม่เคยเกิดใน 120 รอบ): ถ้ากดสั่งซื้อซ้ำด้วย Idempotency-Key เดิม แล้ว request ที่สองอ่านตะกร้าหลังจาก request แรก commit ไปแล้ว อาจได้ `409 CART_EMPTY` แทนการ replay

---

## 8. Known bug ที่มีเทสรออยู่ (`@Disabled`)

เทสที่ต้องแก้ production code ถึงจะผ่าน ถูกเขียนให้ **assert พฤติกรรมที่ถูกต้อง** แล้วใส่ `@Disabled` ไว้ CI จึงยังเขียว และเทสไม่ได้รับรองพฤติกรรมที่ผิด พอ Code Owner แก้เสร็จ แค่ลบ `@Disabled` ก็ใช้เป็นเทสยืนยันได้เลย

**Bug: `commission.default_rate` ไม่ถูกตรวจว่าอยู่ในช่วง 0–100**
- **ผลกระทบ:** แอดมินตั้งค่าติดลบได้ ทำให้ checkout ของทุกคนล้ม (DB CHECK ปฏิเสธ) · ตั้งเกิน 100 ได้ ทำให้ผู้ขายได้เงินติดลบ
- **ทางแก้ที่แนะนำ** (Code Owner ต้องทำ เพราะเป็น `src/main`): ตรวจค่าใน `PlatformSettingService.update` (ต้องเป็นตัวเลข 0–100 ไม่งั้นได้ 400 `VALIDATION_FAILED`) และกันซ้ำใน `LedgerService.quoteCommission`
- **เทสที่รออยู่** (ใช้ข้อความเดียวกันจาก `LedgerServiceTest.KNOWN_BUG_COMMISSION_RATE`):

| เทส | ตรวจอะไร |
|---|---|
| `PlatformSettingServiceTest#rateOutsideZeroToHundredIsRejected` | −5, −0.01, 100.01, 105, "abc" ได้ 400 และไม่ถูกบันทึก |
| `LedgerServiceTest#rateOutsideZeroToHundredIsRefused` | rate นอก 0–100 ต้องไม่ถูกคำนวณเป็นค่าคอม |
| `CheckoutServiceIntegrationTest#commissionAboveHundredRefusesTheOrder` | checkout ที่ 105% ต้องถูกปฏิเสธและ rollback |

ทดสอบแล้ว: ถ้าปิด `@Disabled` ทั้ง 10 เคสนี้ fail ตามคาด (แปลว่าเทสจับ bug ได้จริง) และไม่มีเทสอื่นพัง

ถ้าอยากลองรันเทสที่ถูก `@Disabled` ในเครื่อง ให้สร้างไฟล์ `src/test/resources/junit-platform.properties` ชั่วคราว ใส่บรรทัด `junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition` แล้วรัน (อย่า commit ไฟล์นี้)
