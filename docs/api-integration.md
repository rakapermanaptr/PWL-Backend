# Integrasi API — panduan untuk tim Android

Panduan memakai backend PWL Cashier dari aplikasi Android **Prima Wash**. Dokumen ini menjelaskan
*cara memakai* API: alur login, header, penyimpanan token, penanganan error, dan daftar endpoint yang
sudah tersedia.

| | |
|---|---|
| **Cakupan** | Milestone M1 — login & sesi, tablet, cabang, staff, price list, loyalty, reward, customer |
| **Kontrak lengkap** | `openapi.yaml` di root repo backend — skema setiap request/response; bisa di-import ke Postman |
| **Aturan bisnis** | `PRD-Backend-REST-API.md` (Draft 1.1), di folder yang sama dengan dokumen ini |
| **Keputusan di luar PRD** | `docs/prd-gaps-m1.md` di repo backend |
| **Sumber** | Repo backend `PWL-Backend` → `docs/api-integration.md`, disalin ke repo Android `docs/backend/`. Bila ragu, versi di repo backend yang berlaku |
| **Terakhir diperbarui** | 14 September 2026 |

---

## 1. Ringkasan cepat

- Semua endpoint bisnis ada di bawah `/api/v1`.
- Login **tanpa aktivasi perangkat**: layar login cukup *pilih cabang → ketik PIN*.
- Aplikasi membuat **UUID instalasi** sendiri saat pertama dibuka dan mengirimnya sebagai header
  `X-Device-Id`. Pengguna tidak pernah melihatnya.
- Setelah login, semua endpoint data memakai `Authorization: Bearer <accessToken>`.
- `error.message` dari server adalah kalimat Bahasa Indonesia yang **ditampilkan ke kasir apa adanya**.
- Server yang menghitung dan memutuskan. Klien boleh menghitung untuk tampilan, tapi yang disimpan
  adalah hasil server.

## 2. Environment

| Environment | Base URL | Catatan |
|---|---|---|
| Lokal | `http://localhost:8080` | Backend dijalankan developer (`./gradlew run`). Emulator Android: `http://10.0.2.2:8080` |
| Staging | `https://pwl-cashier-staging-rvz75.ondigitalocean.app` | Dipakai untuk uji & shadow mode. Endpoint M1 tersedia setelah `development` di-merge ke `main` |
| Produksi | `https://api.primawash.id` | Belum aktif |

Isi `BASE_URL` per build type (PRD §14 A14), misalnya `debug` → lokal/staging, `release` → produksi.

## 3. Konvensi

| Hal | Aturan | Contoh |
|---|---|---|
| Format | JSON UTF-8, `Content-Type: application/json` | |
| Nama field | camelCase, sama dengan model Kotlin | `dailyTarget`, `shortName` |
| Nilai kosong | Dikirim sebagai `null`, field tidak dihilangkan | `"lastBranchId": null` |
| ID | UUID string | `"2368f351-ca2d-445d-92e8-c64e4d0ca8c6"` |
| Waktu | ISO-8601 UTC dengan milidetik | `"2026-09-14T09:38:10.184Z"` |
| Tanggal bisnis | `YYYY-MM-DD`, zona Asia/Jakarta | `"2026-09-15"` |
| Uang | Integer rupiah (`Long`), tanpa desimal | `45000` |
| Kuantitas / step | Angka desimal, maksimal 1 angka di belakang koma | `0.5`, `1.0` |
| Enum | Huruf besar | `KASIR`, `OWNER`, `KILOAN_REGULER` |
| Daftar berhalaman | `?limit=50&cursor=<opaque>` → `{ "items": [...], "nextCursor": "…" \| null }` | Lanjutkan dengan `nextCursor` sampai `null` |

Klien sebaiknya memakai `ignoreUnknownKeys = true` supaya field baru dari server tidak merusak parsing.

## 4. Header

| Header | Kapan | Nilai |
|---|---|---|
| `X-Device-Id` | **Setiap request.** Wajib di `pin-login` dan `refresh` | UUID instalasi (bagian 5) |
| `Authorization` | Semua endpoint selain layar login | `Bearer <accessToken>` |
| `X-App-Version` | Setiap request | `versionName` aplikasi, mis. `1.4.0`. Di bawah versi minimum server → `426` |
| `Content-Type` | Request dengan body | `application/json` |

Respons setiap request membawa header `X-Request-Id` (juga ada di `error.requestId`). Sertakan nilai ini
saat melaporkan bug ke tim backend.

## 5. Data yang disimpan di tablet

| Kunci | Isi | Dibuat / diganti | Dihapus |
|---|---|---|---|
| `deviceId` | UUID instalasi | Sekali, saat aplikasi pertama dibuka: `UUID.randomUUID()` | Tidak pernah (hilang saat uninstall) |
| `accessToken` | JWT sesi staff, berlaku 60 menit | `pin-login`, `refresh`, `switch-staff`, `switch-branch` | Logout, sesi berakhir |
| `refreshToken` | `rt_…`, sesi maksimal 18 jam sejak login PIN | Sama dengan `accessToken`. **Berganti setiap refresh** | Logout, sesi berakhir |
| `sessionContext` | `staff` dan `branch` dari respons login | Setiap token baru | Logout |
| `lastBranchId` | Cabang terakhir, untuk preselect | Dari `login-options` / `logout` | — |

- Simpan token di penyimpanan terenkripsi. Library baru (mis. untuk enkripsi) mengikuti aturan persetujuan
  di `CLAUDE.md` repo Android.
- `deviceId` jangan dibuat ulang saat logout atau ganti staff. ID baru dianggap server sebagai tablet baru:
  kunci PIN dan riwayat tablet lama tidak berlaku.
- PIN **tidak pernah** disimpan di tablet. Hashing PIN lokal dan `matchesActivePin()` dihapus (PRD §14 A5).

## 6. Alur login

Halaman visual alur ini lengkap dengan contoh JSON:
<https://claude.ai/code/artifact/b2edcc00-a56b-4ca9-b08e-8e4653be5475>

### 6.1 Saat aplikasi dibuka

```
Buka aplikasi
  ├─ belum ada deviceId → buat UUID, simpan
  ├─ ada refreshToken  → POST /auth/refresh
  │     ├─ 200                          → simpan token baru → Beranda
  │     ├─ 401 UNAUTHENTICATED          → hapus sesi → Layar pilih cabang
  │     └─ 401 DEVICE_UNAUTHORIZED      → hapus sesi → Layar "tablet diblokir"
  └─ tidak ada refreshToken → Layar pilih cabang
```

Kalau tidak ada koneksi saat aplikasi dibuka, sesi yang tersimpan tetap bisa dipakai untuk tampilan
dari cache. Login PIN pertama di awal hari wajib online (PRD Q2).

### 6.2 Layar pilih cabang — `GET /api/v1/login-options`

Tanpa token. Kirim `X-Device-Id` agar cabang terakhir terpilih otomatis.

```http
GET /api/v1/login-options
X-Device-Id: 5b1d6a3e-2f4c-4c1a-9e7b-2a4f8c3d1e90
X-App-Version: 1.4.0
```

```json
{
  "lastBranchId": "2368f351-ca2d-445d-92e8-c64e4d0ca8c6",
  "branches": [
    {
      "id": "2368f351-ca2d-445d-92e8-c64e4d0ca8c6",
      "code": "TBT",
      "name": "Tebet",
      "address": "Jl. Tebet Raya No. 42, Jakarta Selatan",
      "hours": "07.00 – 21.00",
      "active": true,
      "cashierNames": ["Siti N.", "Bagas A."],
      "shift": { "open": false, "openedByName": null }
    }
  ]
}
```

- `lastBranchId` bernilai `null` di fresh install atau bila tablet belum pernah login.
- Cabang `active: false` ditampilkan tetapi tidak bisa dipilih (login ke cabang itu ditolak
  `BRANCH_INACTIVE`).

### 6.3 Layar PIN — `POST /api/v1/auth/pin-login`

Tanpa token. `X-Device-Id` **wajib**.

```http
POST /api/v1/auth/pin-login
X-Device-Id: 5b1d6a3e-2f4c-4c1a-9e7b-2a4f8c3d1e90
X-App-Version: 1.4.0
Content-Type: application/json

{ "branchId": "2368f351-ca2d-445d-92e8-c64e4d0ca8c6", "pin": "1234" }
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9…",
  "accessTokenExpiresIn": 3600,
  "refreshToken": "rt_9QdL2mZ…",
  "refreshTokenExpiresIn": 64800,
  "context": {
    "staff": {
      "id": "644d7631-b1a1-47ab-9577-69bb1ef978ae",
      "name": "Siti Nurhaliza",
      "shortName": "Siti N.",
      "role": "KASIR",
      "branchId": "2368f351-ca2d-445d-92e8-c64e4d0ca8c6",
      "active": true,
      "lastLoginAt": "2026-09-14T09:38:10.184Z"
    },
    "branch": {
      "id": "2368f351-ca2d-445d-92e8-c64e4d0ca8c6",
      "code": "TBT",
      "name": "Tebet",
      "address": "Jl. Tebet Raya No. 42, Jakarta Selatan",
      "phone": "021-8290-1147",
      "hours": "07.00 – 21.00",
      "dailyTarget": 5200000,
      "active": true
    }
  }
}
```

- Server mengenali staff dari PIN-nya, jadi tidak ada pilih nama. Kasir hanya bisa login di cabangnya
  sendiri; owner di cabang mana pun.
- `details.clearPin: true` pada error → kosongkan keypad. `false` → biarkan PIN yang sudah diketik.
- Kirim PIN saat tombol ditekan atau saat 6 digit terisi, bukan otomatis setiap digit (PRD §14 A5).
  Setiap percobaan salah dihitung ke kunci tablet.
- Login di tablet yang sedang dipakai staff lain mengakhiri sesi staff sebelumnya.

| Error | Tampilkan | `clearPin` |
|---|---|---|
| `400 VALIDATION_ERROR` | Bug klien: `X-Device-Id` tidak dikirim / bukan UUID | — |
| `422 BRANCH_REQUIRED` | "Pilih cabang perangkat dulu." | false |
| `422 BRANCH_INACTIVE` | "Cabang {nama} sedang nonaktif — pilih cabang lain atau hubungi owner." | false |
| `422 PIN_FORMAT` | "PIN minimal 4 digit." | false |
| `401 DEVICE_UNAUTHORIZED` | Layar "tablet diblokir" dengan `error.message` | — |
| `422 PIN_UNKNOWN` | "PIN tidak dikenali. Coba lagi atau minta owner reset PIN." | true |
| `422 STAFF_INACTIVE` | "Akun {nama} nonaktif — PIN lama sudah diblokir." | true |
| `422 STAFF_WRONG_BRANCH` | "{nama} terdaftar di Cabang {asal} — tidak bisa login di perangkat Cabang {cabang}." | true |
| `423 PIN_LOCKED` | "Terlalu banyak PIN salah. Coba lagi dalam {n} menit." — nonaktifkan keypad selama `Retry-After` detik | true |
| `429 RATE_LIMITED` | `error.message`; coba lagi setelah `Retry-After` detik | — |

### 6.4 Memakai sesi

Semua endpoint selain `login-options`, `pin-login`, dan `refresh` wajib membawa access token:

```http
GET /api/v1/me
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9…
X-Device-Id: 5b1d6a3e-2f4c-4c1a-9e7b-2a4f8c3d1e90
```

- Role dan cabang dibaca server dari sesi. **Jangan** mengirim `role` atau `branchId` untuk menentukan
  hak akses. Kasir yang meminta data cabang lain mendapat `403 BRANCH_SCOPE`.
- Sesi dicek ulang di setiap request. Reset PIN oleh owner, akun dinonaktifkan, atau tablet diblokir
  langsung membuat request berikutnya `401`.

### 6.5 Perpanjang sesi — `POST /api/v1/auth/refresh`

Tanpa access token. `X-Device-Id` **wajib** dan harus tablet yang sama dengan saat login.

```http
POST /api/v1/auth/refresh
X-Device-Id: 5b1d6a3e-2f4c-4c1a-9e7b-2a4f8c3d1e90
Content-Type: application/json

{ "refreshToken": "rt_9QdL2mZ…" }
```

Respons berbentuk sama dengan `pin-login`. `refreshTokenExpiresIn` adalah sisa waktu sampai 18 jam
sejak login PIN.

> **Penting — refresh token dirotasi.** Setiap refresh berhasil, refresh token lama langsung mati.
> Kalau dua request memanggil refresh bersamaan dengan token yang sama, yang kedua gagal `401` dan
> pengguna terlempar ke layar PIN. Pastikan **hanya satu refresh berjalan pada satu waktu** (bagian 7),
> dan simpan kedua token baru sebelum mengulang request.

### 6.6 Logout — `POST /api/v1/auth/logout`

```http
POST /api/v1/auth/logout
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9…
```

```json
{ "lastBranchId": "2368f351-ca2d-445d-92e8-c64e4d0ca8c6" }
```

Hapus `accessToken`, `refreshToken`, dan `sessionContext`. **Simpan** `deviceId`. Kembali ke layar pilih
cabang dengan `lastBranchId` terpilih.

### 6.7 Aksi lain selama shift

| Aksi | Endpoint | Body | Respons |
|---|---|---|---|
| Kasir lain mengambil alih tablet | `POST /api/v1/auth/switch-staff` | `{ "staffId", "pin" }` | Sama dengan `pin-login`. Sesi lama berakhir — ganti semua token. Jika staff baru kasir, keluar dari layar owner (`ShellEffect.CashierTookOver`) |
| Konfirmasi PIN sebelum buka shift | `POST /api/v1/auth/verify-pin` | `{ "staffId", "pin" }` | `{ "staff", "staffProof": "sp_…", "expiresIn": 300 }` — `staffProof` sekali pakai, dikirim ke `POST /shifts` (M2) |
| Owner memindah tablet ke cabang lain | `POST /api/v1/auth/switch-branch` | `{ "branchId" }` | `{ "changed", "accessToken", "accessTokenExpiresIn", "refreshToken", "refreshTokenExpiresIn", "context" }`. `changed: false` → token `null`, tetap pakai token lama |
| Konteks sesi terbaru | `GET /api/v1/me` | — | `{ "staff", "branch" }` |

Error `verify-pin` / `switch-staff`, berurutan: `STAFF_REQUIRED` "Pilih staff dulu." → `STAFF_INACTIVE`
"Akun {nama} nonaktif — PIN lama tidak bisa dipakai." → `STAFF_WRONG_BRANCH` → `PIN_FORMAT` →
`423 PIN_LOCKED` → `PIN_WRONG` "PIN salah untuk {nama}. Coba lagi.". Lima PIN salah berturut-turut untuk
akun yang sama mengunci akun itu 15 menit, termasuk untuk login PIN biasa. Kandidat staff untuk dialog
PIN diambil dari `GET /api/v1/staff` (bagian 8.3).

`switch-branch` oleh kasir → `403 OWNER_ONLY` "Perangkat kasir terikat ke Cabang {nama}. Hanya
owner/admin yang bisa memindahkan perangkat ke cabang lain."

## 7. Penanganan error di klien

Semua error memakai amplop yang sama:

```json
{
  "error": {
    "code": "PIN_UNKNOWN",
    "message": "PIN tidak dikenali. Coba lagi atau minta owner reset PIN.",
    "details": { "clearPin": true },
    "requestId": "req_01M2FMEJWWRS4K44"
  }
}
```

| HTTP | Kode | Tindakan klien |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bug klien (field hilang/format salah). `details.fields` berisi field yang salah. Log dan tampilkan pesan umum |
| 401 | `TOKEN_EXPIRED` | Refresh sekali (single-flight), lalu ulangi request |
| 401 | `UNAUTHENTICATED` | Hapus sesi → layar pilih cabang |
| 401 | `DEVICE_UNAUTHORIZED` | Hapus sesi → layar "tablet diblokir", tampilkan `message` |
| 403 | `FORBIDDEN`, `BRANCH_SCOPE`, `OWNER_ONLY` | Tampilkan `message`. Biasanya tanda UI menampilkan fitur yang bukan untuk role ini |
| 404 | `NOT_FOUND` | Tampilkan `message` |
| 409 | `PIN_CONFLICT`, `PHONE_ALREADY_REGISTERED`, … | Tampilkan `message`; beberapa membawa `details` (mis. `details.customer`) |
| 422 | kode bisnis (Lampiran B) | Tampilkan `message` apa adanya |
| 423 | `PIN_LOCKED` | Tampilkan `message`, kunci keypad selama `Retry-After` detik |
| 426 | `APP_UPDATE_REQUIRED` | Layar "perbarui aplikasi"; `details.minAppVersion` |
| 429 | `RATE_LIMITED` | Tunggu `Retry-After` detik |
| 5xx | `INTERNAL_ERROR` | Retryable (`AppError.isRetryable`); tampilkan `message` bila perlu |

Sekarang 422 dipetakan ke "The submitted data is invalid" di `core/network/AppErrorExt.kt`. Untuk
409/422/423 **tampilkan `error.message`** (PRD §14 A7).

Contoh pola untuk Ktor Client — sesuaikan dengan struktur `:data/remote` yang ada:

```kotlin
// Satu refresh pada satu waktu: refresh token dirotasi, refresh paralel saling membatalkan.
private val refreshMutex = Mutex()

suspend fun <T> authorized(call: suspend (accessToken: String) -> HttpResponse, parse: suspend (HttpResponse) -> T): T {
    val token = session.accessToken ?: throw SessionEndedException()
    val first = call(token)
    if (first.status != HttpStatusCode.Unauthorized || first.errorCode() != "TOKEN_EXPIRED") return parse(first)

    val renewed = refreshMutex.withLock {
        // Request lain mungkin sudah me-refresh selagi kita menunggu.
        session.accessToken.takeIf { it != token } ?: refresh()
    }
    return parse(call(renewed))
}
```

## 8. Endpoint M1

Akses: **Pub** = tanpa token · **K** = kasir · **O** = owner. Semua request K/O membawa
`Authorization: Bearer <accessToken>`. Detail skema ada di `openapi.yaml`.

### 8.1 Layar login & sesi

| Method & path | Akses | Body | Respons |
|---|---|---|---|
| `GET /api/v1/login-options` | Pub | — | `{ lastBranchId, branches[] }` |
| `POST /api/v1/auth/pin-login` | Pub + `X-Device-Id` | `{ branchId, pin }` | Token + `context` |
| `POST /api/v1/auth/refresh` | Pub + `X-Device-Id` | `{ refreshToken }` | Token + `context` |
| `POST /api/v1/auth/verify-pin` | K/O | `{ staffId, pin }` | `{ staff, staffProof, expiresIn }` |
| `POST /api/v1/auth/switch-staff` | K/O | `{ staffId, pin }` | Token + `context` |
| `POST /api/v1/auth/switch-branch` | O | `{ branchId }` | `{ changed, …token, context }` |
| `POST /api/v1/auth/logout` | K/O | — | `{ lastBranchId }` |
| `GET /api/v1/me` | K/O | — | `{ staff, branch }` |

### 8.2 Tablet

| Method & path | Akses | Body | Respons |
|---|---|---|---|
| `GET /api/v1/devices` | O | — | `{ items: [{ id, name, platform, appVersion, lastBranchId, firstSeenAt, lastSeenAt, pendingCount, revokedAt }] }` — hanya tablet yang pernah login |
| `DELETE /api/v1/devices/{id}` | O | — | `{ device, changed }` — blokir tablet hilang; `id` = `deviceId` tablet itu |

Tablet dinamai otomatis "Tablet Cabang {nama}" pada login pertamanya.

### 8.3 Cabang & staff

| Method & path | Akses | Body / query | Respons |
|---|---|---|---|
| `GET /api/v1/branches` | K/O | — | `{ items: [Branch] }` |
| `GET /api/v1/branches/overview` | O | `?date=YYYY-MM-DD` (default hari ini WIB) | `{ date, items: [{ branch, revenue, txCount, targetRatio, latestShift, activeCashiers }] }` |
| `PATCH /api/v1/branches/{id}` | O | `{ dailyTarget?, active? }` | `{ branch, changed }` |
| `GET /api/v1/staff` | K/O | O: `?branchId=&active=` | O: `{ items: [Staff] }` · K: `{ items: [{ id, name, shortName, role }] }` (staff aktif yang boleh bekerja di cabangnya — kandidat dialog PIN) |
| `POST /api/v1/staff` | O | `{ name, pin, role, branchId }` | `201 { staff }` |
| `POST /api/v1/staff/{id}/reset-pin` | O | — | `{ staff, pin }` — PIN baru hanya ditampilkan sekali, jangan disimpan |
| `PATCH /api/v1/staff/{id}` | O | `{ active }` | `{ staff, changed }` |

`Branch` = `{ id, code, name, address, phone, hours, dailyTarget, active }`.
`Staff` = `{ id, name, shortName, role, branchId, active, lastLoginAt }` — tanpa `pinHash`; model `Staff`
di `:domain` perlu menghapus field itu.

Error penting: `TARGET_REQUIRED` (target ≤ 0), `BRANCH_IN_USE` (menonaktifkan cabang tablet sendiri),
`NAME_REQUIRED`, `PIN_FORMAT` "PIN harus 4–6 digit.", `PIN_TAKEN`, `CASHIER_NEEDS_BRANCH`,
`STAFF_SELF_DEACTIVATE`, `LAST_OWNER`, `409 PIN_CONFLICT`.

### 8.4 Price list, loyalty, reward

| Method & path | Akses | Body / query | Respons |
|---|---|---|---|
| `GET /api/v1/services` | K/O | O: `?includeInactive=true` (kasir selalu hanya aktif) | `{ items: [Service] }` |
| `POST /api/v1/services` | O | `{ category, name, price, unit }` | `201 { service }` |
| `PATCH /api/v1/services/prices` | O | `{ prices: { "<serviceId>": 11000 } }` | `{ items: [Service], changedCount }` |
| `PATCH /api/v1/services/{id}` | O | `{ active }` | `{ service, changed }` |
| `GET /api/v1/loyalty/rate` | K/O | — | `{ id, rupiahPerStep, pointsPerStep, effectiveFrom }` |
| `PUT /api/v1/loyalty/rate` | O | `{ rupiahPerStep, pointsPerStep }` | `{ rate, changed }` |
| `GET /api/v1/rewards` | K/O | O: `?includeInactive=true` | `{ items: [Reward] }` |
| `POST /api/v1/rewards` | O | `{ name, cost, value, minSubtotal? }` | `201 { reward }` |
| `PATCH /api/v1/rewards/{id}` | O | `{ active }` | `{ reward, changed }` |

`Service` = `{ id, category, name, price, unit, step, active }` — `step` dihitung server (0.5 untuk `kg`,
1.0 lainnya); `unit` ∈ `kg`, `pcs`, `pasang`, `m²`.
`Reward` = `{ id, name, cost, value, note, minSubtotal, usedCount, active }` — `minSubtotal` baru (T9),
`null` = tanpa minimum.

Error penting: `NAME_REQUIRED`, `PRICE_REQUIRED`, `SERVICE_NAME_TAKEN`, `RATE_INVALID`, `REWARD_INVALID`.
Kategori/unit di luar daftar → `400`.

### 8.5 Customer

| Method & path | Akses | Body / query | Respons |
|---|---|---|---|
| `GET /api/v1/customers` | K/O | `?q=&limit=&cursor=` | `{ items: [Customer], nextCursor }` — tanpa `q`: kunjungan terbanyak; dengan `q`: nama atau digit no HP |
| `POST /api/v1/customers` | K/O | `{ id?, name, phone, optIn }` | `201 { customer }` |
| `GET /api/v1/customers/{id}` | K/O | — | `{ customer }` |
| `PATCH /api/v1/customers/{id}` | K/O | `{ name?, optIn? }` | `{ customer, changed }` |
| `GET /api/v1/customers/{id}/points` | K/O | `?limit=&cursor=` | `{ items: [{ id, type, delta, balanceAfter, orderId, staffId, createdAt }], nextCursor }` |

`Customer` = `{ id, name, phone, points, optIn, visits, homeBranchId }`. `phone` dikembalikan dalam format
tampilan `0812-3390-4471`; klien boleh mengirim `+62 812…`, `0812 3390 4471`, dll.

- `id` opsional: kirim UUID buatan klien untuk customer yang didaftarkan offline, supaya identitasnya sama
  setelah sync (M2).
- `409 PHONE_ALREADY_REGISTERED` membawa `details.customer` — langsung pilih customer itu di POS.
- Opt-out (`optIn: false`) membatalkan pesan WhatsApp customer tsb yang masih antre.

## 9. Batas & kunci

| Batas | Nilai | Respons |
|---|---|---|
| PIN salah per tablet | 5 dalam 5 menit → kunci 5 menit | `423 PIN_LOCKED` + `Retry-After` |
| PIN salah per akun (`verify-pin`, `switch-staff`) | 5 berturut-turut → kunci 15 menit | `423 PIN_LOCKED` |
| Percobaan login PIN per jaringan | 20 per 5 menit | `429 RATE_LIMITED` + `Retry-After` |
| Request umum per tablet | 120 per menit | `429 RATE_LIMITED` |
| Access token | 60 menit | `401 TOKEN_EXPIRED` |
| Sesi (refresh token) | 18 jam sejak login PIN | `401 UNAUTHENTICATED` |
| `staffProof` | 5 menit, sekali pakai | — |

Tablet di satu toko biasanya berbagi satu jaringan, jadi hindari percobaan login otomatis berulang
(mis. retry otomatis saat error) supaya kuota 20 percobaan per 5 menit tidak habis.

## 10. Uji coba

**Lokal**
```bash
# di repo backend
docker compose up -d db
./gradlew seedPilot
./gradlew run            # http://localhost:8080
```

**Akun demo (seed pilot — hanya dev/staging)**

| Staff | Role | Cabang | PIN |
|---|---|---|---|
| Raka Prasetyo | OWNER | semua | 9090 |
| Siti Nurhaliza | KASIR | Tebet (TBT) | 1234 |
| Bagas Ardhana | KASIR | Tebet (TBT) | 5678 |
| Nia Ramadhani | KASIR | Bintaro (BTR) | 2468 |
| Fajar Nugroho | KASIR | Bintaro (BTR) | 1357 |
| Wulan Sari | KASIR | Cipete (CPT) | 3690 |
| Yuni Astari | KASIR (nonaktif) | Cipete (CPT) | 4321 |

PIN harus unik di antara staff aktif karena server mengenali staff dari PIN-nya.

**Postman**: import `openapi.yaml`. Untuk `pin-login` dan `refresh` tambahkan header `X-Device-Id`
(UUID apa pun, dipakai konsisten). Simpan `accessToken` ke variabel collection lalu pakai
`Bearer {{accessToken}}` untuk endpoint lain.

**curl**
```bash
curl -X POST http://localhost:8080/api/v1/auth/pin-login \
  -H 'Content-Type: application/json' \
  -H 'X-Device-Id: 5b1d6a3e-2f4c-4c1a-9e7b-2a4f8c3d1e90' \
  -d '{"branchId":"<id cabang dari login-options>","pin":"1234"}'
```

## 11. Pemetaan use case klien → endpoint (M1)

| Use case / komponen klien | Endpoint | Catatan |
|---|---|---|
| `LoginViewModel` (daftar cabang) | `GET /login-options` | Tanpa token |
| `LoginWithPinUseCase` | `POST /auth/pin-login` | Hapus hashing PIN lokal & `matchesActivePin()` |
| `VerifyStaffPinUseCase` | `POST /auth/verify-pin` | |
| `SwitchActiveStaffUseCase` | `POST /auth/switch-staff` | Ganti semua token |
| `SwitchBranchUseCase` | `POST /auth/switch-branch` | Owner saja |
| `LogoutUseCase` | `POST /auth/logout` | Simpan `deviceId` |
| `ObserveSessionContextUseCase` | `GET /me` + sesi tersimpan | |
| `SessionRepository` | penyimpanan token + `POST /auth/refresh` | Persisten, refresh otomatis (A4) |
| `UpdateBranchTargetUseCase` / `ToggleBranchActiveUseCase` | `PATCH /branches/{id}` | |
| `AddStaffUseCase` / `ResetStaffPinUseCase` / `ToggleStaffActiveUseCase` | `POST /staff` / `POST /staff/{id}/reset-pin` / `PATCH /staff/{id}` | |
| `AddServiceUseCase` / `SavePriceListUseCase` / `ToggleServiceUseCase` | `POST /services` / `PATCH /services/prices` / `PATCH /services/{id}` | |
| `SaveLoyaltyRateUseCase` | `PUT /loyalty/rate` | |
| `AddRewardUseCase` / `ToggleRewardUseCase` | `POST /rewards` / `PATCH /rewards/{id}` | Toggle mengirim nilai `active` tujuan, bukan membalik |
| `RegisterCustomerUseCase` | `POST /customers` | |
| `PosState.customerMatches` | `GET /customers?q=` | |

Semua endpoint toggle (`PATCH …/{id}` dengan `{ "active": … }`) menerima **nilai tujuan**. Mengirim nilai
yang sama dengan saat ini aman: respons `changed: false`, tanpa audit.

## 12. Belum tersedia

| Milestone | Endpoint |
|---|---|
| M2 Transaksi | `/orders`, `/orders/sync`, `/orders/{id}/advance`, `/orders/next-number`, `/shifts…`, `/sync/bootstrap`, `/sync/changes`, `/devices/heartbeat` |
| M3 Laporan & impor | `/reports/dashboard`, `/reports/daily-sales`, `/audit`, `/customer-imports…` |
| M5 WhatsApp | `/wa/failures…`, `/wa/summary`, `/wa/templates` |

Endpoint yang belum ada membalas `404 NOT_FOUND`.

## 13. Riwayat perubahan

| Tanggal | Perubahan |
|---|---|
| 14 September 2026 | Versi pertama untuk M1. Login tanpa aktivasi perangkat (`X-Device-Id` menggantikan token perangkat). |
