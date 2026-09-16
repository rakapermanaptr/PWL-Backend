# PRD Backend — REST API Prima Wash (Kasir Prima Laundry)

| | |
|---|---|
| **Dokumen** | Product Requirements Document — Backend & REST API v1 |
| **Status** | Draft 1.2 — siap direview tim backend |
| **Tanggal** | 11 September 2026 · revisi 14 & 15 September 2026 |
| **Perubahan 1.1** | Login tanpa aktivasi perangkat: layar login publik, "pilih cabang → ketik PIN"; tablet dikenali dari ID instalasi (`X-Device-Id`). Keputusan owner — lihat §12.1 dan `docs/prd-gaps-m1.md` di repo backend |
| **Perubahan 1.2** | Celah yang terisi saat implementasi M1–M2 disahkan owner: kode error `STAFF_REQUIRED`, `STAFF_PROOF_INVALID`, `CAPTURED_IN_FUTURE`, `DUPLICATE_TRANSACTION` dan teks pesan transport; bentuk respons semua endpoint M1–M2; cursor delta sync berbasis id transaksi (§8.10); aturan detail sync offline (§11.2); `staffProof` & serah terima tablet saat buka shift (§8.7); rencana rilis dengan integrasi Meta paling akhir (§15). Riwayat keputusan: `docs/prd-gaps-m1.md`, `docs/prd-gaps-m2.md` di repo backend |
| **Klien** | Aplikasi Android `Prima Wash` (tablet, landscape) — repo ini |
| **Sumber analisis** | Kode `:domain` (model, repository, use case), `:data` (Room, seeder), seluruh `:feature:*`; PRD produk *Sistem Kasir, Loyalty & WhatsApp* v1 (29 Agustus 2026); desain *Kasir Prima Laundry* v2 multi-cabang |
| **Pemilik produk** | Raka |

---

## 1. Ringkasan

Aplikasi kasir Prima Wash sudah berjalan **sepenuhnya lokal** di tablet (Room). Semua aturan bisnis — transaksi, poin
loyalty, shift & kas, status order, audit trail, impor customer — ada di modul `:domain` dan tersimpan di database
perangkat. Belum ada server: `HttpClient` Ktor terdaftar di DI tetapi tidak dipakai, dan `BASE_URL` masih placeholder.

Dokumen ini mendefinisikan **backend pusat** yang:

1. Menjadi **sumber data resmi (source of truth)** untuk semua cabang — PRD produk P0 #1 mensyaratkan
   "order tersimpan langsung ke database pusat".
2. Menjalankan ulang seluruh aturan bisnis secara **server-authoritative** (klien boleh menghitung untuk tampilan,
   server yang memutuskan).
3. Menerima **antrean transaksi offline** tanpa duplikasi (P0 #8).
4. Mengirim **notifikasi WhatsApp** lewat WhatsApp Cloud API resmi Meta, terkirim < 2 menit (P0 #4).
5. Menyediakan **autentikasi PIN per staff** yang aman, audit trail, dan laporan owner lintas cabang.

---

## 2. Hasil analisis project

### 2.1 Peta fitur klien → kebutuhan backend

| Menu | Modul | Role | Yang dibutuhkan dari backend |
|---|---|---|---|
| Login | `:feature:auth` | Semua | Daftar cabang + kasir aktif + status shift (sebelum login), login PIN per cabang |
| Kasir | `:feature:pos` | Semua | Price list aktif, reward aktif, rate poin, cari/daftar customer, simpan order (online) atau sync (offline), nomor order berikutnya |
| Order | `:feature:orders` | Semua | Daftar order per cabang / semua cabang (owner), filter status, pencarian ID/nama/no HP, majukan status |
| WA | `:feature:whatsapp` | Semua | Daftar notifikasi WA gagal, kirim ulang, tandai follow-up telepon, jumlah terkirim (delivery rate) |
| Kas | `:feature:shift` | Semua | Shift terakhir cabang + mutasi kas, verifikasi PIN, buka shift, kas masuk/keluar, hitung uang fisik, tutup shift |
| Customer | `:feature:customer` | Semua | Template CSV, validasi (preview), impor, laporan error |
| Laporan | `:feature:dashboard` | Owner | KPI harian, performa per cabang, grafik 7 hari, layanan terlaris, log audit 1/7/30 hari |
| Cabang | `:feature:branch` | Owner | Daftar cabang, omzet & transaksi hari ini, shift, kasir aktif, ubah target harian, aktif/nonaktif, pindah konteks cabang perangkat |
| Harga | `:feature:pricing` | Owner | Price list global, ubah harga massal, aktif/nonaktif, tambah layanan |
| Staff | `:feature:staff` | Owner | Daftar akun, tambah staff + PIN, reset PIN, aktif/nonaktif |
| Setting | `:feature:settings` | Owner | Rate poin, katalog reward (tambah, aktif/nonaktif) |
| Shell | `:app` | Semua | Ganti staff aktif (PIN), pindah cabang (owner), logout, antrean offline + sync |

### 2.2 Entitas yang sudah ada di klien

Sumber: `domain/model/*.kt` dan `data/local/entity/Entities.kt`.

| Entitas | Field penting | Catatan |
|---|---|---|
| `Branch` | `id, code (FMU/NRG/CPT), name, address, phone, hours, dailyTarget, active` | Order, kas, shift, audit milik satu cabang |
| `Staff` | `id, name, shortName, role (KASIR/OWNER), branchId?, pinHash, active, lastLoginAt` | Kasir terikat 1 cabang; owner `branchId = null` |
| `Customer` | `id, name, phone, points, optIn, visits, homeBranchId` | Global lintas cabang, no HP unik, poin berlaku di semua cabang |
| `ServiceItem` | `id, category, name, price, unit, step, active` | Price list global; `step` 0,5 untuk kg, 1 untuk lainnya |
| `LoyaltyRate` | `rupiahPerStep, pointsPerStep` | Default Rp10.000 = 100 poin, berlaku semua cabang |
| `Reward` | `id, name, cost (poin), value (Rp), note, usedCount, active` | Diskon rupiah saat redeem |
| `Order` + `OrderItem` | `id (FMU-0829-015), items (snapshot nama/harga/unit), subtotal, discount, total, reward, redeemed/earnedPoints, payment, status, waStatus, createdAt, statusChangedAt, staffId` | Harga di-snapshot saat transaksi |
| `order_events` | `orderId, branchId, type, createdAt` | Event layer P0 #3 |
| `Shift` + `CashEntry` | `openedByName, openedAt, closedAt, cashSales, transferSales, txCount, pointsIssued, countedCash, recap` / `kind (OPENING/CASH_IN/CASH_OUT/SALE), amount (bertanda)` | Satu shift terbuka per cabang |
| `AuditEntry` | `branchId?, createdAt, actor ("Siti N. (kasir)"), action (teks)` | Append-only |
| `WaFailure` | `branchId, customerName, phone, orderId, template, reason` | Hanya catatan gagal, belum ada outbox |
| `PendingTransaction` | `branchId, createdAt, customerId?, items, note, payment, total, staffId` | Antrean offline |
| `DailySales` | `branchId, epochDay, revenue, txCount` | Agregat untuk grafik owner |

### 2.3 Temuan penting yang harus diselesaikan backend

Temuan ini berasal dari membaca kode, bukan dari PRD produk. Masing-masing punya keputusan/solusi di bagian terkait.

| # | Temuan | Dampak | Solusi di dokumen ini |
|---|---|---|---|
| T1 | PIN di-hash **SHA-256 dengan salt statis** (`"primawash:$pin"`). Ruang PIN 4 digit = 10.000 kombinasi → hash bisa dibalik dalam milidetik. | Kebocoran DB = semua PIN bocor | §12.2: HMAC + pepper untuk lookup, Argon2id untuk verifikasi, rate limit per tablet, per akun & per alamat jaringan |
| T2 | Login **hanya dengan PIN** (tanpa pilih nama), jadi PIN harus unik di antara staff aktif. `ToggleStaffActiveUseCase` **tidak** mengecek bentrok PIN saat mengaktifkan ulang akun. | Dua staff aktif bisa punya PIN sama → login ambigu | §8.3: aktivasi ulang ditolak bila PIN bentrok; unique index parsial |
| T3 | `matchesActivePin()` dipakai keypad login untuk submit otomatis. Kalau diekspos sebagai API, ini jadi **oracle tebak PIN**. | Brute force tanpa login gagal tercatat | §8.1 & §14 (A5): endpoint ini tidak disediakan |
| T4 | Format ID order `KODE-MMdd-SEQ` **tanpa tahun** → `FMU-0829-015` tahun 2026 bentrok dengan 2027. | Primary key bentrok setelah 1 tahun | §7.3: `id` UUID + `number` tampilan, unik per (cabang, tanggal bisnis) |
| T5 | Sync offline memanggil ulang `PlaceOrderUseCase` yang **mewajibkan shift terbuka saat sync**, memakai **rate poin saat sync**, dan **berhenti di transaksi pertama yang gagal**. | Transaksi yang sudah dibayar bisa tertahan selamanya; poin dihitung dengan rate yang salah | §11: shift & rate mengikuti waktu transaksi, hasil per transaksi |
| T6 | Status WA di-set `TERKIRIM` **seketika** saat status order jadi "siap diambil" dan saat kirim ulang — belum ada pengiriman nyata. | Delivery rate di laporan tidak jujur | §10: outbox + worker + webhook status Meta |
| T7 | Keputusan kirim WA "siap diambil" memakai `order.waStatus` yang di-stamp saat transaksi. Customer yang opt-in belakangan tidak pernah dapat notifikasi. | Opt-in baru tidak berefek ke order berjalan | §8.6: cek opt-in customer saat event terjadi |
| T8 | Tidak ada **pembatalan/void order** maupun koreksi. | Salah input = selisih kas permanen | §17 keputusan terbuka (usulan P1) |
| T9 | Reward "Diskon Rp25.000" punya catatan "Minimum belanja Rp75.000" tetapi **tidak ditegakkan** di kode. | Reward bisa dipakai di bawah syarat | §7.2: field opsional `minSubtotal` |
| T10 | Log audit hanya menyimpan **teks nama** pelaku, tidak `staffId`. PRD P0 #12 meminta filter per staff. | Laporan per staff tidak bisa difilter | §7.2: `audit_log.staff_id` + `action_type` terstruktur |
| T11 | Metrik dashboard (`redemptionRate`, `deliveryRate`, top layanan) dihitung dari **semua order sepanjang masa**. | Angka tidak mencerminkan periode | §9.4: periode eksplisit (default 30 hari) |
| T12 | PRD produk menulis pilot **1 cabang** dan dashboard lintas cabang sebagai non-goal, tetapi aplikasi (desain v2) sudah **multi-cabang** dengan 3 cabang. | Skema harus siap multi-cabang | Backend multi-cabang sejak awal; cabang pilot dikendalikan flag `active` |

---

## 3. Tujuan & ruang lingkup

### 3.1 Tujuan backend

| # | Tujuan | Ukuran keberhasilan |
|---|---|---|
| G1 | Semua transaksi tersimpan di database pusat | 100% order di cabang pilot ada di server; selisih klien–server = 0 saat rekonsiliasi harian |
| G2 | Sync offline tanpa duplikasi | < 1% transaksi gagal sync; 0 order ganda (PRD: error rate < 1%) |
| G3 | Notifikasi WA cepat | ≥ 95% pesan "siap diambil" *delivered* < 2 menit sejak status berubah |
| G4 | Ketersediaan | ≥ 99% selama jam operasional 07.00–21.00 WIB |
| G5 | Audit lengkap | Setiap transaksi, perubahan status, redeem, buka/tutup shift, dan perubahan setting punya `staffId` + timestamp |

### 3.2 Termasuk (v1)

Autentikasi perangkat & PIN, master data (cabang, staff, price list, loyalty, reward), customer & impor CSV, order &
event layer, shift & kas, sinkronisasi offline, notifikasi WhatsApp (outbox, worker, webhook, opt-out), laporan owner,
log audit.

### 3.3 Tidak termasuk (v1) — mengikuti non-goal PRD produk

- Payment gateway / QRIS (fase 2). Enum pembayaran disiapkan agar `QRIS` bisa ditambah tanpa migrasi besar.
- Tier loyalty, referral, broadcast/campaign, win-back.
- Migrasi histori transaksi dari SaaS lama (hanya customer + saldo poin via impor).
- Printer thermal (struk digital via WA adalah pendekatan permanen).
- Modul inventory, role Supervisor.
- Panel admin web terpisah — seluruh fungsi owner ada di aplikasi tablet.

---

## 4. Pengguna, role & perangkat

| Aktor | Deskripsi | Cakupan data |
|---|---|---|
| **Kasir** (`KASIR`) | Staff cabang; transaksi, status order, redeem, kas & shift, impor customer, follow-up WA | Hanya cabang tempatnya terdaftar |
| **Owner/Admin** (`OWNER`) | Akses penuh; laporan, harga, staff, cabang, loyalty | Semua cabang; bisa login di perangkat cabang mana pun dan memindahkan konteks perangkat |
| **Perangkat** (tablet) | Tablet terdaftar yang dipakai bergantian oleh staff; selalu punya satu *cabang aktif* | Kredensial perangkat terpisah dari sesi staff |
| **Customer** | Menerima WA (bila opt-in), bisa berhenti dengan membalas STOP | Tidak mengakses API |
| **Meta WhatsApp Cloud API** | Sistem eksternal; mengirim webhook status & pesan masuk | Endpoint webhook saja |

### 4.1 Matriks akses

| Kemampuan | Kasir | Owner |
|---|:-:|:-:|
| Login PIN di perangkat cabang sendiri | ✔ | ✔ |
| Login PIN di perangkat cabang lain | ✘ | ✔ |
| Transaksi, redeem poin, daftar customer | ✔ (cabang sendiri) | ✔ |
| Lihat & majukan status order | ✔ (cabang sendiri) | ✔ (semua) |
| Buka/tutup shift, kas masuk/keluar | ✔ (cabang sendiri) | ✔ |
| Impor customer | ✔ | ✔ |
| Daftar & tindak lanjut WA gagal | ✔ (cabang sendiri) | ✔ (semua) |
| Laporan, log audit | ✘ | ✔ |
| Price list, reward, rate poin | ✘ (baca saja) | ✔ |
| Staff, cabang, perangkat | ✘ | ✔ |
| Pindah konteks cabang perangkat | ✘ | ✔ |

---

## 5. Arsitektur yang diusulkan

```
 Tablet cabang (Android)                         Backend pusat
┌──────────────────────────┐   HTTPS/JSON   ┌───────────────────────────────────────┐
│ UI (MVI) → UseCase        │ ─────────────▶ │ REST API /v1  (auth, validasi, rules)   │
│ Repository = Remote+Room  │ ◀───────────── │   │                                     │
│  • cache master data      │   delta sync   │   ▼  1 transaksi DB per aksi             │
│  • outbox transaksi       │                │ PostgreSQL ── orders, events, outbox    │
│    offline (clientTxId)   │                │   │                                     │
└──────────────────────────┘                │   ▼  polling FOR UPDATE SKIP LOCKED      │
                                             │ WA worker ──▶ WhatsApp Cloud API (Meta) │
                                             │   ▲                                     │
                                             │ Webhook /v1/webhooks/whatsapp ◀─ Meta   │
                                             │ Scheduler (reminder 3 hari, cleanup)    │
                                             └───────────────────────────────────────┘
```

Prinsip:

- **Server-authoritative.** Klien tetap menghitung total, poin, dan nomor order *untuk tampilan*; server menghitung
  ulang dan hasil server yang disimpan serta dikembalikan.
- **Satu aksi = satu transaksi database.** Contoh: simpan order menulis order, item, event, ledger poin, reward,
  shift, kas, penjualan harian, audit, dan outbox WA secara atomik (setara `OrderRepository.recordSale` di klien).
- **Transactional outbox** untuk WA — pesan tidak pernah hilang meski worker mati, dan tidak terkirim bila transaksi
  gagal.
- **Room tetap dipakai di klien** sebagai cache + outbox offline, sehingga UI berbasis `Flow` tidak perlu diubah.

### 5.1 Rekomendasi stack (tidak mengikat)

Kontrak API di dokumen ini tidak bergantung pada stack. Rekomendasi untuk tim kecil:

| Komponen | Rekomendasi | Alasan |
|---|---|---|
| Bahasa/framework | **Kotlin + Ktor Server** | Satu bahasa dengan klien; modul `:domain` adalah Kotlin/JVM murni, jadi aturan seperti `PhoneNumber.validate`, `LoyaltyRate.pointsFor`, `CalculateCartTotalsUseCase`, dan `ParseCustomerCsvUseCase` bisa dipakai ulang atau diekstrak ke modul bersama |
| Database | **PostgreSQL 16** + Flyway | Transaksi kuat, partial unique index, `SKIP LOCKED` untuk antrean |
| Antrean/job | Tabel outbox di Postgres | Tidak perlu Redis/broker di skala pilot (< 1.000 transaksi/hari) |
| Hosting | 1 container API + 1 worker, Postgres terkelola dengan PITR | Sederhana, murah, cukup untuk 4 cabang |
| Dokumentasi | **OpenAPI 3.1** sebagai kontrak resmi | Bisa dibuatkan klien Ktor & contract test |

---

## 6. Konvensi API

### 6.1 Umum

| Aturan | Nilai |
|---|---|
| Base URL | `https://<host>/api/v1` (menggantikan `BASE_URL` placeholder di `data/build.gradle.kts`) |
| Format | JSON UTF-8, `Content-Type: application/json`; field **camelCase** (sama dengan model Kotlin) |
| Waktu | ISO-8601 UTC dengan milidetik, mis. `2026-08-29T04:24:00.000Z` |
| Tanggal bisnis | `YYYY-MM-DD` dalam zona **Asia/Jakarta (WIB)** — dipakai untuk penjualan harian, nomor order, "hari ini" |
| Uang | Integer rupiah (`Long`), tanpa desimal: `45000` |
| Kuantitas | Angka desimal dengan maksimal 1 angka di belakang koma, kelipatan `step` layanan: `4.5` |
| ID | UUID string; nomor order tampilan di field terpisah `number` |
| Enum | Huruf besar, sama dengan klien: `DITERIMA`, `TUNAI`, `KILOAN_REGULER`, … (Lampiran A) |
| Nilai kosong | Dikirim sebagai `null`, bukan field dihilangkan |
| Paginasi | Cursor: `?limit=50&cursor=<opaque>` → `{ "items": [...], "nextCursor": "..." \| null }` |
| Idempotensi | Header `Idempotency-Key: <uuid>` **wajib** untuk `POST /orders`, `POST /orders/sync`, `POST /shifts`, `POST /shifts/{id}/cash-entries`, `POST /shifts/{id}/close`. Kunci yang sama + body sama → respons yang sama; body berbeda → `409 IDEMPOTENCY_MISMATCH`; tanpa kunci → `400`. Kunci dicakup per perangkat dan per endpoint. Disimpan 7 hari. Respons yang diputar ulang tidak pernah berisi token |
| Konkurensi | Resource yang bisa diubah dua perangkat membawa `version`; update mengirim `expectedVersion` atau status asal → `409` bila berubah |
| Versi aplikasi | Header `X-App-Version` dan `X-Device-Id` di setiap request; server bisa menolak versi lama dengan `426 APP_UPDATE_REQUIRED`. `X-Device-Id` = UUID instalasi yang dibuat aplikasi saat pertama dibuka (wajib di `pin-login` & `refresh`; di endpoint ber-sesi opsional, tetapi bila dikirim harus sama dengan tablet sesi → selain itu `401`) |

### 6.2 Format error

Semua error memakai amplop yang sama. **`message` adalah kalimat Bahasa Indonesia yang siap ditampilkan ke kasir
apa adanya** — setara `BusinessRuleException` di klien. Teks pesan di dokumen ini disalin dari use case yang ada
agar UX tidak berubah.

```json
{
  "error": {
    "code": "SHIFT_NOT_OPEN",
    "message": "Shift Cabang Familia Urban belum dibuka — buka shift dulu sebelum mencatat transaksi.",
    "details": { "branchId": "7f1c…" },
    "requestId": "req_01J9…"
  }
}
```

| HTTP | Kapan |
|---|---|
| `400 VALIDATION_ERROR` | Body tidak valid secara format (field hilang, tipe salah). `details.fields` berisi daftar field |
| `401 UNAUTHENTICATED` / `TOKEN_EXPIRED` | Token tidak ada, salah, atau kedaluwarsa |
| `403 FORBIDDEN` / `BRANCH_SCOPE` | Role tidak cukup, atau kasir mengakses cabang lain |
| `404 NOT_FOUND` | Resource tidak ada (pesan: "Order tidak ditemukan." — permintaan memakai `id`, jadi nomornya tidak diketahui) |
| `409 CONFLICT` | Bentrok status/versi/duplikat (mis. `STATUS_CHANGED`, `PRICE_CHANGED`, `PHONE_ALREADY_REGISTERED`, `SHIFT_ALREADY_OPEN`) |
| `422 BUSINESS_RULE` | Aturan bisnis dilanggar; `code` spesifik (Lampiran B) |
| `423 PIN_LOCKED` | Terlalu banyak PIN salah; header `Retry-After` |
| `426 APP_UPDATE_REQUIRED` | Versi aplikasi terlalu lama |
| `429 RATE_LIMITED` | Rate limit umum (120/menit per perangkat) atau 20 percobaan `pin-login` per alamat jaringan per 5 menit; header `Retry-After` |
| `5xx` | Kesalahan server — klien memperlakukannya sebagai *retryable* (sesuai `AppError.isRetryable`) |

Teks pesan kode transport ada di Lampiran B bagian "Kode transport".

---

## 7. Model data

### 7.1 Diagram relasi (ringkas)

```
branches 1─* staff (kasir)          customers 1─* points_ledger *─1 orders
branches 1─* devices                customers 1─* orders
branches 1─* shifts 1─* cash_entries    orders 1─* order_items
branches 1─* orders ─* order_events      orders 1─* wa_messages
services 1─* service_price_history   loyalty_rates (riwayat)   rewards
audit_log (append-only)  customer_imports  order_number_counters  idempotency_keys
```

### 7.2 Tabel

Semua tabel punya `created_at`, `updated_at` (UTC) kecuali disebut lain. Uang = `BIGINT`, kuantitas = `NUMERIC(7,1)`.

**branches**

| Kolom | Tipe | Aturan |
|---|---|---|
| id | uuid PK | |
| code | varchar(5) | unik, huruf besar (FMU, NRG, CPT) — dipakai di nomor order & impor CSV |
| name, address, phone, hours | text | `hours` teks bebas "07.00 – 21.00" (ikut isi pesan WA) |
| daily_target | bigint | > 0 |
| active | bool | |
| sort_order | int | |

**staff**

| Kolom | Tipe | Aturan |
|---|---|---|
| id | uuid PK | |
| name, short_name | text | `short_name` otomatis: "Siti Nurhaliza" → "Siti N." |
| role | enum `KASIR`,`OWNER` | CHECK: `KASIR` ⇒ `branch_id NOT NULL`; `OWNER` ⇒ `branch_id NULL` |
| branch_id | uuid FK null | |
| pin_lookup | char(64) | `HMAC-SHA256(PIN_PEPPER, pin)` — **unique index parsial `WHERE active`** |
| pin_hash | text | Argon2id |
| pin_failed_count, pin_locked_until | int, timestamptz | Lockout per akun |
| active | bool | |
| last_login_at | timestamptz null | |
| sort_order | int | |

**devices** — `id (= X-Device-Id), name, platform, app_version, last_branch_id, activated_at (pertama terlihat), last_seen_at, pending_count, pin_locked_until, revoked_at` — dicatat otomatis pada percobaan login pertama; tidak ada token maupun kode aktivasi (§12.1)
**sessions** — `id, device_id, staff_id, branch_id, refresh_token_hash, created_at, expires_at, revoked_at`

**customers**

| Kolom | Tipe | Aturan |
|---|---|---|
| id | uuid PK | Boleh dibuat klien (UUID) untuk customer yang didaftarkan offline |
| name | text | wajib |
| phone | text | tampilan "0812-3390-4471" |
| phone_digits | varchar(13) | **unik**, format "08…" 10–13 digit |
| points_balance | bigint | CHECK ≥ 0; hanya diubah lewat `points_ledger` |
| opt_in | bool | |
| opt_in_at, opt_out_at | timestamptz null | Bukti consent (P0 #5) |
| visits | int | +1 per order berbayar |
| home_branch_id | uuid FK | cabang pendaftar |
| created_by_staff_id | uuid null | |

**points_ledger** — `id, customer_id, order_id null, type (EARN, REDEEM, IMPORT, ADJUST), delta, balance_after, staff_id, created_at`.
Saldo customer = jumlah ledger; kolom `points_balance` adalah cache yang diperbarui dalam transaksi yang sama.

**services** — `id, category, name (unik case-insensitive), price (>0), unit (kg, pcs, pasang, m²), step (0.5 bila unit = kg, selain itu 1.0), active, sort_order`
**service_price_history** — `service_id, old_price, new_price, changed_by, changed_at`

**loyalty_rates** — `id, rupiah_per_step (≥ 1000), points_per_step (≥ 1), effective_from, created_by`.
Rate yang berlaku untuk suatu transaksi = baris dengan `effective_from ≤ captured_at` terbaru. Riwayat tidak pernah
diubah — ini yang menjamin "rate baru hanya berlaku untuk transaksi berikutnya" (P0 #6).

**rewards** — `id, name, cost_points (>0), value_rupiah (>0), note, min_subtotal (null, baru — T9), used_count, active, sort_order`

**orders**

| Kolom | Tipe | Aturan |
|---|---|---|
| id | uuid PK | |
| number | varchar(20) | "FMU-0829-015"; unik bersama `(branch_id, business_date)` |
| branch_id, business_date, seq | uuid, date, int | UNIQUE `(branch_id, business_date, seq)` |
| client_tx_id | uuid | **UNIQUE** — kunci anti-duplikasi sync |
| source | enum `ONLINE`,`OFFLINE_SYNC` | |
| customer_id | uuid null | null = walk-in "Tanpa nama" |
| customer_name, customer_phone | text | snapshot saat transaksi ("—" untuk walk-in) |
| note | text | |
| subtotal, discount, total | bigint | total = subtotal − discount ≥ 0 |
| reward_id, reward_name, redeemed_points | uuid null, text null, bigint | |
| earned_points | bigint | |
| loyalty_rate_id | uuid | rate yang dipakai |
| payment | enum `TUNAI`,`TRANSFER` | |
| status | enum `DITERIMA`,`PROSES`,`SIAP`,`SELESAI` | |
| wa_status | enum `TERKIRIM`,`MENUNGGU`,`GAGAL`,`BELUM_OPTIN` | cache status notifikasi "siap diambil" |
| shift_id | bigint/uuid FK | |
| staff_id | uuid FK | pelaku transaksi |
| captured_at | timestamptz | waktu transaksi di perangkat (untuk offline ≠ waktu diterima server) |
| created_at | timestamptz | waktu diterima server |
| status_changed_at | timestamptz | |
| flags | text[] | hanya `OFFLINE_SYNC`: `LATE_AFTER_SHIFT_CLOSE`, `PRICE_MISMATCH`, `SERVICE_INACTIVE`, `STALE_CAPTURE`, `CUSTOMER_PHONE_MATCHED` (dikunci CHECK; flag baru = migrasi baru) |
| version | int | optimistic locking |

**order_items** — `id, order_id, position, service_id, name, qty, unit, unit_price, subtotal` (semua snapshot)
**order_events** — `id bigserial, order_id, branch_id, type (CREATED, STATUS_CHANGED, WA_STATUS_CHANGED), from_status, to_status, staff_id, device_id, created_at, payload jsonb`
**order_number_counters** — `branch_id, business_date, last_seq` (PK gabungan; di-increment dengan `UPDATE … RETURNING` di dalam transaksi order)

**shifts**

| Kolom | Tipe | Aturan |
|---|---|---|
| id | uuid PK | |
| branch_id | uuid FK | **unique index parsial `WHERE closed_at IS NULL`** → maksimal 1 shift terbuka per cabang |
| opened_by_staff_id, opened_by_name | uuid, text | |
| opened_at, closed_at | timestamptz | |
| closed_by_staff_id | uuid null | |
| opening_cash | bigint | > 0 |
| cash_sales, transfer_sales, tx_count, points_issued | bigint/int | akumulasi dari order |
| counted_cash | bigint | hitungan uang fisik terakhir (awal = modal awal) |
| recap_expected, recap_actual | bigint null | dibekukan saat tutup |
| version | int | |

**cash_entries** — `id, shift_id, branch_id, kind (OPENING, CASH_IN, CASH_OUT, SALE), label, note, amount (bertanda; negatif = keluar), order_id null, staff_id, created_at`
**daily_sales** — `branch_id, business_date, revenue, tx_count` (PK gabungan; di-upsert di transaksi order; bisa juga view)

**wa_messages** (outbox + riwayat; menggantikan `wa_failures`)

| Kolom | Tipe | Aturan |
|---|---|---|
| id | uuid PK | |
| branch_id, order_id, customer_id | uuid | |
| template | enum `STATUS_SIAP_DIAMBIL`,`STRUK_DIGITAL`,`OPT_IN_CONFIRM`,`REMINDER_3_HARI` | |
| to_phone | varchar(15) | E.164 tanpa `+`: `6281233904471` |
| params | jsonb | nilai variabel template |
| status | enum `QUEUED`,`SENDING`,`SENT`,`DELIVERED`,`READ`,`FAILED`,`CANCELLED` | |
| attempts, next_attempt_at | int, timestamptz | |
| provider_message_id | text null | `wamid` dari Meta |
| error_code, error_reason | text null | `error_reason` dalam Bahasa Indonesia untuk UI |
| resolution | enum `NONE`,`RESENT`,`MANUAL_CALL` | tindak lanjut kasir |
| resolved_by, resolved_at | uuid, timestamptz | |
| queued_at, sent_at, delivered_at, failed_at | timestamptz | untuk metrik < 2 menit |

Unique index parsial `(order_id) WHERE template = 'REMINDER_3_HARI'` menjamin pengingat maksimal satu per order.

**audit_log** (append-only — role DB aplikasi tidak punya hak `UPDATE`/`DELETE`)

| Kolom | Tipe | Catatan |
|---|---|---|
| id | bigserial | |
| branch_id | uuid null | null = berlaku semua cabang |
| staff_id | uuid null | T10 |
| actor_name | text | "Siti N. (kasir)" — format `Staff.auditName` |
| action_type | varchar | mis. `ORDER_CREATED`, `ORDER_STATUS_CHANGED`, `SHIFT_OPENED` (Lampiran C) |
| action | text | kalimat untuk UI, format sama dengan klien sekarang |
| entity_type, entity_id | text | |
| metadata | jsonb | nilai lama/baru, jumlah, dll. |
| device_id | uuid null | |
| created_at | timestamptz | |

**customer_imports** — `id, file_name, size_bytes, branch_id, created_by, status (PREVIEW, COMMITTED, EXPIRED), preview jsonb, summary jsonb, expires_at (30 menit), committed_at`
**idempotency_keys** — `key, device_id, endpoint, request_hash, status_code, response jsonb, created_at`

### 7.3 Nomor order (T4)

- Format tampilan tetap seperti desain: `{branch.code}-{MMdd}-{seq 3 digit}` → `FMU-0829-015`.
- `MMdd` = tanggal bisnis WIB dari `captured_at` (transaksi offline memakai hari transaksinya, sama seperti
  `NextOrderIdUseCase.forTime`).
- `seq` diambil dari `order_number_counters` secara atomik di transaksi yang sama → tidak ada lompatan akibat race.
- Identitas unik = `id` (UUID). `number` unik per `(branch_id, business_date)`. Endpoint menerima `id`; pencarian
  menerima `number`.
- `GET /orders/next-number` mengembalikan **perkiraan** nomor berikutnya untuk header POS (`draftOrderId`); nomor
  final ditentukan saat order disimpan.

---

## 8. Spesifikasi endpoint

Notasi akses: **K** = staff kasir, **O** = owner, **Pub** = publik (tanpa token, dengan verifikasi khusus).
Semua endpoint K/O otomatis dibatasi ke cabang token untuk kasir.

### 8.1 Perangkat & autentikasi

| Method & path | Akses | Fungsi | Use case klien |
|---|---|---|---|
| `GET /devices` · `DELETE /devices/{id}` | O | Daftar tablet yang pernah login & blokir tablet hilang | *(baru)* |
| `GET /login-options` | Pub | Cabang + nama kasir aktif + status shift untuk layar login | `LoginViewModel` |
| `POST /auth/pin-login` | Pub (`X-Device-Id`) | Login PIN di cabang yang dipilih | `LoginWithPinUseCase` |
| `POST /auth/refresh` | Pub (`X-Device-Id`) | Perpanjang sesi | *(baru)* |
| `POST /auth/verify-pin` | K/O | Verifikasi PIN staff tertentu → `staffProof` sekali pakai (5 menit) | `VerifyStaffPinUseCase` |
| `POST /auth/switch-staff` | K/O | Serahkan perangkat ke staff lain (PIN) → token baru | `SwitchActiveStaffUseCase` |
| `POST /auth/switch-branch` | O | Pindah konteks cabang perangkat → token baru | `SwitchBranchUseCase` |
| `POST /auth/logout` | K/O | Cabut sesi, catat audit, kembalikan `lastBranchId` | `LogoutUseCase` |
| `GET /me` | K/O | Konteks sesi (`staff`, `branch`) | `ObserveSessionContextUseCase` |

Sengaja **tidak disediakan**: endpoint "apakah PIN ini milik akun aktif" (`matchesActivePin`) — itu oracle untuk
menebak PIN tanpa tercatat sebagai login gagal (T3).

#### `POST /auth/pin-login`

```json
// Request  (tanpa Authorization; header X-Device-Id: <UUID instalasi>)
{ "branchId": "b1…", "pin": "1234" }

// 200
{
  "accessToken": "eyJ…", "accessTokenExpiresIn": 3600,
  "refreshToken": "rt_…", "refreshTokenExpiresIn": 64800,
  "context": {
    "staff":  { "id": "u1…", "name": "Siti Nurhaliza", "shortName": "Siti N.", "role": "KASIR", "branchId": "b1…", "active": true, "lastLoginAt": "…" },
    "branch": { "id": "b1…", "code": "FMU", "name": "Familia Urban", "address": "…", "phone": "…", "hours": "07.00 – 21.00", "dailyTarget": 5200000, "active": true }
  }
}
```

Aturan (urutan pengecekan sama dengan `LoginWithPinUseCase`):

| Kondisi | HTTP / code | Pesan | `details.clearPin` |
|---|---|---|---|
| Cabang tidak ada | 422 `BRANCH_REQUIRED` | Pilih cabang perangkat dulu. | false |
| Cabang nonaktif | 422 `BRANCH_INACTIVE` | Cabang {nama} sedang nonaktif — pilih cabang lain atau hubungi owner. | false |
| PIN < 4 atau > 6 digit / bukan angka | 422 `PIN_FORMAT` | PIN minimal 4 digit. | false |
| Tablet diblokir owner | 401 `DEVICE_UNAUTHORIZED` | Tablet ini sudah diblokir owner — hubungi owner untuk memakai tablet lain. | — |
| PIN tidak cocok | 422 `PIN_UNKNOWN` | PIN tidak dikenali. Coba lagi atau minta owner reset PIN. | true |
| Akun nonaktif | 422 `STAFF_INACTIVE` | Akun {nama} nonaktif — PIN lama sudah diblokir. | true |
| Kasir di cabang lain | 422 `STAFF_WRONG_BRANCH` | {nama} terdaftar di Cabang {asal} — tidak bisa login di perangkat Cabang {cabang}. | true |
| Terlalu banyak gagal | 423 `PIN_LOCKED` | Terlalu banyak PIN salah. Coba lagi dalam {n} menit. | true |

`X-Device-Id` wajib (tanpa header → 400). Tablet terkunci setelah 5 PIN salah dalam 5 menit (`423`); maksimal 20
percobaan per alamat jaringan per 5 menit (`429`).

- Kunci per tablet menghitung **semua penolakan setelah PIN dievaluasi** (`PIN_UNKNOWN`, `STAFF_INACTIVE`,
  `STAFF_WRONG_BRANCH`, `PIN_WRONG`) — dua penolakan terakhir juga membocorkan bahwa PIN itu milik akun. Percobaan yang
  ditolak karena tablet sedang terkunci tidak diaudit ulang (kunci tidak diperpanjang terus).
- Percobaan yang memicu kunci tetap mendapat error aslinya; `423` baru di percobaan berikutnya (§16 #14).
- Kunci akun (§12.2) juga berlaku di sini: PIN benar untuk akun yang sedang terkunci → `423`. Counter akun hanya
  bertambah di `verify-pin`/`switch-staff`, karena PIN salah tanpa pilih nama tidak bisa diatribusikan ke akun.
- `STAFF_WRONG_BRANCH` menyebut nama pemilik PIN seperti klien sekarang.

Efek: `staff.last_login_at`, sesi baru (sesi lain di tablet yang sama berakhir), `devices.last_branch_id`, audit
`"Login PIN sebagai kasir Familia Urban di perangkat Cabang Familia Urban"` / `"… sebagai owner/admin …"`, dan pada login pertama
sebuah tablet audit `DEVICE_ACTIVATED` `"Tablet baru dipakai login pertama kali di Cabang Familia Urban"`.

#### `POST /auth/verify-pin` → dipakai layar Kas sebelum buka shift

```json
{ "staffId": "u3…", "pin": "5678" }
// 200
{ "staff": { … }, "staffProof": "sp_…", "expiresIn": 300 }
```

Aturan (`VerifyStaffPinUseCase`), berurutan: staff wajib dipilih dan dikenal (`STAFF_REQUIRED` "Pilih staff dulu."),
aktif (`STAFF_INACTIVE` "Akun {nama} nonaktif — PIN lama tidak bisa dipakai."), boleh bekerja di cabang token
(`STAFF_WRONG_BRANCH`), PIN ≥ 4 digit (`PIN_FORMAT`), akun tidak terkunci (`423`), PIN cocok (`PIN_WRONG` "PIN salah
untuk {nama}. Coba lagi."). Gagal dihitung ke lockout akun. `staffProof` disimpan sebagai hash (`staff_proofs`), terikat
ke tablet & cabang sesi, sekali pakai, berlaku 5 menit — dipakai `POST /shifts` (§8.7).

#### `POST /auth/switch-staff`

Body `{ "staffId", "pin" }` → validasi seperti `verify-pin`, lalu menerbitkan token untuk staff tsb di cabang yang
sama, audit `"Login PIN sebagai {kasir Familia Urban | owner/admin}"`. Klien menerima `context` baru; jika staff baru kasir,
klien keluar dari layar owner (`ShellEffect.CashierTookOver`). Respons sama dengan `pin-login`. `switch-staff` tidak
menolak cabang nonaktif (hanya login & buka shift yang ditolak, §8.2), agar serah terima di shift yang sedang
diselesaikan tetap bisa.

#### `POST /auth/switch-branch` (owner)

Body `{ "branchId" }`. Aturan: pemanggil owner (kasir → 403 dengan pesan "Perangkat kasir terikat ke Cabang {nama}.
Hanya owner/admin yang bisa memindahkan perangkat ke cabang lain."), cabang ada, cabang aktif ("Cabang {nama}
nonaktif — aktifkan dulu di halaman Cabang sebelum memindahkan perangkat."). Bila cabang berbeda → token baru +
audit `"Pindah konteks perangkat ke Cabang {nama}"`. Respons `{ changed, accessToken, accessTokenExpiresIn, refreshToken,
refreshTokenExpiresIn, context }`; token `null` bila cabang sama (`changed: false`).

#### `POST /auth/refresh`, `POST /auth/logout`, perangkat

- `refresh` — header `X-Device-Id` + `{ "refreshToken" }` → bentuk yang sama dengan `pin-login` (termasuk `context`).
  Refresh token dirotasi dan hanya berlaku dari tablet yang sama; sesi tetap berakhir 18 jam sejak login.
- `logout` → `{ "lastBranchId" }`, audit `"Logout dari perangkat Cabang {nama}"`.
- Satu tablet, satu sesi staff: login PIN, `switch-staff`, atau serah terima saat buka shift mencabut sesi lain di
  tablet yang sama.
- `GET /devices` → `{ items: [{ id, name, platform, appVersion, lastBranchId, firstSeenAt, lastSeenAt, pendingCount,
  revokedAt }] }` — hanya tablet yang pernah berhasil login, supaya ID instalasi karangan tidak memenuhi daftar.
- `DELETE /devices/{id}` → `{ device, changed }`, audit `DEVICE_REVOKED` `"Blokir perangkat {nama}"`. Owner boleh
  memblokir tablet yang sedang ia pakai (sesinya ikut berakhir).
- Audit lain: `LOGIN_FAILED` `"PIN ditolak di perangkat Cabang {nama} — {KODE}"`; `PIN_LOCKED` `"Perangkat {nama}
  dikunci 5 menit — 5 PIN salah dalam 5 menit"` / `"Akun {nama} dikunci 15 menit — 5 PIN salah berturut-turut"`.

#### `GET /login-options`

```json
{
  "lastBranchId": "b1…",
  "branches": [
    { "id": "b1…", "code": "FMU", "name": "Familia Urban", "address": "…", "hours": "07.00 – 21.00", "active": true,
      "cashierNames": ["Siti N.", "Bagas A."], "shift": { "open": true, "openedByName": "Siti Nurhaliza" } }
  ]
}
```

Hanya menampilkan nama pendek kasir aktif — **tidak pernah** mengirim hash PIN.

### 8.2 Cabang

| Method & path | Akses | Fungsi |
|---|---|---|
| `GET /branches` | K/O | Semua cabang (kasir juga butuh kode & nama untuk tampilan) |
| `GET /branches/overview?date=` | O | Per cabang: omzet & transaksi hari itu, shift terakhir, kasir aktif — untuk halaman Cabang |
| `PATCH /branches/{id}` | O | Ubah `dailyTarget` dan/atau `active` |

`PATCH /branches/{id}` — body `{ "dailyTarget"?: 5500000, "active"?: false }`:

- `dailyTarget` ≤ 0 → 422 `TARGET_REQUIRED` "Target harian tidak boleh kosong — dikembalikan ke {target lama}."
- Respons `{ branch, changed }`. Nilai sama → 200 dengan `"changed": false`, tanpa audit.
- Audit `"Ubah target harian Cabang {nama}: Rp5.200.000 → Rp5.500.000"`.
- Menonaktifkan cabang yang sedang menjadi konteks perangkat pemanggil → 422 `BRANCH_IN_USE` "Tidak bisa
  menonaktifkan cabang yang sedang dipakai perangkat ini."
- Cabang nonaktif: login & buka shift baru ditolak; shift yang sudah terbuka boleh diselesaikan dan transaksi offline
  tetap diterima. Audit `"Nonaktifkan Cabang {nama}"` / `"Aktifkan Cabang {nama}"`.

`GET /branches/overview` → `{ date, items: [{ branch, revenue, txCount, targetRatio, latestShift, activeCashiers }] }`.

### 8.3 Staff

| Method & path | Akses | Fungsi | Use case |
|---|---|---|---|
| `GET /staff?branchId=&active=` | O (semua field) · K (hanya staff aktif yang boleh bekerja di cabangnya: `id, name, shortName, role`) | Daftar akun / kandidat PIN | `StaffViewModel`, dialog PIN |
| `POST /staff` | O | Tambah staff | `AddStaffUseCase` |
| `POST /staff/{id}/reset-pin` | O | PIN acak baru, dikembalikan **sekali** | `ResetStaffPinUseCase` |
| `PATCH /staff/{id}` | O | `{ "active": bool }` | `ToggleStaffActiveUseCase` |

`POST /staff` — body `{ "name", "pin", "role", "branchId" }`:

| Aturan | Pesan |
|---|---|
| Nama wajib | Nama staff wajib diisi. |
| PIN 4–6 digit angka | PIN harus 4–6 digit. |
| PIN tidak dipakai staff aktif lain | PIN ini sudah dipakai staff lain — pilih kombinasi lain. |
| Kasir wajib punya cabang; owner selalu `branchId = null` | Kasir harus ditempatkan di satu cabang. |

`shortName` diturunkan server. Audit `"Tambah akun staff {nama} (kasir, Cabang Familia Urban) · PIN dibuat"`.

`POST /staff/{id}/reset-pin` → `{ "staff": {…}, "pin": "4821" }`. PIN 4 digit acak yang tidak bentrok dengan
**semua** akun; PIN lama langsung tidak berlaku dan semua sesi staff tsb dicabut. Respons tidak boleh di-cache atau
di-log. Audit `"Reset PIN {nama} — PIN lama dicabut"`.

`PATCH /staff/{id}` `active`:

- Menonaktifkan akun sendiri → 422 "Tidak bisa menonaktifkan akun yang sedang dipakai."
- **Baru (T2):** mengaktifkan akun yang PIN-nya kini dipakai staff aktif lain → 409 `PIN_CONFLICT` "PIN {nama}
  sudah dipakai staff lain — reset PIN setelah akun diaktifkan." (atau server otomatis reset PIN — lihat §17).
- **Baru:** menonaktifkan owner aktif terakhir → 422 "Minimal harus ada satu owner aktif."
- Menonaktifkan akun mencabut semua sesinya. Audit `"Nonaktifkan akun {nama}"` / `"Aktifkan akun {nama}"`.
- Respons `{ staff, changed }`; nilai sama → `changed: false`, tanpa audit.

### 8.4 Price list, loyalty & reward

| Method & path | Akses | Fungsi | Use case |
|---|---|---|---|
| `GET /services?includeInactive=` | K/O | Price list (kasir: aktif saja) | `CatalogRepository` |
| `POST /services` | O | Tambah layanan | `AddServiceUseCase` |
| `PATCH /services/prices` | O | Simpan banyak harga sekaligus `{ "prices": { "<id>": 10000 } }` | `SavePriceListUseCase` |
| `PATCH /services/{id}` | O | `{ "active": bool }` | `ToggleServiceUseCase` |
| `GET /loyalty/rate` | K/O | Rate aktif | `LoyaltyRepository` |
| `PUT /loyalty/rate` | O | `{ "rupiahPerStep": 10000, "pointsPerStep": 100 }` | `SaveLoyaltyRateUseCase` |
| `GET /rewards?includeInactive=` | K/O | Katalog reward | |
| `POST /rewards` | O | `{ "name", "cost", "value", "minSubtotal"? }` | `AddRewardUseCase` |
| `PATCH /rewards/{id}` | O | `{ "active": bool }` | `ToggleRewardUseCase` |

Aturan:

- **Respons:** toggle/ubah → `{ service | reward, changed }`; `PATCH /services/prices` → `{ items: [semua layanan],
  changedCount }`; `PUT /loyalty/rate` → `{ rate, changed }`. Semua toggle menerima nilai tujuan; nilai sama →
  `changed: false`, tanpa audit.
- **Tambah layanan:** nama wajib ("Nama layanan wajib diisi."), harga > 0 ("Harga per unit belum diisi."), nama unik
  tanpa membedakan huruf besar/kecil ("Layanan dengan nama ini sudah ada di price list."). `step` = 0,5 bila
  `unit = "kg"`, selain itu 1. Audit `"Tambah layanan {nama} — Rp{harga}/{unit} ({kategori})"`.
- **Simpan harga:** semua harga > 0 ("Harga layanan tidak boleh kosong."), satu transaksi DB, satu baris
  `service_price_history` per perubahan, audit per perubahan `"Ubah harga Cuci Setrika: Rp9.500 → Rp10.000 (semua
  cabang)"` + ringkasan `"Simpan price list — {n} layanan aktif di semua cabang"` (tanpa perubahan → tanpa audit). Order yang sudah ada tidak
  berubah (harga ada di snapshot `order_items`).
- **Nonaktifkan layanan:** hilang dari POS, order lama tetap utuh.
- **Rate poin:** `rupiahPerStep` ≥ 1.000 ("Nominal belanja minimal Rp1.000."), `pointsPerStep` ≥ 1 ("Poin didapat
  minimal 1."). Disimpan sebagai baris baru `loyalty_rates` dengan `effective_from = now()`; rate yang sama dengan rate
  aktif tidak menyisipkan baris baru. Audit `"Simpan pengaturan loyalty — Rp10.000 = 100 poin, berlaku semua cabang"`.
- **Reward:** nama wajib ("Nama reward wajib diisi."), `cost` dan `value` > 0 ("Poin dan nilai diskon wajib diisi."),
  `note` default `"Setara Rp{value}"`, `minSubtotal` 0 atau kosong = tanpa minimum (`null`). Audit `"Tambah reward
  {nama} — 1.000 poin, nilai Rp10.000[ · minimum belanja Rp75.000]"`; toggle `"Nonaktifkan reward {nama} (1.500 poin) —
  berlaku semua cabang"`. Toggle layanan: `"Nonaktifkan layanan {nama} ({kategori}) — berlaku semua cabang"`.

### 8.5 Customer & impor

| Method & path | Akses | Fungsi | Use case |
|---|---|---|---|
| `GET /customers?q=&limit=&cursor=` | K/O | Tanpa `q`: pelanggan terbanyak kunjungan. Dengan `q`: cocok nama (contains, case-insensitive) atau digit no HP | `PosState.customerMatches` |
| `GET /customers/{id}` | K/O | Detail + saldo poin terbaru | |
| `POST /customers` | K/O | Daftar customer baru (walk-in) | `RegisterCustomerUseCase` |
| `PATCH /customers/{id}` | K/O | `{ "name"?, "optIn"? }` — ubah consent WA | *(baru, P1 UI)* |
| `GET /customers/{id}/points` | K/O | Riwayat ledger poin | *(baru, P1)* |
| `GET /customer-imports/template` | K/O | File CSV template (`text/csv`) | `CustomerImportTemplate.template` |
| `POST /customer-imports` | K/O | Upload file (`multipart/form-data`, field `file`, maks 2 MB) → preview | `ParseCustomerCsvUseCase` |
| `POST /customer-imports/{id}/commit` | K/O | Jalankan impor → ringkasan | `ImportCustomersUseCase` |
| `GET /customer-imports/{id}/error-report` | K/O | CSV `baris,nama,no_hp,penyebab` | `CustomerImportTemplate.errorReport` |

`POST /customers` — body `{ "id"?: "<uuid klien>", "name", "phone", "optIn" }`; `homeBranchId` = cabang token.

| Aturan | Pesan |
|---|---|
| Nama wajib | Nama customer wajib diisi. |
| No HP valid: hanya digit/spasi/`-`/`+`; awalan `62` → `0`; 10–13 digit; diawali `08` | Nomor WhatsApp belum valid (minimal 10 digit, diawali 08). |
| No HP belum terdaftar (semua cabang) | 409 `PHONE_ALREADY_REGISTERED` — Nomor ini sudah terdaftar (bisa dari cabang lain) — pakai pencarian untuk memilihnya. `details.customer` berisi customer yang ada |

Efek: `phone` disimpan dalam format `0812-3390-4471`, poin 0, kunjungan 0, `opt_in_at` bila opt-in, audit
`"Daftarkan customer {nama} ({phone}) · opt-in WA: ya|belum"`. Pesan WA `OPT_IN_CONFIRM` dikirim bersama transaksi
pertamanya (§10.2). Respons `201 { customer }`; `Customer` = `{ id, name, phone, points, optIn, visits, homeBranchId }`.

`PATCH /customers/{id}` → `{ customer, changed }`. Nama kosong → `NAME_REQUIRED`. Audit `CUSTOMER_UPDATED` `"Ubah nama
customer {lama} → {baru}"` / `"Ubah opt-in WA customer {nama}: ya"`. Opt-out mengisi `opt_out_at`, membatalkan
(`CANCELLED`) pesan WA customer yang masih `QUEUED` dalam transaksi yang sama (sama seperti balasan STOP §10.4), audit
`CUSTOMER_OPTED_OUT`. `GET /customers/{id}/points` → `{ items: [{ id, type, delta, balanceAfter, orderId, staffId,
createdAt }], nextCursor }`.

**Impor CSV** — aturan persis `ParseCustomerCsvUseCase`:

- Header `nama,no_hp,saldo_poin_awal,cabang_asal`; pemisah `,` atau `;` (dipilih dari yang terbanyak di baris
  header); BOM dibuang; baris header dikenali bila mengandung "nama"; baris kosong dilewati; nomor baris = nomor
  baris file (1-based).
- Per baris: nama wajib ("Kolom nama wajib diisi") → no HP valid (pesan dari validator) → poin kosong = 0, titik
  ribuan dibuang, harus angka ≥ 0 ("Saldo poin awal bukan angka ("{nilai}")") → kode cabang kosong = cabang token,
  tidak dikenal = error ("Kode cabang "{kode}" tidak dikenal") → no HP sudah ada di DB = **duplikat** ("Duplikat —
  nomor sudah dipakai {nama} ({cabang})") → no HP muncul dua kali di file = error ("Duplikat dalam file (sama
  dengan baris {n})").
- Respons preview: `{ importId, fileName, sizeBytes, totalRows, candidates[], duplicates[], errors[], expiresAt }`.
- `commit`: ditolak bila tidak ada kandidat ("Tidak ada baris valid untuk diimpor."); **validasi ulang** duplikat saat
  commit (nomor bisa terdaftar di antara preview dan commit); customer impor `optIn = false`, `visits = 0`; saldo awal
  masuk `points_ledger` tipe `IMPORT`; satu transaksi DB.
- Ringkasan `{ imported, duplicates, failed, pointsMoved }`; audit `"Impor {n} customer ke Cabang {nama} · {d}
  duplikat dilewati · {f} baris gagal"`.
- File `.xls/.xlsx` → 422 "File Excel belum didukung — simpan sebagai CSV dari Excel lalu unggah ulang." (lihat §17
  untuk dukungan Excel).

### 8.6 Order

| Method & path | Akses | Fungsi | Use case |
|---|---|---|---|
| `POST /orders` | K/O | Simpan & bayar (online) | `PlaceOrderUseCase` (online) |
| `POST /orders/sync` | K/O | Kirim antrean offline (batch) | `SyncPendingTransactionsUseCase` |
| `GET /orders` | K/O | Daftar + jumlah per status | `OrdersViewModel` |
| `GET /orders/{id}` | K/O | Detail order + event | |
| `POST /orders/{id}/advance` | K/O | Majukan status satu langkah | `AdvanceOrderStatusUseCase` |
| `GET /orders/next-number` | K/O | Perkiraan nomor berikutnya cabang token | `NextOrderIdUseCase` |

#### `POST /orders`

```json
// Header: Idempotency-Key: 3f0c…   (sama dengan clientTxId)
{
  "clientTxId": "3f0c…",
  "customerId": "c1…",                  // null = walk-in
  "items": [ { "serviceId": "cs…", "qty": 4.5, "unitPrice": 10000 } ],
  "rewardId": "r2…",                    // opsional
  "payment": "TUNAI",
  "note": "Pisahkan baju putih",
  "expectedTotal": 31000                // total yang dilihat kasir
}
```

```json
// 201
{
  "order": {
    "id": "9b2e…", "number": "FMU-0829-015", "branchId": "b1…", "businessDate": "2026-08-29",
    "customerId": "c1…", "customerName": "Dewi Anggraini", "customerPhone": "0812-3390-4471",
    "items": [ { "serviceId": "cs…", "name": "Cuci Setrika", "qty": 4.5, "unit": "kg", "unitPrice": 10000, "subtotal": 45000 } ],
    "note": "Pisahkan baju putih",
    "subtotal": 45000, "discount": 14000, "total": 31000,
    "rewardName": "Gratis Cuci Kering 2 kg", "redeemedPoints": 1500, "earnedPoints": 300,
    "rewardId": "r2…", "payment": "TUNAI", "status": "DITERIMA", "waStatus": "MENUNGGU",
    "capturedAt": "…", "createdAt": "…", "statusChangedAt": "…", "shiftId": "s1…", "staffId": "u1…",
    "source": "ONLINE", "flags": [], "version": 1
  },
  "customer": { "id": "c1…", "points": 1140, "visits": 19 }
}
```

Validasi berurutan:

| # | Aturan | HTTP / code | Pesan |
|---|---|---|---|
| 1 | Cabang token ada | 404 | Cabang tidak ditemukan. |
| 2 | Ada shift terbuka di cabang | 422 `SHIFT_NOT_OPEN` | Shift Cabang {nama} belum dibuka — buka shift dulu sebelum mencatat transaksi. |
| 3 | `items` tidak kosong | 422 `EMPTY_CART` | Belum ada layanan di order ini. |
| 4 | Setiap layanan ada & aktif; `qty > 0`, kelipatan `step`, ≤ 999 | 422 `INVALID_ITEM` | Layanan {nama} sudah tidak tersedia. / Jumlah {nama} tidak valid. |
| 5 | `unitPrice` = harga price list saat ini | 409 `PRICE_CHANGED` | Harga {nama} baru saja diubah owner — cek ulang total sebelum bayar. (`details.services` berisi harga terbaru) |
| 6 | Reward aktif, customer dipilih, saldo ≥ `cost`, `subtotal ≥ minSubtotal` | 422 `INSUFFICIENT_POINTS` / `REWARD_NOT_ELIGIBLE` | Saldo poin belum cukup untuk reward ini. |
| 7 | `expectedTotal` = total server | 409 `TOTAL_MISMATCH` | Total berubah — muat ulang keranjang. (`details` = `{ subtotal, discount, total, earnedPoints }` server) |

Pesan tambahan: layanan yang tidak ada sama sekali → `INVALID_ITEM` "Layanan yang dipilih sudah tidak tersedia.";
reward nonaktif/tidak ada → `REWARD_NOT_ELIGIBLE` "Reward ini sudah tidak tersedia."; `customerId` tidak ada → 404
"Customer tidak ditemukan.". `clientTxId` yang sudah punya order tetapi `Idempotency-Key` berbeda (aplikasi kehilangan
kuncinya) → 409 `DUPLICATE_TRANSACTION` "Transaksi ini sudah tersimpan sebagai {nomor} — muat ulang daftar order."
dengan `details.order`; kunci yang sama memutar ulang respons pertama. Walk-in: `customer = null` di respons.

Perhitungan: §9.1. Efek atomik (satu transaksi DB — setara `OrderRepository.recordSale`):

1. Ambil nomor dari `order_number_counters`; insert `orders` + `order_items` (snapshot).
2. `order_events` `CREATED` → `DITERIMA`.
3. Customer: `points_ledger` `EARN` (+earned) dan `REDEEM` (−cost) bila ada; `visits + 1`; cache saldo.
4. Reward: `used_count + 1`.
5. Shift: `cash_sales` atau `transfer_sales` += total, `tx_count + 1`, `points_issued += earned`.
6. Bila `TUNAI` dan total > 0: `cash_entries` `SALE` label `"Pembayaran tunai {number}"`, note = nama customer.
7. `daily_sales` (cabang, tanggal bisnis) += total, tx + 1.
8. Audit `"Transaksi {number} · Rp{total} · {Tunai|Transfer}[ · redeem {cost} poin]"` (+ audit terpisah
   `REWARD_REDEEMED` `"Redeem {reward} · {cost} poin · {customer} · {number}"` untuk laporan redeem).
9. Outbox WA (§10.2), hanya bila customer opt-in **saat ini**: `OPT_IN_CONFIRM` sekali per persetujuan (transaksi
   pertama sejak `opt_in_at` terakhir — opt-out lalu opt-in lagi mendapat konfirmasi baru), lalu `STRUK_DIGITAL`.
   Baris dikunci per customer sehingga dua order bersamaan tidak sama-sama mengirim konfirmasi.

#### `GET /orders`

Query: `branchId` (kasir: diabaikan, selalu cabang token; owner: kosong + `scope=all` = semua cabang), `status`,
`q` (cocok `number`, nama customer, atau digit no HP — sama dengan `OrdersState.visible`), `from`/`to` (tanggal
bisnis; default: order belum `SELESAI` + order 7 hari terakhir), `limit`, `cursor`. Urut `capturedAt` terbaru. Owner
tanpa `branchId` dan tanpa `scope=all` mendapat cabang sesi tablet. `counts` mengikuti filter yang sama tanpa `status`.

```json
{ "items": [ { …order… } ], "nextCursor": null,
  "counts": { "ALL": 13, "DITERIMA": 2, "PROSES": 4, "SIAP": 4, "SELESAI": 3 } }
```

#### `POST /orders/{id}/advance`

Body `{ "fromStatus": "PROSES" }`.

- Order tidak ada → 404 "Order tidak ditemukan."
- Kasir dan order di cabang lain → 403 `BRANCH_SCOPE` "Kasir hanya bisa mengakses data Cabang {nama}.".
- Status saat ini ≠ `fromStatus` (perangkat lain sudah mengubah) → 409 `STATUS_CHANGED` dengan order terbaru.
- Status `SELESAI` → 200 `{ "result": "ALREADY_COMPLETED", "order": …, "notificationQueued": false }`.
- Transisi hanya maju satu langkah: `DITERIMA → PROSES → SIAP → SELESAI`. Tidak ada mundur atau lompat.
- Efek: `status`, `status_changed_at`, `version + 1`, `order_events` `STATUS_CHANGED` dengan staff & perangkat.
- Saat menjadi `SIAP` **dan customer saat ini opt-in** (dicek dari tabel customer, bukan dari `wa_status` lama — T7):
  antrekan `STATUS_SIAP_DIAMBIL`, `wa_status = MENUNGGU`, respons `"notificationQueued": true`.
- Audit `"Ubah status {number} → {label}[ · WA dijadwalkan]"`.

```json
{ "result": "ADVANCED", "order": { … "status": "SIAP", "waStatus": "MENUNGGU" }, "notificationQueued": true }
```

- Menjadi `SIAP` untuk customer yang tidak opt-in / walk-in → `wa_status = BELUM_OPTIN`, tanpa pesan. Setiap
  perubahan `wa_status` menulis `order_events` `WA_STATUS_CHANGED`.
- `advance` tidak memakai `Idempotency-Key` — `fromStatus` sudah melindungi dari pengiriman ganda.

#### `GET /orders/{id}` · `GET /orders/next-number`

- `GET /orders/{id}` → `{ order, events: [{ id, type, fromStatus, toStatus, staffId, deviceId, createdAt, payload }] }`,
  event terlama dulu; kasir hanya cabangnya (`403 BRANCH_SCOPE`).
- `GET /orders/next-number` → `{ number, businessDate }` (perkiraan, §7.3).

### 8.7 Shift & kas

| Method & path | Akses | Fungsi | Use case |
|---|---|---|---|
| `GET /shifts/current?branchId=` | K/O | Shift terakhir (terbuka/tertutup) + mutasi kas + `expectedCash` | `ShiftViewModel` |
| `GET /shifts/latest` | K/O | Shift terakhir per cabang (map) — shell, cabang, dashboard | `observeLatestShifts` |
| `POST /shifts` | K/O | Buka shift | `OpenShiftUseCase` |
| `POST /shifts/{id}/cash-entries` | K/O | Kas masuk/keluar | `RecordCashEntryUseCase` |
| `PUT /shifts/{id}/counted-cash` | K/O | Simpan hitungan uang fisik (klien *debounce* ≥ 500 ms) | `updateCountedCash` |
| `POST /shifts/{id}/close` | K/O | Tutup shift & bekukan rekap | `CloseShiftUseCase` |
| `GET /shifts?branchId=&from=&to=` | O | Riwayat shift (P1) | *(baru)* |

`POST /shifts` — body `{ "openingCash": 500000, "staffProof": "sp_…" }`:

- Cabang aktif ("Cabang {nama} nonaktif — shift baru tidak bisa dibuka sampai owner mengaktifkan cabang.").
- Tidak ada shift terbuka (409 `SHIFT_ALREADY_OPEN` "Shift Cabang {nama} masih terbuka — tutup dulu sebelum membuka
  yang baru."). Dijamin unique index parsial walau dua perangkat membuka bersamaan.
- `openingCash > 0` ("Modal awal belum diisi.").
- `staffProof` valid dan belum dipakai (422 `STAFF_PROOF_INVALID` "Verifikasi PIN sudah kedaluwarsa — pilih staff dan
  masukkan PIN lagi." bila kosong, tidak dikenal, sudah dipakai, kedaluwarsa, dari tablet/cabang lain, atau staff-nya
  kini nonaktif/pindah cabang) → staff pembuka = staff pada proof. Proof baru ditandai terpakai setelah semua aturan
  lain lolos, di transaksi yang sama — pembukaan yang ditolak tidak menghanguskannya.
- Bila staff pada proof **bukan** pemilik sesi tablet, tablet diserahkan kepadanya (setara
  `sessionRepository.update { staffId = staff.id }`): sesi lama berakhir, respons `session` berisi token baru (bentuk
  sama dengan `pin-login`), audit `STAFF_SWITCHED`. Bila sama, `session = null`. Respons yang diputar ulang lewat
  `Idempotency-Key` selalu `session = null`.
- Respons `201 { shift, session }`.
- Efek: shift (`counted_cash` awal = modal awal), `cash_entries` `OPENING` "Modal awal shift" / "Diinput {nama}",
  audit `"Buka shift Cabang {nama} · modal awal Rp500.000"`.

`POST /shifts/{id}/cash-entries` — body `{ "direction": "IN" | "OUT", "label": "beli deterjen", "amount": 180000 }`:
shift terbuka ("Shift Cabang {nama} belum dibuka."), label wajib ("Keterangan wajib diisi untuk audit trail."),
amount > 0 ("Jumlah belum diisi."). Urutan: shift ada (404 "Shift tidak ditemukan.") → kasir hanya cabangnya (403) →
shift terbuka → label → amount. Disimpan dengan label `"Kas masuk — …"` / `"Kas keluar — …"`, amount bertanda, note
`"Diinput {nama}"`, audit `"Kas keluar Rp180.000 — beli deterjen"`. Respons `201 { entry, shift }`.

`PUT /shifts/{id}/counted-cash` — body `{ "countedCash": 1600000 }` → `{ shift }`. Hitungan sementara, **tidak
diaudit** (hitungan final diaudit saat tutup); negatif → 400; shift tertutup → 409 `SHIFT_CLOSED`; tanpa
`Idempotency-Key` (idempoten secara alami).

`POST /shifts/{id}/close` — body `{ "countedCash": 1624000 }`: shift masih terbuka (409 "Shift Cabang {nama} sudah
ditutup."); simpan `counted_cash`, hitung rekap (§9.3), isi `closed_at`, `closed_by_staff_id`; audit `"Tutup shift
Cabang {nama} · kas cocok"` atau `"… · selisih Rp8.000"`. Respons `{ "shift", "recap": { "expected", "actual", "diff" } }`.

**Bentuk `Shift`**: `{ id, branchId, open, openedByStaffId, openedByName, openedAt, closedAt, closedByStaffId,
openingCash, cashSales, transferSales, txCount, pointsIssued, countedCash, expectedCash, recap, version }` —
`expectedCash` dihitung saat dibaca (§9.3), `recap` dibekukan saat tutup (`null` selama terbuka). `CashEntry` =
`{ id, shiftId, kind, label, note, amount, orderId, staffId, createdAt }`. `GET /shifts/current` → `{ shift | null,
entries }` (shift terakhir, terbuka atau tertutup; entri terlama dulu); `GET /shifts/latest` → `{ items }`;
`GET /shifts` → `{ items, nextCursor }`, `from`/`to` = tanggal bisnis `openedAt`.

### 8.8 WhatsApp

| Method & path | Akses | Fungsi | Use case |
|---|---|---|---|
| `GET /wa/failures?branchId=` | K/O | Pesan `FAILED` dengan `resolution = NONE` (kasir: cabangnya; owner: semua) | `observeFailures` |
| `POST /wa/failures/{id}/retry` | K/O | Kirim ulang satu | `ResolveWaFailuresUseCase(resent = true)` |
| `POST /wa/failures/retry` | K/O | Kirim ulang banyak `{ "ids": [...] }` | "Kirim ulang semua" |
| `POST /wa/failures/{id}/manual-follow-up` | K/O | Tandai sudah ditelepon | `ResolveWaFailuresUseCase(resent = false)` |
| `GET /wa/summary?branchId=&from=&to=` | K/O | `{ delivered, failed, pending, deliveryRate, p95DeliverySeconds }` | kartu delivery rate |
| `GET /wa/templates` | K/O | Daftar template + pemicu (tab Preview) | `TemplateNotes` |
| `GET /webhooks/whatsapp` | Pub (verify token) | Verifikasi webhook Meta (`hub.challenge`) | — |
| `POST /webhooks/whatsapp` | Pub (tanda tangan) | Status pengiriman & pesan masuk | — |

Item failure: `{ id, branchId, createdAt, customerName, phone, orderId, orderNumber, template, reason }` — kompatibel
dengan `WaFailure` klien (tambahan `orderNumber`, `id` menjadi UUID).

Kirim ulang membuat baris `wa_messages` baru (`QUEUED`), menandai yang lama `resolution = RESENT`, dan mengubah
`wa_status` order ke `MENUNGGU` — **bukan** langsung `TERKIRIM` (T6). Audit per cabang `"Kirim ulang WA ke {nama,
nama}"` / `"Follow up manual via telepon: {nama}"`. Respons `{ "resolved": n }`.

### 8.9 Laporan & audit (owner)

| Method & path | Fungsi |
|---|---|
| `GET /reports/dashboard?branchId=&date=&periodDays=30&auditRangeDays=1` | Semua data halaman Laporan (§9.4) |
| `GET /reports/daily-sales?branchId=&from=&to=` | Omzet & jumlah transaksi per hari per cabang |
| `GET /audit?branchId=&staffId=&actionType=&from=&to=&cursor=` | Log audit (P0 #12: filter per staff & rentang tanggal). `branchId` kosong = semua; entri `branchId = null` selalu ikut |

### 8.10 Sinkronisasi data untuk cache klien

| Method & path | Akses | Fungsi |
|---|---|---|
| `GET /sync/bootstrap` | K/O | Snapshot awal: cabang, staff (field publik), layanan, reward, rate, customer, order aktif cabang, shift terakhir, WA gagal (mulai M5) |
| `GET /sync/changes?since=<cursor>` | K/O | Perubahan sejak cursor per koleksi → `{ changes: { branches: [...], services: [...], … }, nextCursor, hasMore }` |
| `POST /devices/heartbeat` | K/O (`X-Device-Id`) | `{ pendingCount, appVersion, online }` tiap 60 detik — dipakai `pendingSync` di dashboard |

Cursor opaque berbasis **id transaksi database** (*watermark*), bukan `updated_at`. `updated_at = now()` adalah waktu
*mulai* transaksi: transaksi yang mulai lebih dulu tetapi commit belakangan akan tertinggal di belakang cursor
`updated_at` dan perubahannya tidak pernah sampai ke tablet. Setiap baris yang disinkron membawa id transaksi penulisnya
(`change_xid`); cursor = transaksi tertua yang masih berjalan saat server membaca. Akibatnya tidak ada perubahan yang
hilang, tetapi satu baris kadang terkirim dua kali — **klien wajib upsert** berdasarkan `id`. Transaksi database yang
sangat lama menahan watermark, jadi transaksi *idle* dipantau di produksi. Klien memanggil `changes` saat app aktif
(tiap 15–30 detik) dan setelah setiap mutasi, lalu menulis ke Room sehingga `Flow` di UI ikut terbarui.

- Bentuk: `bootstrap` → `{ data: { branches, staff, services, rewards, loyaltyRates, customers, orders, shifts },
  cursor }`; `changes` → `{ changes: { …koleksi yang sama… }, nextCursor, hasMore }`, maksimal 500 baris per halaman
  (`hasMore = true` → panggil lagi segera). Staff hanya `{ id, name, shortName, role, branchId, active }`.
- Scope: order = cabang sesi tablet; staff & shift — kasir: cabangnya (+ owner), owner: semua; cabang, katalog
  (termasuk nonaktif), rate, dan customer global. Pada skala 10× customer penuh di bootstrap perlu ditinjau ulang.
- Cursor yang tidak dikenal → 400 "Cursor tidak valid — muat ulang data dari awal (bootstrap)."
- Heartbeat: `pendingCount` wajib (≥ 0); respons `{ serverTime }` agar tablet bisa mendeteksi jam yang salah sebelum
  mencap `capturedAt` offline. Tidak diaudit; `online` belum disimpan. Server-Sent Events untuk push real-time
dicatat sebagai P1.

---

## 9. Perhitungan & aturan lintas modul

### 9.1 Total & poin (dari `CalculateCartTotalsUseCase` & `PlaceOrderUseCase`)

```
item.subtotal   = round_half_up(qty × unitPrice)          // hitung dengan NUMERIC, bukan float
subtotal        = Σ item.subtotal
discount        = reward ? min(reward.value, subtotal) : 0
total           = subtotal − discount
earnedPoints    = customer ? floor(total / rate.rupiahPerStep) × rate.pointsPerStep : 0
pointsDelta     = earnedPoints − (reward ? reward.cost : 0)
waStatus awal   = customer?.optIn ? MENUNGGU : BELUM_OPTIN
customerName    = customer?.name ?: "Tanpa nama";  customerPhone = customer?.phone ?: "—"
```

`rate` = rate yang berlaku pada `capturedAt`. Contoh: Cuci Setrika 4,5 kg × Rp10.000 = Rp45.000; redeem "Gratis Cuci
Kering 2 kg" (1.500 poin, nilai Rp14.000) → total Rp31.000 → +300 poin → saldo 2.340 − 1.500 + 300 = 1.140.

### 9.2 Status order

```
DITERIMA ──▶ PROSES ──▶ SIAP ──(WA status_siap_diambil bila opt-in)──▶ SELESAI
```

### 9.3 Rekonsiliasi kas (dari `Shift.expectedCash`)

```
expectedCash = cashSales + Σ amount(cash_entries WHERE kind ≠ SALE)
             = modal awal + penjualan tunai + kas masuk − kas keluar
diff         = countedCash − expectedCash        // negatif = kurang
```

Penjualan tunai tidak dijumlah dua kali: entri `SALE` hanya catatan, nilainya sudah ada di `cashSales`.

### 9.4 Dashboard (dari `ComputeDashboardUseCase`)

`scope` = `branchId` atau semua cabang. `date` default hari ini (WIB). `period` = `periodDays` terakhir s.d. `date`
(default 30 — T11).

| Field | Definisi |
|---|---|
| `revenueToday`, `txToday` | `daily_sales` di scope pada `date` |
| `revenueYesterday` | `daily_sales` pada `date − 1` |
| `readyForPickup` | Order `SIAP` di scope (semua tanggal) |
| `staleReady` | Order `SIAP` dengan `status_changed_at` < sekarang − 72 jam |
| `optInRate` | Customer dengan `home_branch_id` di scope: opt-in ÷ total (null bila 0) |
| `deliveryRate` | Pesan `STATUS_SIAP_DIAMBIL` di period: delivered ÷ (delivered + failed) (null bila 0) |
| `redemptionRate` | Order di period dengan `redeemed_points > 0` ÷ order di period |
| `pendingSync` | Σ `devices.pending_count` di scope (heartbeat) |
| `branchRows[]` | Per cabang: omzet & tx pada `date`, `readyForPickup`, shift terakhir, rasio target (omzet ÷ `daily_target`) |
| `chart[]` | 7 hari s.d. `date`: omzet per cabang di scope |
| `topServices[]` | Omzet per nama layanan di period: 4 teratas + "Lainnya" sebagai pangsa (0–1) |
| `audit[]` | Entri `auditRangeDays` (1/7/30) terakhir, cabang di scope atau `branchId = null`, terbaru dulu |

Omzet diakui pada tanggal bisnis `captured_at` — transaksi offline yang tersinkron besok tetap masuk ke hari
transaksinya.

---

## 10. Integrasi WhatsApp

### 10.1 Setup

- Satu nomor bisnis untuk semua cabang (sesuai contoh percakapan di aplikasi); nama cabang & jam buka masuk ke
  variabel template.
- WhatsApp Cloud API resmi Meta, template kategori *Utility*, bahasa `id`. Verifikasi bisnis & approval template
  diajukan di minggu 1–4 (dependency eksternal PRD produk).
- Nomor tujuan dikonversi ke E.164: `0812-3390-4471` → `6281233904471`.
- Kredensial (`WA_ACCESS_TOKEN`, `WA_PHONE_NUMBER_ID`, `WA_APP_SECRET`, `WA_VERIFY_TOKEN`) di secret manager.

### 10.2 Template & pemicu

| Template | Pemicu | Variabel | Prioritas |
|---|---|---|---|
| `opt_in_confirm` | Transaksi pertama customer yang opt-in | nama, cara berhenti (balas STOP) | P0 |
| `struk_digital` | Order tersimpan (dibayar) & customer opt-in | nomor order, ringkasan layanan, total, metode bayar, poin didapat, saldo poin | P1 PRD, **disarankan P0** karena menggantikan struk kertas |
| `status_siap_diambil` | Status → `SIAP` & customer opt-in saat itu | nama, nomor order, nama cabang, jam buka | P0 |
| `reminder_3_hari` | Scheduler 09.00 WIB: order `SIAP` > 72 jam, maksimal 1× per order | nama, nomor order, cabang, jam buka | P1 |

Walk-in (tanpa customer) dan customer belum opt-in tidak pernah dikirimi pesan.

### 10.3 Alur pengiriman

1. Transaksi bisnis menulis `wa_messages` (`QUEUED`) di transaksi DB yang sama.
2. Worker mengambil pesan tiap 2 detik: `SELECT … WHERE status = 'QUEUED' AND next_attempt_at <= now() FOR UPDATE
   SKIP LOCKED LIMIT 20`.
3. Kirim ke Cloud API → sukses: simpan `wamid`, status `SENT`. Gagal:
   - *Retryable* (rate limit mis. `130429`, 5xx, timeout): `attempts + 1`, backoff 15 dtk → 30 dtk → 60 dtk → 2
     mnt → 5 mnt; setelah 5 percobaan → `FAILED`.
   - *Non-retryable* (nomor tidak terdaftar di WA mis. `131026`, nomor diblokir, parameter template salah): langsung
     `FAILED`.
   - Mapping lengkap kode error mengikuti dokumentasi Cloud API; `error_reason` ditulis dalam Bahasa Indonesia
     ("Nomor tidak terdaftar di WhatsApp", "Rate limit Cloud API — coba lagi", "Customer memblokir nomor bisnis").
4. Webhook status memperbarui `DELIVERED` / `READ` / `FAILED` (idempotent per `wamid` + status).
5. `orders.wa_status` mengikuti pesan `status_siap_diambil` terakhir: `QUEUED/SENDING/SENT → MENUNGGU`,
   `DELIVERED/READ → TERKIRIM`, `FAILED → GAGAL`. Setiap perubahan menulis `order_events` `WA_STATUS_CHANGED`.
6. SLA: `delivered_at − order_events(SIAP).created_at` ≤ 120 detik untuk ≥ 95% pesan; alert bila antrean
   `QUEUED` tertua > 60 detik.

### 10.4 Webhook

- `GET`: bila `hub.mode = subscribe` dan `hub.verify_token` cocok → balas `hub.challenge`.
- `POST`: verifikasi `X-Hub-Signature-256` (HMAC-SHA256 body dengan app secret) sebelum parsing; balas `200` cepat
  lalu proses async; simpan payload mentah 30 hari untuk debug.
- Pesan masuk berisi `STOP` / `BERHENTI` (tidak peka huruf besar) → `opt_in = false`, `opt_out_at`, batalkan pesan
  `QUEUED` customer tsb (`CANCELLED`), audit `"Customer {nama} berhenti berlangganan WA"`.
- Pesan masuk lain: lihat keputusan terbuka §16 (inbox balasan customer).

---

## 11. Antrean offline & sinkronisasi (P0 #8)

### 11.1 Aturan di perangkat (untuk tim Android)

- Setiap transaksi offline diberi `clientTxId` (UUID) **saat dibuat**, disimpan bersama `shiftId` yang terbuka,
  `capturedAt`, harga satuan per item, dan data customer baru bila didaftarkan offline.
- Redeem poin tidak boleh offline — aturan klien saat ini dipertahankan, server juga menolaknya (422 "Redeem poin
  butuh koneksi — batalkan redemption atau tunggu online.").
- Sync otomatis saat koneksi kembali dan manual lewat "Sync sekarang"; kirim urut `capturedAt`, maksimal 50 per
  request.
- Tutup shift wajib online; klien sync antrean dulu sebelum memanggil `close`.
- `Idempotency-Key` satu per isi batch: batch yang sama dikirim ulang karena timeout memakai kunci yang sama.
- Bila `serverTime` heartbeat beda jauh dari jam tablet, peringatkan kasir sebelum mencatat transaksi offline.

### 11.2 `POST /orders/sync`

```json
{
  "transactions": [
    {
      "clientTxId": "a1…", "capturedAt": "2026-08-29T03:12:00.000Z", "shiftId": "s1…", "staffId": "u1…",
      "customerId": null,
      "newCustomer": { "id": "c9…", "name": "Rina Kusuma", "phone": "0838-7712-4409", "optIn": true },
      "items": [ { "serviceId": "ck…", "name": "Cuci Kering", "qty": 6, "unit": "kg", "unitPrice": 7000 } ],
      "payment": "TRANSFER", "note": ""
    }
  ]
}
```

```json
{
  "results": [
    { "clientTxId": "a1…", "status": "CREATED",   "order": { … } },
    { "clientTxId": "a2…", "status": "DUPLICATE", "order": { … } },
    { "clientTxId": "a3…", "status": "REJECTED",  "error": { "code": "INVALID_ITEM", "message": "…" } }
  ],
  "summary": { "created": 1, "duplicates": 1, "rejected": 1, "total": 42000, "points": 420 }
}
```

Aturan server (memperbaiki T5):

| Aspek | Aturan |
|---|---|
| Duplikasi | `clientTxId` sudah ada → `DUPLICATE` + order yang ada. Klien menghapus dari antrean untuk `CREATED` dan `DUPLICATE` |
| Kegagalan | Diproses per transaksi; satu gagal tidak menghentikan yang lain. `REJECTED` tetap di antrean klien dengan pesan error |
| Harga | Pakai `unitPrice` snapshot perangkat (customer sudah membayar harga itu). Beda dengan price list pada `capturedAt` (dibangun ulang dari `service_price_history`) → flag `PRICE_MISMATCH` + audit. `unitPrice` ≤ 0 → `REJECTED` `INVALID_ITEM` "Harga {nama} tidak valid." |
| Layanan nonaktif | Diterima (snapshot), flag `SERVICE_INACTIVE` + audit. Layanan yang tidak ada sama sekali atau `qty` tidak valid → `REJECTED` `INVALID_ITEM` |
| Rate poin | Rate yang berlaku pada `capturedAt` |
| Shift | Masuk ke `shiftId` bila shift itu masih terbuka; bila sudah ditutup → shift terbuka cabang saat ini; bila tidak ada → tetap ke `shiftId` asal (tanpa `shiftId`: shift terakhir cabang) dengan flag `LATE_AFTER_SHIFT_CLOSE` + audit + tampil di laporan owner. `shiftId` milik cabang lain diabaikan. Transaksi yang sudah dibayar **tidak pernah ditolak** karena status shift — kecuali cabang belum pernah punya shift sama sekali (`REJECTED` `SHIFT_NOT_OPEN`, tidak ada tempat mencatat uangnya). Transaksi yang masuk ke shift tertutup menambah `cash_sales`/`transfer_sales` shift itu, tetapi rekap yang dibekukan tidak berubah |
| Waktu | `capturedAt` > sekarang + 5 menit → `REJECTED` 422 `CAPTURED_IN_FUTURE` "Waktu transaksi di tablet lebih maju dari jam server — cek jam tablet lalu sync ulang."; lebih lama dari 7 hari → diterima dengan flag `STALE_CAPTURE` |
| Customer baru | Upsert berdasarkan no HP (aturan nama & nomor sama dengan `POST /customers`): nomor sudah ada → pakai customer yang ada, flag `CUSTOMER_PHONE_MATCHED`, `id` klien dipetakan ke `id` server di `customerIdMapping = { clientId, serverId }`. Nomor baru → customer dibuat dengan `id` klien, audit `CUSTOMER_REGISTERED` |
| Staff | `staffId` transaksi dipakai sebagai kasir order bila staff itu boleh bekerja di cabang sesi; bila tidak dikenal, staff sesi yang dipakai |
| Penolakan | Hanya: jam tablet terlalu maju, ada `rewardId` (`REDEEM_OFFLINE`), `items` kosong (`EMPTY_CART`), item tidak valid, `customerId` tidak ada, data customer baru tidak valid, cabang tanpa shift. Format yang salah hanya menolak transaksi itu, bukan satu batch; lebih dari 50 transaksi per request → 400 |
| Nomor order | Berdasarkan tanggal bisnis `capturedAt` |
| Audit | Satu entri ringkasan `"Sync {n} transaksi offline Cabang {nama} · Rp{total} · ID {pertama}–{terakhir}"` + entri per transaksi `"Transaksi {nomor} · Rp{total} · {Tunai|Transfer} · offline[ · ditandai: masuk setelah shift ditutup, harga beda dengan price list, …]"` |

Tiap hasil selalu membawa `clientTxId, status, order, error, customerIdMapping` (yang tidak berlaku `null`);
`error = { code, message }`. Setiap transaksi adalah transaksi database tersendiri.

---

## 12. Keamanan

### 12.1 Model autentikasi

1. **Tanpa aktivasi perangkat** *(revisi 1.1, keputusan owner 14 September 2026)*. Pengguna tablet adalah owner
   lanjut usia dan kasir non-teknis, jadi layar login cukup "pilih cabang → ketik PIN". Aplikasi membuat UUID
   instalasi saat pertama dibuka, menyimpannya permanen, dan mengirimnya sebagai `X-Device-Id`; server mencatat
   tablet pada percobaan login pertamanya. ID ini dipakai untuk kunci PIN per tablet, satu sesi per tablet,
   refresh yang terikat ke tablet, dan pemblokiran tablet hilang oleh owner. *Konsekuensi yang diterima:* login PIN
   bisa dicoba dari luar tablet toko; mitigasinya batas percobaan per alamat jaringan (§12.2) dan PIN owner 6 digit.
2. **Sesi staff.** Login PIN menghasilkan access token JWT (60 menit, claim `sub`, `role`, `branchId`, `deviceId`,
   `sid`) dan refresh token (18 jam sejak login — satu hari operasional; dirotasi setiap dipakai, tidak
   memperpanjang sesi). Sesi dicek ulang ke database di setiap request.
3. **Sesi bertahan saat app ditutup** (sekarang di memori — README "Status"). PIN diminta lagi setelah logout,
   refresh token habis, ganti staff, atau akun dinonaktifkan/di-reset.

### 12.2 PIN (T1, T2)

- Server menerima PIN mentah hanya lewat TLS; tidak pernah di-log (redaksi body endpoint `/auth/*`, `/staff`).
- `pin_lookup = HMAC-SHA256(PIN_PEPPER, pin)` untuk mencari staff dari PIN (login tanpa pilih nama), dengan unique
  index parsial pada staff aktif. `PIN_PEPPER` di secret manager, tidak di database.
- `pin_hash = Argon2id(pin)` untuk verifikasi akhir.
- Karena ruang PIN kecil, perlindungan utama adalah **rate limit**:
  - per tablet (`X-Device-Id`): 5 PIN salah dalam 5 menit → kunci 5 menit (`423`);
  - per alamat jaringan: maksimal 20 percobaan `pin-login` per 5 menit (`429`) — karena ID instalasi bisa
    dikarang ulang;
  - per akun (`verify-pin`, `switch-staff`): 5 salah berturut-turut → kunci 15 menit + audit `PIN_LOCKED`;
  - semua gagal login tercatat di audit (tanpa PIN).
- Hash SHA-256 lama dari perangkat **tidak dimigrasi**; PIN pilot dibuat ulang lewat seed/owner.

### 12.3 Lainnya

- TLS 1.2+, HSTS. Tidak perlu CORS (klien native).
- Otorisasi di setiap endpoint: role + scope cabang dari token, bukan dari body.
- `audit_log` append-only di level hak akses DB.
- Data pribadi: nama & no HP customer. Akses dibatasi role; log aplikasi menyamarkan no HP (`0812-****-4471`).
- Rate limit umum 120 request/menit per perangkat.
- Dependency & container di-scan di CI; secret tidak pernah di repo.

---

## 13. Kebutuhan non-fungsional

| Area | Target |
|---|---|
| Ketersediaan | ≥ 99% jam operasional (07.00–21.00 WIB); maintenance di luar jam itu |
| Latensi | p95 < 300 ms untuk `POST /orders`, `advance`, `GET /customers?q`; p95 < 1 dtk untuk dashboard |
| Kapasitas | 4 cabang × ± 150 transaksi/hari, 10 perangkat; desain aman sampai 10× |
| WA | ≥ 95% `status_siap_diambil` delivered < 2 menit |
| Data | Backup harian + PITR 7 hari; RPO ≤ 5 menit, RTO ≤ 1 jam; retensi order & audit ≥ 5 tahun; payload webhook 30 hari |
| Waktu | Simpan UTC; aturan bisnis harian di Asia/Jakarta; server & DB tidak bergantung pada zona sistem |
| Observability | Log terstruktur dengan `requestId`; metrik latensi/error per endpoint, kedalaman antrean WA, latensi pengiriman, login gagal; alert: 5xx > 1% dalam 5 menit, antrean WA macet > 60 dtk, failure WA > 5% per jam |
| Lingkungan | `dev`, `staging` (dipakai untuk dry-run/shadow mode 3–5 hari sebelum cutover), `prod` |
| Testing | Unit test semua aturan; integration test dengan Postgres (Testcontainers); contract test terhadap OpenAPI; mock Cloud API; uji konkurensi (dua perangkat buka shift / majukan status bersamaan; sync ganda) |
| Dokumentasi | `openapi.yaml` di repo backend, diperbarui di setiap PR |

---

## 14. Dampak ke aplikasi Android

Bagian ini untuk perencanaan tim Android. Library baru (mis. penyimpanan terenkripsi) perlu disetujui dulu sesuai
`CLAUDE.md`.

| # | Perubahan | Lokasi |
|---|---|---|
| A1 | Repository menjadi *remote + cache Room*; interface `:domain` tetap, implementasi baru di `:data/remote` memakai `safeRequest` | `data/repository/*`, `data/remote/*` |
| A2 | Aturan bisnis di use case tetap untuk tampilan, tetapi hasil server yang disimpan | `domain/usecase/*` |
| A3 | Buat UUID instalasi saat pertama dibuka, simpan permanen, kirim sebagai `X-Device-Id` di setiap request (tanpa layar aktivasi) | `:data`, `:feature:auth` |
| A4 | `SessionRepository` menyimpan token secara persisten dan refresh otomatis | `data/repository/Repositories.kt` |
| A5 | Hapus hashing PIN lokal & auto-submit `matchesActivePin`; submit saat tombol ditekan atau saat 6 digit | `LoginWithPinUseCase`, `LoginViewModel` |
| A6 | `PendingTransaction` + `clientTxId`, `shiftId`, harga satuan, customer baru; sync pakai `POST /orders/sync` dan lanjut walau ada yang gagal | `PendingTransactionRepository`, `SyncPendingTransactionsUseCase` |
| A7 | Parse amplop error; untuk 409/422 tampilkan `error.message` apa adanya (sekarang 422 dipetakan ke "The submitted data is invalid") | `core/network/AppErrorExt.kt` |
| A8 | Toast status "siap diambil": "WA dijadwalkan", bukan "WA terkirim"; kirim ulang → `MENUNGGU` | `OrdersViewModel`, `WhatsAppViewModel` |
| A9 | `Order.id` UUID + `number` untuk tampilan & pencarian | model `Order`, UI order |
| A10 | Laporan diambil dari `GET /reports/dashboard`; `ComputeDashboardUseCase` bisa dihapus | `:feature:dashboard` |
| A11 | Impor customer mengunggah file ke server; preview dari server | `:feature:customer` |
| A12 | Hitung uang fisik: `PUT counted-cash` dengan debounce; tutup shift kirim `countedCash` | `ShiftViewModel` |
| A13 | Hapus `DatabaseSeeder` di build produksi; data pilot di-seed di server | `data/seed` |
| A14 | Isi `BASE_URL` per build type (staging/prod) | `data/build.gradle.kts` |
| A15 | Buka shift: `verify-pin` → `POST /shifts`; ganti semua token bila respons membawa `session` (tablet diserahkan ke pembuka shift) | `OpenShiftUseCase`, `SessionRepository` |
| A16 | Cache Room dari `GET /sync/bootstrap` + `GET /sync/changes` dengan **upsert** (baris bisa datang dua kali); heartbeat tiap 60 detik | `data/repository/*`, `ShellViewModel` |

---

## 15. Rencana rilis backend

Diselaraskan dengan timeline 13 minggu di PRD produk. **Revisi 1.2: integrasi Meta/WhatsApp dikerjakan paling akhir.**
Sejak M2 setiap aksi bisnis tetap menulis baris `wa_messages` `QUEUED` di transaksinya (outbox tidak ditunda); yang
ditunda hanya pengirimnya. M1–M4 termasuk shadow mode berjalan tanpa kredensial Meta, dan tidak ada yang menandai
pesan terkirim tanpa pengiriman nyata (T6). Verifikasi bisnis Meta, pendaftaran nomor, dan approval template tetap
diajukan sejak M0 sebagai jalur paralel karena butuh waktu di sisi Meta.

| Minggu | Milestone | Isi | Selesai bila |
|---|---|---|---|
| 1–2 | **M0 Fondasi** | Repo, CI, container, Postgres + migrasi, seed pilot, draft OpenAPI, pengajuan verifikasi Meta | Staging hidup; skema lolos review |
| 3–4 | **M1 Identitas & master data** | Login PIN tanpa aktivasi, refresh/switch/logout, cabang, staff, price list, loyalty, reward, customer, audit | Login tablet ke staging; semua CRUD owner lolos contract test |
| 5–7 | **M2 Transaksi** | Order + event layer + nomor order, advance status, shift & kas, sync offline, delta sync, heartbeat, penulisan outbox WA | Skenario §16 #1–#11 lolos (untuk #8 sampai pesan `QUEUED`); uji konkurensi lolos |
| 8–9 | **M3 Laporan & impor** | Dashboard, audit filter, impor CSV, error report, hardening, load test, security review | Metrik dashboard cocok dengan hitungan manual |
| 10 | **M4 Shadow mode** | Dry-run 3–5 hari paralel dengan SaaS lama di staging/prod + UAT, belum mengirim WA | Rekonsiliasi harian cocok; 0 bug kritis |
| 11–12 | **M5 Integrasi Meta/WhatsApp** | Worker, Cloud API client, retry, webhook, opt-out STOP, failures API, summary, scheduler pengingat; batalkan (`CANCELLED` + audit) antrean `QUEUED` lama sebelum worker dinyalakan | Pesan nyata terkirim < 2 menit di nomor uji; skenario §16 #8, #17, #18 lolos |
| 13 | **M6 Go-live** | Cutover cabang pilot, monitoring intensif 2 minggu | SLA §13 terpenuhi |

## 16. Kriteria penerimaan (skenario uji utama)

| # | Given | When | Then |
|---|---|---|---|
| 1 | Shift Familia Urban terbuka, customer Dewi 2.340 poin, rate Rp10.000 = 100 | Kasir menyimpan Cuci Setrika 4,5 kg, Tunai, redeem 1.500 poin | Order `FMU-MMdd-seq` status `DITERIMA`, total Rp31.000, +300 poin, saldo 1.140, kas `SALE` Rp31.000, `cash_sales` +31.000, audit tercatat, outbox `struk_digital` |
| 2 | Tidak ada shift terbuka | `POST /orders` | 422 `SHIFT_NOT_OPEN` dengan pesan cabang |
| 3 | Request order yang sama dikirim 2× (retry jaringan) | Idempotency-Key sama | Hanya 1 order; respons kedua identik |
| 4 | Owner mengubah harga Cuci Setrika jadi Rp11.000 | Kasir mengirim order dengan `unitPrice` 10.000 | 409 `PRICE_CHANGED` berisi harga terbaru; order lama tetap Rp10.000 |
| 5 | Owner mengubah rate jadi Rp10.000 = 150 poin pukul 12.00 | Transaksi offline pukul 11.00 disinkron pukul 13.00 | Poin dihitung dengan rate lama (100) |
| 6 | 3 transaksi offline, yang ke-2 berisi layanan tidak dikenal | `POST /orders/sync` | #1 dan #3 `CREATED`, #2 `REJECTED`; sync ulang #1 → `DUPLICATE` |
| 7 | Shift ditutup sebelum antrean offline tersinkron | Sync | Order tetap tercatat, flag `LATE_AFTER_SHIFT_CLOSE`, tampil di laporan owner |
| 8 | Order `PROSES`, customer opt-in | Kasir majukan status | Status `SIAP`, event tercatat, pesan `status_siap_diambil` `QUEUED` → delivered < 2 menit → `wa_status = TERKIRIM` |
| 9 | Dua tablet memajukan order yang sama bersamaan | Keduanya kirim `fromStatus = PROSES` | Satu sukses, satu 409 `STATUS_CHANGED` |
| 10 | Dua tablet membuka shift cabang yang sama bersamaan | `POST /shifts` | Satu sukses, satu 409 `SHIFT_ALREADY_OPEN` |
| 11 | Modal Rp500.000, tunai Rp2.242.000, kas keluar Rp1.180.000, kas masuk Rp62.000, hitungan Rp1.624.000 | Tutup shift | expected Rp1.624.000, diff 0, audit "kas cocok" |
| 12 | Kasir Familia Urban | Login PIN di perangkat Narogong | 422 `STAFF_WRONG_BRANCH`, PIN dikosongkan |
| 13 | Owner reset PIN Bagas | Bagas login dengan PIN lama | Ditolak; sesi Bagas yang aktif ikut dicabut |
| 14 | 5 PIN salah dari satu perangkat | Percobaan ke-6 | 423 `PIN_LOCKED` + `Retry-After` |
| 15 | Yuni nonaktif dan PIN-nya kini dipakai staff aktif | Owner mengaktifkan Yuni | 409 `PIN_CONFLICT` |
| 16 | CSV 10 baris: 7 valid, 1 nomor sudah ada, 1 nomor 9 digit, 1 kode cabang salah | Upload → commit | Preview 7/1/2; commit 7 customer, `optIn = false`, poin ke ledger `IMPORT`, error report 3 baris |
| 17 | Customer membalas "STOP" | Webhook diterima | `opt_in = false`, pesan antre dibatalkan, audit tercatat |
| 18 | Nomor customer tidak terdaftar di WA | Worker mengirim | `FAILED` tanpa retry, muncul di `/wa/failures` cabang tsb dengan alasan Bahasa Indonesia |
| 19 | Kasir | `GET /reports/dashboard` | 403 |
| 20 | Owner memilih audit "7 hari" cabang Familia Urban | `GET /audit?branchId=…&from=…` | Entri Familia Urban + entri semua cabang (`branchId = null`), terbaru dulu, bisa difilter per staff |

---

## 17. Keputusan terbuka

| # | Pertanyaan | Rekomendasi | Pemutus |
|---|---|---|---|
| Q1 | Format nomor order tanpa tahun (T4) | `id` UUID + `number` tetap `FMU-0829-015`, unik per (cabang, tanggal) | Owner + Android |
| Q2 | Login saat perangkat offline di awal hari | Pilot: login wajib online; sesi bertahan 18 jam sehingga putus koneksi di tengah hari tidak mengganggu. Login offline (cache verifier per perangkat) jadi P1 | Owner |
| Q3 | Impor Excel `.xlsx` (PRD P0 #9 menyebut Excel/CSV; klien sekarang menolak Excel) | Server menerima `.xlsx` (sheet pertama, kolom sama) — tambahan kecil di backend, mengurangi langkah staff | Owner |
| Q4 | Syarat minimum belanja reward (T9) | Tambah `minSubtotal` opsional, ditegakkan server | Owner |
| Q5 | Void/koreksi order (T8) | P1: void oleh owner dengan alasan wajib; membalik poin, kas, penjualan harian; audit | Owner |
| Q6 | Balasan customer di nomor WA bisnis — nomor yang dipakai Cloud API tidak bisa sekaligus dipakai di aplikasi WhatsApp Business biasa kecuali fitur *coexistence* tersedia | Pilot: simpan pesan masuk dan tampilkan di tab WA per cabang (read + balas teks dalam jendela 24 jam) — atau aktifkan coexistence bila tersedia untuk nomor ini | Owner + backend |
| Q7 | `struk_digital` P0 atau P1 | P0 — PRD menyebut struk digital sebagai pengganti permanen struk kertas | Owner |
| Q8 | Aktivasi ulang staff dengan PIN bentrok | Tolak (409) dan minta reset — lebih sederhana daripada reset otomatis | Owner |
| Q9 | Transaksi offline setelah shift ditutup | Diterima dengan flag dan tampil di laporan (§11.2) | Owner |
| Q10 | Stack & hosting | Kotlin/Ktor + PostgreSQL terkelola (§5.1) | Tim backend |
| Q11 | Retensi data (audit, order, payload WA) | Order & audit 5 tahun, payload webhook 30 hari | Owner |
| Q12 | Pilot 1 cabang vs aplikasi multi-cabang (T12) | Skema multi-cabang; pilot = hanya cabang pilot yang `active` | Owner |

---

## Lampiran A — Enum

| Enum | Nilai |
|---|---|
| `Role` | `KASIR`, `OWNER` |
| `ServiceCategory` | `KILOAN_REGULER` (Kiloan Reguler), `KILOAN_EXPRESS` (Kiloan Express), `SATUAN` (Satuan), `DRY_CLEAN` (Dry Clean) |
| `OrderStatus` | `DITERIMA` (Diterima), `PROSES` (Diproses), `SIAP` (Siap diambil), `SELESAI` (Selesai) |
| `WaStatus` | `TERKIRIM`, `MENUNGGU`, `GAGAL`, `BELUM_OPTIN` |
| `PaymentMethod` | `TUNAI`, `TRANSFER` (fase 2: `QRIS`) |
| `CashEntryKind` | `OPENING`, `CASH_IN`, `CASH_OUT`, `SALE` |
| `OrderSource` | `ONLINE`, `OFFLINE_SYNC` |
| `OrderFlag` | `LATE_AFTER_SHIFT_CLOSE`, `PRICE_MISMATCH`, `SERVICE_INACTIVE`, `STALE_CAPTURE`, `CUSTOMER_PHONE_MATCHED` |
| `OrderEventType` | `CREATED`, `STATUS_CHANGED`, `WA_STATUS_CHANGED` |
| `SyncResultStatus` | `CREATED`, `DUPLICATE`, `REJECTED` |
| `PointsLedgerType` | `EARN`, `REDEEM`, `IMPORT`, `ADJUST` |
| `WaTemplate` | `OPT_IN_CONFIRM`, `STRUK_DIGITAL`, `STATUS_SIAP_DIAMBIL`, `REMINDER_3_HARI` |
| `WaMessageStatus` | `QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`, `CANCELLED` |
| Unit layanan | `kg` (step 0,5), `pcs`, `pasang`, `m²` (step 1) |

## Lampiran B — Kode error bisnis

| Code | HTTP | Contoh pesan |
|---|---|---|
| `BRANCH_REQUIRED` | 422 | Pilih cabang perangkat dulu. |
| `BRANCH_INACTIVE` | 422 | Cabang Cipete sedang nonaktif — pilih cabang lain atau hubungi owner. |
| `BRANCH_IN_USE` | 422 | Tidak bisa menonaktifkan cabang yang sedang dipakai perangkat ini. |
| `DEVICE_UNAUTHORIZED` | 401 | Tablet ini sudah diblokir owner — hubungi owner untuk memakai tablet lain. |
| `PIN_FORMAT` | 422 | PIN minimal 4 digit. / PIN harus 4–6 digit. |
| `PIN_UNKNOWN` | 422 | PIN tidak dikenali. Coba lagi atau minta owner reset PIN. |
| `PIN_WRONG` | 422 | PIN salah untuk Bagas Ardhana. Coba lagi. |
| `PIN_TAKEN` | 422 | PIN ini sudah dipakai staff lain — pilih kombinasi lain. |
| `PIN_CONFLICT` | 409 | PIN Yuni Astari sudah dipakai staff lain — reset PIN setelah akun diaktifkan. |
| `PIN_LOCKED` | 423 | Terlalu banyak PIN salah. Coba lagi dalam 5 menit. |
| `STAFF_INACTIVE` | 422 | Akun Yuni Astari nonaktif — PIN lama sudah diblokir. |
| `STAFF_WRONG_BRANCH` | 422 | Siti Nurhaliza terdaftar di Cabang Familia Urban — tidak bisa login di perangkat Cabang Narogong. |
| `STAFF_SELF_DEACTIVATE` | 422 | Tidak bisa menonaktifkan akun yang sedang dipakai. |
| `LAST_OWNER` | 422 | Minimal harus ada satu owner aktif. |
| `CASHIER_NEEDS_BRANCH` | 422 | Kasir harus ditempatkan di satu cabang. |
| `OWNER_ONLY` | 403 | Hanya owner/admin yang bisa memindahkan perangkat ke cabang lain. |
| `STAFF_REQUIRED` | 422 | Pilih staff dulu. |
| `STAFF_PROOF_INVALID` | 422 | Verifikasi PIN sudah kedaluwarsa — pilih staff dan masukkan PIN lagi. |
| `SHIFT_NOT_OPEN` | 422 | Shift Cabang Familia Urban belum dibuka — buka shift dulu sebelum mencatat transaksi. |
| `SHIFT_ALREADY_OPEN` | 409 | Shift Cabang Familia Urban masih terbuka — tutup dulu sebelum membuka yang baru. |
| `SHIFT_CLOSED` | 409 | Shift Cabang Familia Urban sudah ditutup. |
| `OPENING_CASH_REQUIRED` | 422 | Modal awal belum diisi. |
| `CASH_LABEL_REQUIRED` | 422 | Keterangan wajib diisi untuk audit trail. |
| `AMOUNT_REQUIRED` | 422 | Jumlah belum diisi. |
| `EMPTY_CART` | 422 | Belum ada layanan di order ini. |
| `INVALID_ITEM` | 422 | Layanan Gorden sudah tidak tersedia. |
| `PRICE_CHANGED` | 409 | Harga Cuci Setrika baru saja diubah owner — cek ulang total sebelum bayar. |
| `TOTAL_MISMATCH` | 409 | Total berubah — muat ulang keranjang. |
| `INSUFFICIENT_POINTS` | 422 | Saldo poin belum cukup untuk reward ini. |
| `REWARD_NOT_ELIGIBLE` | 422 | Reward ini butuh minimum belanja Rp75.000. |
| `REDEEM_OFFLINE` | 422 | Redeem poin butuh koneksi — batalkan redemption atau tunggu online. |
| `CAPTURED_IN_FUTURE` | 422 | Waktu transaksi di tablet lebih maju dari jam server — cek jam tablet lalu sync ulang. |
| `DUPLICATE_TRANSACTION` | 409 | Transaksi ini sudah tersimpan sebagai FMU-0915-001 — muat ulang daftar order. |
| `STATUS_CHANGED` | 409 | Status order sudah diubah dari perangkat lain. |
| `PHONE_INVALID` | 422 | Nomor WhatsApp belum valid (minimal 10 digit, diawali 08). |
| `PHONE_ALREADY_REGISTERED` | 409 | Nomor ini sudah terdaftar (bisa dari cabang lain) — pakai pencarian untuk memilihnya. |
| `NAME_REQUIRED` | 422 | Nama customer wajib diisi. / Nama layanan wajib diisi. / Nama staff wajib diisi. |
| `SERVICE_NAME_TAKEN` | 422 | Layanan dengan nama ini sudah ada di price list. |
| `PRICE_REQUIRED` | 422 | Harga layanan tidak boleh kosong. |
| `RATE_INVALID` | 422 | Nominal belanja minimal Rp1.000. / Poin didapat minimal 1. |
| `REWARD_INVALID` | 422 | Poin dan nilai diskon wajib diisi. |
| `TARGET_REQUIRED` | 422 | Target harian tidak boleh kosong — dikembalikan ke Rp5.200.000. |
| `IMPORT_EMPTY` | 422 | Tidak ada baris valid untuk diimpor. |
| `IMPORT_EXCEL` | 422 | File Excel belum didukung — simpan sebagai CSV dari Excel lalu unggah ulang. |
| `IMPORT_EXPIRED` | 409 | Preview impor kedaluwarsa — unggah ulang file. |

Variasi pesan untuk kode yang sama: `STAFF_INACTIVE` di `verify-pin`/`switch-staff` "Akun {nama} nonaktif — PIN lama
tidak bisa dipakai."; `INVALID_ITEM` "Layanan yang dipilih sudah tidak tersedia." / "Jumlah {nama} tidak valid." /
"Harga {nama} tidak valid." (sync); `REWARD_NOT_ELIGIBLE` "Reward ini sudah tidak tersedia."; `NAME_REQUIRED` "Nama
reward wajib diisi."; `PRICE_REQUIRED` (tambah layanan) "Harga per unit belum diisi."; `SHIFT_NOT_OPEN` (kas) "Shift
Cabang {nama} belum dibuka."; `BRANCH_INACTIVE` (buka shift) "Cabang {nama} nonaktif — shift baru tidak bisa dibuka
sampai owner mengaktifkan cabang.".

**Kode transport**

| Code | HTTP | Pesan |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Data yang dikirim tidak lengkap atau formatnya salah. (atau pesan spesifik: "Parameter limit harus angka 1–100.", "Cursor tidak valid — muat ulang daftar dari awal.", "Format tanggal harus YYYY-MM-DD.", "Maksimal 50 transaksi per sync.", "Hitungan uang fisik tidak boleh negatif.", "ID customer dari perangkat sudah dipakai customer lain.") |
| `UNAUTHENTICATED`, `TOKEN_EXPIRED` | 401 | Sesi berakhir — silakan login ulang dengan PIN. |
| `FORBIDDEN` | 403 | Fitur ini hanya untuk owner/admin. |
| `BRANCH_SCOPE` | 403 | Kasir hanya bisa mengakses data Cabang Familia Urban. |
| `NOT_FOUND` | 404 | Cabang / Akun / Layanan / Reward / Perangkat / Customer / Order / Shift tidak ditemukan. · Rate poin belum diatur. |
| `IDEMPOTENCY_MISMATCH` | 409 | Permintaan ini memakai Idempotency-Key yang sudah dipakai untuk data lain — buat kunci baru lalu kirim ulang. |
| `APP_UPDATE_REQUIRED` | 426 | Versi aplikasi Prima Wash sudah terlalu lama — perbarui aplikasi dulu sebelum melanjutkan. (`details.minAppVersion`) |
| `RATE_LIMITED` | 429 | Terlalu banyak permintaan dari perangkat ini — tunggu sebentar lalu coba lagi. |
| `INTERNAL_ERROR` | 500 | Terjadi gangguan di server. Coba lagi sebentar lagi. |

## Lampiran C — `action_type` audit

`LOGIN`, `LOGIN_FAILED`, `PIN_LOCKED`, `LOGOUT`, `STAFF_SWITCHED`, `DEVICE_BRANCH_SWITCHED`, `DEVICE_ACTIVATED`,
`DEVICE_REVOKED`, `ORDER_CREATED`, `ORDER_SYNCED`, `ORDER_STATUS_CHANGED`, `REWARD_REDEEMED`, `SHIFT_OPENED`,
`SHIFT_CLOSED`, `CASH_IN`, `CASH_OUT`, `CUSTOMER_REGISTERED`, `CUSTOMER_OPTED_OUT`, `CUSTOMERS_IMPORTED`,
`SERVICE_ADDED`, `SERVICE_PRICE_CHANGED`, `SERVICE_TOGGLED`, `LOYALTY_RATE_CHANGED`, `REWARD_ADDED`,
`REWARD_TOGGLED`, `STAFF_ADDED`, `STAFF_PIN_RESET`, `STAFF_TOGGLED`, `BRANCH_TARGET_CHANGED`, `BRANCH_TOGGLED`,
`WA_RESENT`, `WA_MANUAL_FOLLOW_UP`, `CUSTOMER_UPDATED` (ubah nama / opt-in lewat `PATCH /customers/{id}`; opt-out
memakai `CUSTOMER_OPTED_OUT`).

## Lampiran D — Data seed pilot

Diambil dari `data/seed/DatabaseSeeder.kt`. Hanya master data yang dibawa ke server; order, shift, dan audit contoh
**tidak** di-seed di produksi. PIN di bawah adalah PIN demo dan wajib diganti sebelum go-live.

**Cabang**

| Kode | Nama | Alamat | Telepon | Jam | Target harian |
|---|---|---|---|---|---|
| FMU | Familia Urban | Ruko Arundaya, Jl. Familia Urban Blok DD. 21 | 021-8290-1147 | 07.00 – 21.00 | Rp5.200.000 |
| NRG | Narogong | Jl. Narogong Indah No.12 Blok C 8, RT.005/RW.012 | 021-7345-6620 | 07.00 – 21.00 | Rp3.400.000 |
| CPT | Cipete | Jl. Cipete Raya No.18-19, RT.8/RW.4 | 021-7690-4432 | 08.00 – 20.00 | Rp2.300.000 |

**Price list**

| Kategori | Layanan | Harga | Unit |
|---|---|---|---|
| Kiloan Reguler | Cuci Kering | Rp7.000 | kg |
| Kiloan Reguler | Cuci Setrika | Rp10.000 | kg |
| Kiloan Reguler | Setrika Saja | Rp6.000 | kg |
| Kiloan Express | Cuci Setrika Express 6 Jam | Rp18.000 | kg |
| Kiloan Express | Cuci Setrika Kilat 1 Hari | Rp14.000 | kg |
| Kiloan Express | Setrika Express 3 Jam | Rp11.000 | kg |
| Satuan | Bed Cover | Rp35.000 | pcs |
| Satuan | Selimut | Rp25.000 | pcs |
| Satuan | Jaket / Jas | Rp22.000 | pcs |
| Satuan | Sepatu | Rp35.000 | pasang |
| Satuan | Karpet | Rp20.000 | m² |
| Satuan | Gorden *(nonaktif)* | Rp18.000 | kg |
| Dry Clean | Jas / Blazer | Rp45.000 | pcs |
| Dry Clean | Gaun / Kebaya | Rp55.000 | pcs |
| Dry Clean | Batik Halus | Rp38.000 | pcs |
| Dry Clean | Gorden Dry Clean | Rp30.000 | kg |

**Reward:** Diskon Rp10.000 (1.000 poin, nilai Rp10.000) · Gratis Cuci Kering 2 kg (1.500 poin, Rp14.000) · Diskon
Rp25.000 (2.500 poin, Rp25.000, minimum belanja Rp75.000). **Rate:** Rp10.000 = 100 poin.

**Staff:** Raka Prasetyo (owner, semua cabang) · Siti Nurhaliza, Bagas Ardhana (kasir Familia Urban) · Nia Ramadhani, Fajar
Nugroho (kasir Narogong) · Wulan Sari, Yuni Astari *(nonaktif)* (kasir Cipete).

## Lampiran E — Pemetaan use case klien → endpoint

| Use case (`:domain`) | Endpoint |
|---|---|
| `LoginWithPinUseCase` | `POST /auth/pin-login` |
| `VerifyStaffPinUseCase` | `POST /auth/verify-pin` |
| `SwitchActiveStaffUseCase` | `POST /auth/switch-staff` |
| `SwitchBranchUseCase` | `POST /auth/switch-branch` |
| `LogoutUseCase` | `POST /auth/logout` |
| `ObserveSessionContextUseCase` | `GET /me` (+ token lokal) |
| `CalculateCartTotalsUseCase` | lokal (tampilan) — divalidasi `POST /orders` |
| `NextOrderIdUseCase` | `GET /orders/next-number` (perkiraan) |
| `PlaceOrderUseCase` | `POST /orders` (online) / antrean lokal (offline) |
| `SyncPendingTransactionsUseCase` | `POST /orders/sync` |
| `AdvanceOrderStatusUseCase` | `POST /orders/{id}/advance` |
| `OpenShiftUseCase` | `POST /auth/verify-pin` → `POST /shifts` |
| `CloseShiftUseCase` | `POST /shifts/{id}/close` |
| `RecordCashEntryUseCase` | `POST /shifts/{id}/cash-entries` |
| `RegisterCustomerUseCase` | `POST /customers` |
| `ParseCustomerCsvUseCase` | `POST /customer-imports` |
| `ImportCustomersUseCase` | `POST /customer-imports/{id}/commit` |
| `AddServiceUseCase` / `SavePriceListUseCase` / `ToggleServiceUseCase` | `POST /services` / `PATCH /services/prices` / `PATCH /services/{id}` |
| `SaveLoyaltyRateUseCase` | `PUT /loyalty/rate` |
| `AddRewardUseCase` / `ToggleRewardUseCase` | `POST /rewards` / `PATCH /rewards/{id}` |
| `AddStaffUseCase` / `ResetStaffPinUseCase` / `ToggleStaffActiveUseCase` | `POST /staff` / `POST /staff/{id}/reset-pin` / `PATCH /staff/{id}` |
| `UpdateBranchTargetUseCase` / `ToggleBranchActiveUseCase` | `PATCH /branches/{id}` |
| `ResolveWaFailuresUseCase` | `POST /wa/failures/{id}/retry`, `POST /wa/failures/retry`, `POST /wa/failures/{id}/manual-follow-up` |
| `ComputeDashboardUseCase` | `GET /reports/dashboard` |
