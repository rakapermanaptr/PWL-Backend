# PWL Cashier — Backend REST API Prima Wash

Backend pusat untuk aplikasi kasir Android **Prima Wash** (Kasir Prima Laundry). Menjadi *source of
truth* untuk semua cabang: transaksi, poin loyalty, shift & kas, status order, notifikasi WhatsApp,
impor customer, laporan owner, dan log audit.

Aplikasi tablet saat ini berjalan sepenuhnya lokal (Room). Backend ini menggantikan peran database
perangkat sebagai penyimpan resmi, sementara Room di klien turun peran menjadi **cache + outbox
offline**.

> **Kontrak resmi:** [`PRD Backend — REST API v1`](docs/PRD-Backend-REST-API.md) — salinan di repo
> ini, sumber aslinya ada di repo Android `PrimaWash` (`docs/backend/`). README ini adalah ringkasan
> operasional — bila ada beda, **PRD yang menang**. Saat PRD di repo Android berubah, salin ulang
> berkasnya ke sini di PR yang sama.

## Status

**M2 selesai — transaksi sudah di `main` dan staging.** M1 (identitas & master data: login "pilih cabang → ketik
PIN" tanpa aktivasi perangkat, sesi, cabang, staff, price list, loyalty, reward, customer, audit) dan M2
kini sama-sama berjalan di staging <https://pwl-cashier-staging-rvz75.ondigitalocean.app>. M2 menambah: order online
yang dihitung ulang server (nomor order per tanggal bisnis, event layer, ledger poin, total shift, kas,
`daily_sales`, audit, dan baris outbox WhatsApp `QUEUED` dalam satu transaksi), advance status dengan
`fromStatus`, sinkronisasi antrean offline per transaksi (rate/tanggal/nomor dari `capturedAt`, flag bukan
penolakan), shift & kas dengan rekap, `Idempotency-Key`, cache tablet (`/sync/bootstrap`,
`/sync/changes` dengan watermark id transaksi), dan heartbeat. Skenario PRD §16 #1–#11 lolos (untuk #8 sampai pesan `QUEUED` — pengirimannya M5), termasuk
uji konkurensi paralel. `openapi.yaml` menspesifikasikan semua endpoint M1–M2 dan setiap respons
divalidasi terhadapnya. Keputusan yang terisi saat implementasi sudah diterapkan ke PRD Draft 1.2 (riwayat:
[`docs/prd-gaps-m1.md`](docs/prd-gaps-m1.md), [`docs/prd-gaps-m2.md`](docs/prd-gaps-m2.md)). Berikutnya:
laporan & impor (M3).

| Sudah jalan di M0 | Perintah |
|---|---|
| Migrasi ke Postgres lokal | `./gradlew flywayMigrate` |
| Master data pilot (PRD Lampiran D) | `./gradlew seedPilot` |
| API | `./gradlew run` → `/health`, `/api/v1/…` |
| Unit test aturan bisnis Service | `./gradlew test` |
| Alur API + constraint + konkurensi di Postgres asli | `./gradlew integrationTest` |
| Setiap endpoint terhadap `openapi.yaml` | `./gradlew contractTest` |
| Lint | `./gradlew ktlintCheck detekt` |

| Milestone | Isi | Status |
|---|---|---|
| M0 Fondasi | Repo, CI, container, Postgres + migrasi, seed pilot, draft OpenAPI, staging | ✅ staging aktif, skema V2 di `main` |
| M1 Identitas & master data | Login PIN tanpa aktivasi, cabang, staff, price list, loyalty, reward, customer, audit | ✅ di `main` & staging |
| M2 Transaksi | Order + event layer, nomor order, advance status, shift & kas, sync offline, delta sync, **penulisan baris outbox WA** | ✅ di `main` & staging |
| M3 Laporan & impor | Dashboard, filter audit, impor CSV, error report, load test, security review | 🔨 berikutnya |
| M4 Shadow mode | Dry-run 3–5 hari paralel dengan SaaS lama + UAT (belum mengirim WA) | ⬜ |
| M5 Integrasi Meta/WhatsApp | Worker, Cloud API client, retry, webhook, opt-out STOP, failures API, summary | ⬜ |
| M6 Go-live | Cutover cabang pilot, monitoring intensif 2 minggu | ⬜ |

> **Urutan ini disengaja: integrasi Meta dikerjakan paling akhir.** Sejak M2 setiap aksi bisnis
> tetap menulis baris `wa_messages` berstatus `QUEUED` di dalam transaksinya — itu Invariant 3 dan
> tidak ikut ditunda. Yang ditunda hanya **pengirimnya**. Sampai M5 tidak ada worker yang berjalan,
> jadi pesan menumpuk sebagai antrean yang jujur, bukan ditandai `TERKIRIM` tanpa pernah terkirim
> seperti bug T6 di klien. Konsekuensinya: seluruh M1–M4 bisa dibangun, diuji, dan dijalankan
> shadow mode **tanpa kredensial Meta sama sekali**. Rencana lengkap fase terakhir ada di
> [Integrasi Meta / WhatsApp](#integrasi-meta--whatsapp-fase-akhir) di akhir dokumen.
>
> Satu hal yang **tidak** ikut ditunda: jalur administrasinya. Verifikasi bisnis Meta, pendaftaran
> nomor, dan *approval* template kategori Utility butuh berhari-hari sampai berminggu-minggu di
> sisi Meta. Mulai berkasnya sejak M0 sebagai jalur paralel non-blocking — yang ditunda adalah
> kodenya, bukan antrean persetujuan di Meta.

## Ruang lingkup v1

**Termasuk** — autentikasi perangkat & PIN staff, master data (cabang, staff, price list, loyalty
rate, reward), customer & impor CSV, order + event layer, shift & kas, sinkronisasi antrean offline,
laporan owner, log audit, dan — sebagai fase terakhir — notifikasi WhatsApp (outbox, worker,
webhook, opt-out).

**Tidak termasuk** — payment gateway/QRIS (fase 2, enum sudah disiapkan), tier loyalty, referral,
broadcast/campaign, migrasi histori transaksi SaaS lama, printer thermal, inventory, role Supervisor,
panel admin web terpisah (semua fungsi owner ada di aplikasi tablet).

## Target (SLA)

| Area | Target |
|---|---|
| Ketersediaan | ≥ 99% pada jam operasional 07.00–21.00 WIB |
| Latensi | p95 < 300 ms (`POST /orders`, `advance`, `GET /customers?q`); p95 < 1 dtk (dashboard) |
| WhatsApp | ≥ 95% pesan "siap diambil" *delivered* < 2 menit sejak status berubah — diukur mulai M5 |
| Sync offline | < 1% transaksi gagal sync, **0** order ganda |
| Kapasitas | 4 cabang × ±150 transaksi/hari, 10 perangkat (desain aman sampai 10×) |
| Data | Backup harian + PITR 7 hari; RPO ≤ 5 menit, RTO ≤ 1 jam |

## Stack

| Komponen | Pilihan | Alasan |
|---|---|---|
| Bahasa | Kotlin (JVM 21) | Satu bahasa dengan klien Android; aturan `:domain` bisa diekstrak jadi modul bersama |
| Framework | Ktor Server 3.x (Netty) | Ringan, coroutine-native, sejalan dengan Ktor Client di klien |
| Database | PostgreSQL 16 | Transaksi kuat, partial unique index, `FOR UPDATE SKIP LOCKED` untuk antrean |
| Akses DB | Exposed + HikariCP | SQL eksplisit, transaksi mudah dikendalikan |
| Migrasi | Flyway | Versioned SQL, satu arah, dijalankan saat boot |
| DI | Koin | Sama dengan klien |
| Serialisasi | kotlinx.serialization | camelCase, sama dengan model klien |
| Auth | JWT (access) + refresh token acak; Argon2id + HMAC untuk PIN | §12 PRD |
| Antrean | Tabel outbox di Postgres (tanpa Redis/broker) | Cukup untuk < 1.000 transaksi/hari |
| Test | JUnit5 + Kotest assertions + MockK + Testcontainers | Integration test pakai Postgres asli |
| Dokumentasi | OpenAPI 3.1 (`openapi.yaml`) | Kontrak resmi, diperbarui tiap PR |

## Arsitektur

```
 Tablet cabang (Android)                        Backend pusat (repo ini)
┌──────────────────────────┐  HTTPS/JSON   ┌────────────────────────────────────────┐
│ UI (MVI) → UseCase       │ ────────────▶ │ REST API /api/v1 (auth, validasi, rule)│
│ Repository = Remote+Room │ ◀──────────── │   │                                    │
│  • cache master data     │  delta sync   │   ▼ 1 aksi = 1 transaksi DB            │
│  • outbox offline        │               │ PostgreSQL ── orders, events, outbox   │
│    (clientTxId)          │               │   │                                    │
└──────────────────────────┘               │   ▼ polling FOR UPDATE SKIP LOCKED     │
                                           │ WA worker ──▶ WhatsApp Cloud API (Meta)│ ┐
                                           │   ▲                                    │ │ fase
                                           │ POST /webhooks/whatsapp ◀── Meta       │ │ akhir
                                           │ Scheduler (reminder 3 hari, cleanup)   │ ┘ (M5)
                                           └────────────────────────────────────────┘
```

Kotak bertanda *fase akhir* baru dibangun di M5. Sebelum itu alurnya berhenti di tabel
`wa_messages`: transaksi bisnis tetap menulis barisnya, belum ada yang mengambil.

Tiga prinsip yang menentukan hampir semua keputusan di repo ini:

1. **Server-authoritative.** Klien boleh menghitung total, poin, dan nomor order untuk tampilan;
   server menghitung ulang dan **hasil server yang disimpan serta dikembalikan**.
2. **Satu aksi = satu transaksi database.** Menyimpan order menulis order, item, event, ledger poin,
   reward, shift, kas, penjualan harian, audit, dan outbox WA secara atomik.
3. **Transactional outbox untuk WhatsApp.** Pesan tidak pernah hilang saat worker mati, dan tidak
   pernah terkirim bila transaksi bisnisnya gagal.

## Struktur project

```
pwl-cashier/
├── build.gradle.kts · settings.gradle.kts · gradle/libs.versions.toml
├── docker-compose.yml            # Postgres + adminer untuk dev
├── Dockerfile                    # build multi-stage → image runtime JRE 21
├── .github/workflows/ci.yml      # lint, unit, integration, contract, build image
├── config/detekt/detekt.yml      # aturan detekt di atas config default
├── openapi.yaml                  # kontrak resmi, wajib diperbarui tiap PR
├── docs/                         # salinan PRD + catatan operasional (docs/ops)
└── src/
    ├── main/kotlin/id/primawash/api/
    │   ├── Application.kt        # entry point, install plugin, mount routing
    │   ├── plugins/              # Serialization, StatusPages, Auth, Monitoring, RateLimit,
    │   │                         #   AppVersion (426), DependencyInjection (Koin), Routing
    │   ├── common/               # ErrorEnvelope, BusinessRuleException, Money, WibClock,
    │   │                         #   Idempotency, Pagination, AuditWriter
    │   ├── db/                   # Database.kt (Hikari+Exposed), Tables.kt, tx helpers
    │   ├── auth/                 # PIN login/refresh/verify/switch, sesi, JWT, PinHasher
    │   ├── device/               # tablet (X-Device-Id): pencatatan otomatis, daftar, blokir
    │   ├── branch/               # cabang + overview owner
    │   ├── staff/                # akun staff, reset PIN, aktif/nonaktif
    │   ├── catalog/              # services, price history, loyalty rate, rewards
    │   ├── customer/             # customer, points ledger, impor CSV
    │   ├── order/                # order, item, event, numbering, advance, sync offline
    │   ├── shift/                # shift, cash entries, rekap
    │   ├── wa/                   # outbox writer (M2); worker, Cloud API client,
    │   │                         #   webhook, failures (M5 — fase akhir)
    │   ├── report/               # dashboard, daily sales, audit query
    │   ├── sync/                 # bootstrap, changes, heartbeat perangkat
    │   └── tools/                # SeedPilot (CLI)
    ├── main/resources/
    │   ├── db/migration/         # Flyway: V1__init.sql, V2__…
    │   ├── application.conf
    │   └── logback.xml
    ├── test/                     # unit test aturan bisnis (tanpa I/O)
    ├── integrationTest/          # Testcontainers Postgres — constraint & konkurensi
    └── contractTest/             # respons diuji terhadap openapi.yaml
```

Setiap package fitur memakai empat berkas dengan peran tetap:

| Berkas | Isi | Tidak boleh |
|---|---|---|
| `XxxRoutes.kt` | Definisi route, parsing request, otorisasi role/cabang, mapping ke DTO respons | Aturan bisnis, SQL |
| `XxxService.kt` | Aturan bisnis, batas transaksi DB, penulisan audit & outbox | Tipe Ktor (`ApplicationCall`) |
| `XxxRepository.kt` | Query Exposed | Keputusan bisnis |
| `XxxDto.kt` | `@Serializable` request/response (camelCase) | Logika |

## Menjalankan lokal

Prasyarat: Docker dan `psql` (opsional). JDK terpasang boleh versi berapa pun — Gradle
mengunduh toolchain JDK 21 sendiri lewat foojay resolver pada build pertama.

```bash
cp .env.example .env          # isi secret (kredensial WA baru perlu di M5)
docker compose up -d db       # Postgres 16 di :5432
./gradlew flywayMigrate       # atau otomatis saat boot
./gradlew seedPilot           # master data pilot — dev/staging saja
./gradlew run                 # API di http://localhost:8080
./gradlew run -Pworker        # worker WA + scheduler — baru ada di M5
```

Cek cepat: `curl localhost:8080/health` → `{"status":"UP",…}` dan `curl localhost:8080/health/ready`
→ `{"status":"READY","database":"UP"}`.

Kalau mesin sudah menjalankan Postgres lain di 5432, jalankan `DB_PORT=55432 docker compose up -d db`
dan samakan `DATABASE_URL` di `.env` — port container bisa diatur lewat `DB_PORT`.

Seed data pilot (3 cabang, price list, reward, rate, staff) dijalankan lewat `./gradlew seedPilot`
— **hanya untuk `dev`/`staging`**. Di produksi master data dibuat lewat endpoint owner, dan PIN demo
wajib diganti sebelum go-live.

### Login di tablet

Tidak ada langkah aktivasi (keputusan owner — PRD §12.1 Draft 1.1, [`docs/prd-gaps-m1.md`](docs/prd-gaps-m1.md) bagian 0).
Panduan lengkap untuk aplikasi Android: [`docs/api-integration.md`](docs/api-integration.md).
Aplikasi membuat UUID instalasi saat pertama dibuka dan mengirimnya sebagai `X-Device-Id`; alurnya
`GET /api/v1/login-options` → pilih cabang → `POST /api/v1/auth/pin-login` → access token.

### Environment variables

| Variable | Contoh | Keterangan |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/pwl` | Koneksi Postgres |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `pwl` / `…` | Role aplikasi — **tanpa** hak `UPDATE`/`DELETE` di `audit_log` |
| `JWT_SECRET` | acak 256-bit | Penandatangan access token |
| `PIN_PEPPER` | acak 256-bit | Kunci HMAC untuk `pin_lookup`. **Tidak pernah** disimpan di database |
| `MIN_APP_VERSION` | `1.4.0` | Di bawah ini → `426 APP_UPDATE_REQUIRED` |
| `RUN_MIGRATIONS_ON_BOOT` | `false` | Default `true` di `dev`, `false` di staging/prod — di sana migrasi adalah job pre-deploy `bin/pwl-migrate` |

Semua secret dari secret manager di staging/prod. Jangan pernah masuk ke repo.

### Staging (DigitalOcean App Platform)

Konfigurasi staging tercatat di [`.do/app.yaml`](.do/app.yaml): region `sgp1`, service `api`
(Dockerfile, health check `/health/ready`) dan job `PRE_DEPLOY` `migrate` yang menjalankan
`./bin/pwl-migrate` sebagai `pwl_migrator` sebelum setiap rollout. Database adalah Managed
PostgreSQL 16 terpisah dengan dua role — lihat [`docs/ops/database-roles.md`](docs/ops/database-roles.md).
Secret disimpan sebagai env terenkripsi App Platform (`type: SECRET`), tidak pernah dalam teks biasa
di repo.

Push ke `main` otomatis men-deploy staging. Perubahan spec diterapkan dengan
`doctl apps update <app-id> --spec .do/app.yaml`.

| Hal | Nilai |
|---|---|
| URL | <https://pwl-cashier-staging-rvz75.ondigitalocean.app> |
| App | `pwl-cashier-staging` (`37909820-9c51-4831-a478-c1d235dccca9`), `sgp1` |
| Database | `pwl-staging-db` (PostgreSQL 16, `db-s-1vcpu-1gb`), database `pwl` |
| Akses database | Trusted source hanya app ini. Untuk `psql`/`seedPilot` dari laptop, tambahkan IP sementara lalu hapus lagi |

Tanpa IP laptop di trusted source, `seedPilot` dan `flywayMigrate` ke staging ditolak — itu disengaja:

```bash
doctl databases firewalls append <cluster-id> --rule ip_addr:$(curl -s https://api.ipify.org)
# … jalankan pekerjaan …
doctl databases firewalls list <cluster-id>            # ambil UUID rule IP tadi
doctl databases firewalls remove <cluster-id> --uuid <uuid>
```

Variabel `WA_*` (token Cloud API, `WA_PHONE_NUMBER_ID`, `WA_APP_SECRET`, `WA_VERIFY_TOKEN`) baru
dibutuhkan di M5 dan didaftarkan di [seksi terakhir](#integrasi-meta--whatsapp-fase-akhir). API dan
test M1–M4 harus tetap boot tanpa variabel itu — kalau ada kode yang menolak start karena `WA_*`
kosong, itu bug.

## Konvensi API (ringkas)

| Aturan | Nilai |
|---|---|
| Base URL | `https://<host>/api/v1` |
| Format | JSON UTF-8, field **camelCase** (kolom DB snake_case) |
| Waktu | ISO-8601 UTC dengan milidetik — `2026-08-29T04:24:00.000Z` |
| Tanggal bisnis | `YYYY-MM-DD` zona **Asia/Jakarta** — dipakai untuk omzet harian, nomor order, "hari ini" |
| Uang | Integer rupiah (`Long`): `45000`. Kuantitas `NUMERIC(7,1)`: `4.5` |
| ID | UUID; nomor order tampilan di field terpisah `number` (`TBT-0829-015`) |
| Paginasi | Cursor — `?limit=50&cursor=<opaque>` → `{ "items": [...], "nextCursor": … }` |
| Idempotensi | Header `Idempotency-Key` **wajib** di `POST /orders`, `/orders/sync`, `/shifts`, `/shifts/{id}/cash-entries`, `/shifts/{id}/close` |
| Konkurensi | Resource yang bisa diubah dua perangkat membawa `version` / `fromStatus` → `409` bila berubah |
| Autentikasi | Layar login publik (`login-options`, `pin-login`, `refresh`); sisanya `Authorization: Bearer <accessToken>` |
| Header | `X-Device-Id` (UUID instalasi) wajib di `pin-login`/`refresh`, harus cocok dengan sesi bila dikirim di endpoint lain; `X-App-Version` (→ `426` bila di bawah `MIN_APP_VERSION`) |

Semua error memakai amplop yang sama, dan **`message` adalah kalimat Bahasa Indonesia yang siap
ditampilkan ke kasir apa adanya**:

```json
{
  "error": {
    "code": "SHIFT_NOT_OPEN",
    "message": "Shift Cabang Tebet belum dibuka — buka shift dulu sebelum mencatat transaksi.",
    "details": { "branchId": "7f1c…" },
    "requestId": "req_01J9…"
  }
}
```

Daftar lengkap kode error ada di Lampiran B PRD dan wajib disalin persis — teksnya diambil dari use
case klien yang sudah berjalan, supaya UX kasir tidak berubah.

## Peta endpoint

| Domain | Endpoint |
|---|---|
| Perangkat & auth | `GET/DELETE /devices` · `GET /login-options` · `POST /auth/pin-login` · `/auth/refresh` · `/auth/verify-pin` · `/auth/switch-staff` · `/auth/switch-branch` · `/auth/logout` · `GET /me` |
| Cabang | `GET /branches` · `GET /branches/overview` · `PATCH /branches/{id}` |
| Staff | `GET /staff` · `POST /staff` · `POST /staff/{id}/reset-pin` · `PATCH /staff/{id}` |
| Katalog | `GET/POST /services` · `PATCH /services/prices` · `PATCH /services/{id}` · `GET/PUT /loyalty/rate` · `GET/POST /rewards` · `PATCH /rewards/{id}` |
| Customer | `GET/POST /customers` · `GET/PATCH /customers/{id}` · `GET /customers/{id}/points` · `GET /customer-imports/template` · `POST /customer-imports` · `POST /customer-imports/{id}/commit` · `GET /customer-imports/{id}/error-report` |
| Order | `POST /orders` · `POST /orders/sync` · `GET /orders` · `GET /orders/{id}` · `POST /orders/{id}/advance` · `GET /orders/next-number` |
| Shift & kas | `GET /shifts/current` · `GET /shifts/latest` · `POST /shifts` · `POST /shifts/{id}/cash-entries` · `PUT /shifts/{id}/counted-cash` · `POST /shifts/{id}/close` · `GET /shifts` |
| WhatsApp *(M5)* | `GET /wa/failures` · `POST /wa/failures/{id}/retry` · `POST /wa/failures/retry` · `POST /wa/failures/{id}/manual-follow-up` · `GET /wa/summary` · `GET /wa/templates` · `GET/POST /webhooks/whatsapp` |
| Laporan | `GET /reports/dashboard` · `GET /reports/daily-sales` · `GET /audit` |
| Sync klien | `GET /sync/bootstrap` · `GET /sync/changes` · `POST /devices/heartbeat` |

Spesifikasi lengkap (body, validasi berurutan, pesan error, efek atomik) ada di §8 PRD.

> **Sengaja tidak disediakan:** endpoint "apakah PIN ini milik akun aktif". Klien punya
> `matchesActivePin()` untuk auto-submit keypad; kalau diekspos sebagai API, itu jadi oracle untuk
> menebak PIN tanpa tercatat sebagai login gagal.

## Database

Migrasi Flyway di `src/main/resources/db/migration`, penamaan `V<n>__<deskripsi>.sql`. Sekali
di-merge, sebuah migrasi **tidak pernah diedit** — buat migrasi baru.

Tabel inti: `branches`, `staff`, `devices`, `sessions`, `customers`, `points_ledger`, `services`,
`service_price_history`, `loyalty_rates`, `rewards`, `orders`, `order_items`, `order_events`,
`order_number_counters`, `shifts`, `cash_entries`, `daily_sales`, `wa_messages`, `audit_log`,
`customer_imports`, `idempotency_keys`.

Constraint yang menegakkan aturan bisnis di level database — bukan hanya di kode:

| Constraint | Menjamin |
|---|---|
| `orders.client_tx_id` UNIQUE | Sync offline tidak pernah membuat order ganda |
| UNIQUE `(branch_id, business_date, seq)` | Nomor order tidak bentrok walau dua perangkat bersamaan |
| Partial unique `shifts(branch_id) WHERE closed_at IS NULL` | Maksimal satu shift terbuka per cabang |
| Partial unique `staff(pin_lookup) WHERE active` | PIN unik di antara staff aktif (login tanpa pilih nama) |
| `customers.phone_digits` UNIQUE | Satu nomor HP = satu customer, lintas cabang |
| CHECK `customers.points_balance >= 0` | Saldo poin tidak pernah negatif |
| Hak akses role aplikasi tanpa `UPDATE`/`DELETE` di `audit_log` | Audit trail append-only |

## Testing

```bash
./gradlew test              # unit test aturan bisnis
./gradlew integrationTest   # Testcontainers Postgres
./gradlew contractTest      # validasi terhadap openapi.yaml
./gradlew ktlintCheck detekt
```

Yang wajib punya test sebelum merge: setiap aturan bisnis di `Service`, setiap endpoint (contract
test), dan skenario konkurensi — dua perangkat membuka shift bersamaan, dua perangkat memajukan
status order yang sama, sync yang dikirim dua kali. 20 skenario penerimaan ada di §16 PRD dan
menjadi basis suite integration test.

## Integrasi Meta / WhatsApp (fase akhir)

Seluruh isi seksi ini dikerjakan di **M5**, setelah transaksi, laporan, dan shadow mode selesai.
Sengaja ditaruh paling akhir supaya dependensi eksternal (verifikasi bisnis Meta, approval template,
kuota, callback publik) tidak pernah memblokir pembangunan inti kasir.

### Yang sudah jalan sebelum M5

Yang ditunda adalah pengirimnya, bukan antreannya. Sejak M2 sudah harus ada:

- tabel `wa_messages` beserta migrasinya;
- penulisan baris `QUEUED` **di dalam transaksi bisnis** yang memicunya (order dibuat, status jadi
  `SIAP`, opt-in customer) — Invariant 3, tidak pernah ditunda dan tidak pernah dipindah ke luar
  transaksi;
- pengecekan opt-in customer **pada saat event terjadi**, bukan dari `waStatus` yang terstempel di
  order (bug T7);
- tidak ada satu pun pemanggilan Cloud API. Tanpa worker, baris tetap `QUEUED` — itu status yang
  jujur. **Jangan pernah** menandai `SENT`/`TERKIRIM` hanya karena alur kodenya "seharusnya"
  mengirim (bug T6).

Endpoint `/wa/*` dan `/webhooks/whatsapp` belum ada di `openapi.yaml` sampai M5; tambahkan di PR yang
sama saat dibangun.

### Jalur administrasi Meta — mulai M0, paralel, non-blocking

Berkas ini punya waktu tunggu di sisi Meta, jadi diurus lebih dulu meski kodenya belakangan:

1. WhatsApp Business Account + **verifikasi bisnis** Meta (butuh dokumen legal usaha).
2. Daftar & verifikasi nomor bisnis — satu nomor untuk semua cabang → `WA_PHONE_NUMBER_ID`.
3. Ajukan 4 template kategori **Utility**, bahasa `id`, tunggu approval:
   `opt_in_confirm`, `struk_digital`, `status_siap_diambil`, `reminder_3_hari`.
4. Buat *system user* + permanent access token, catat app secret & verify token → secret manager.
5. Siapkan callback URL webhook HTTPS publik + langganan field `messages`.

Status keempat template dicatat di `docs/` repo ini; template yang ditolak harus direvisi sebelum
M5 dimulai.

### Langkah kerja M5

| # | Pekerjaan | Catatan |
|---|---|---|
| 1 | `WaCloudApiClient` (Ktor Client) + pemetaan parameter template | Di-mock di semua test — **jangan pernah** memanggil Meta dari test |
| 2 | Worker sebagai proses terpisah (`./gradlew run -Pworker`) | Polling 2 dtk, `FOR UPDATE SKIP LOCKED LIMIT 20` |
| 3 | Kebijakan retry + klasifikasi error Meta | Lihat tabel di bawah |
| 4 | `GET/POST /webhooks/whatsapp` | Verifikasi `X-Hub-Signature-256` & verify token; status `delivered`/`read`/`failed`; balasan masuk `STOP`/`BERHENTI` |
| 5 | `GET /wa/failures`, retry manual, `manual-follow-up`, `GET /wa/summary`, `GET /wa/templates` | Pesan gagal harus bisa ditindaklanjuti kasir |
| 6 | Scheduler harian 09.00 WIB | `reminder_3_hari` + cleanup |
| 7 | Uji: parameter template salah, nomor tidak terdaftar, rate limit, worker mati di tengah kirim, dua worker paralel | Skenario konkurensi harus benar-benar jalan paralel |

### Worker

Proses terpisah dari API, mengambil pesan tiap 2 detik:

```sql
SELECT … FROM wa_messages
 WHERE status = 'QUEUED' AND next_attempt_at <= now()
 FOR UPDATE SKIP LOCKED LIMIT 20;
```

Retry hanya untuk error yang layak diulang (rate limit `130429`, 5xx, timeout) dengan backoff
15 dtk → 30 dtk → 60 dtk → 2 mnt → 5 mnt, maksimal 5 percobaan. Error non-retryable (nomor tidak
terdaftar di WhatsApp `131026`, diblokir, parameter template salah) langsung `FAILED` dengan
`error_reason` Bahasa Indonesia untuk ditampilkan ke kasir.

Template: `opt_in_confirm`, `struk_digital`, `status_siap_diambil`, `reminder_3_hari` — kategori
*Utility*, bahasa `id`. Walk-in dan customer yang belum opt-in **tidak pernah** dikirimi pesan.
Balasan `STOP`/`BERHENTI` mematikan opt-in dan membatalkan pesan yang masih antre.

Scheduler harian 09.00 WIB mengirim `reminder_3_hari` untuk order `SIAP` > 72 jam (maksimal 1× per
order) dan membersihkan `idempotency_keys` (7 hari) serta payload webhook (30 hari).

### Menyalakan worker pertama kali — jangan ledakkan antrean lama

Karena M2–M4 (termasuk shadow mode) sudah menumpuk baris `QUEUED` selama berhari-hari, menyalakan
worker begitu saja akan mengirim ribuan pesan basi ke customer sungguhan: struk transaksi minggu
lalu, "cucian siap diambil" untuk order yang sudah selesai. Urutannya:

1. Di staging, jalankan worker dengan nomor uji dan verifikasi seluruh alur end-to-end.
2. Di produksi, **sebelum** worker dinyalakan: batalkan antrean basi dalam satu transaksi —
   `wa_messages` yang `QUEUED` dan `created_at` lebih lama dari ambang (mis. 2 jam) → status
   `CANCELLED` dengan `error_reason` "Antrean sebelum WhatsApp aktif", disertai baris audit.
3. Baru nyalakan worker, pantau `GET /wa/summary` dan `/wa/failures` intensif di jam pertama.
4. Target SLA WhatsApp (≥ 95% *delivered* < 2 menit) mulai diukur setelah langkah ini.

Langkah 2 memakai migrasi/skrip operasional sekali pakai, bukan `UPDATE` manual di produksi.

## Dokumen terkait

| Dokumen | Lokasi |
|---|---|
| PRD Backend REST API v1 | [`docs/PRD-Backend-REST-API.md`](docs/PRD-Backend-REST-API.md) (salinan) · asli di repo Android `PrimaWash` → `docs/backend/` |
| PRD produk (Kasir, Loyalty & WhatsApp) | repo Android `PrimaWash` → `docs/` |
| Aplikasi klien Android | `~/AndroidStudioProjects/PrimaWash` |
| Desain v2 multi-cabang | `~/Website/prima-wash-laundry` |
| Hak akses database & operasional | [`docs/ops/database-roles.md`](docs/ops/database-roles.md) |
| **Panduan integrasi API untuk tim Android** | [`docs/api-integration.md`](docs/api-integration.md) |
| Riwayat keputusan celah PRD (sudah di PRD Draft 1.2) | [`docs/prd-gaps-m1.md`](docs/prd-gaps-m1.md), [`docs/prd-gaps-m2.md`](docs/prd-gaps-m2.md) |
| Panduan kerja untuk Claude | [`CLAUDE.md`](CLAUDE.md) |
