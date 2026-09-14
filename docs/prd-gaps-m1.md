# Celah PRD yang diisi di M1

Catatan untuk PRD Backend (sumber asli di repo Android `PrimaWash` → `docs/backend/`) dan untuk tim
Android. Semua yang ada di sini **sudah diimplementasikan** di M1 karena endpoint-nya butuh jawaban,
tetapi belum tertulis di PRD. Setelah disetujui, salin ke PRD (Lampiran B/C dan §8.1) lalu salin ulang
PRD ke repo ini.

Aturan yang dipakai saat mengisi: bila use case klien sudah punya teksnya, teks itu yang dipakai apa
adanya; hanya yang benar-benar belum ada yang didrafkan baru.

## 0. Keputusan owner: login tanpa aktivasi perangkat (14 September 2026)

**Sudah diterapkan ke PRD Draft 1.1** (§6.1, §7.2 `devices`, §8.1, §12.1, §12.2, §14 A3, Lampiran B
`DEVICE_UNAUTHORIZED`). Bagian lain dokumen ini masih menunggu disalin ke PRD. Pengguna tablet adalah owner lanjut usia dan kasir yang tidak boleh
direpotkan langkah teknis, jadi alurnya cukup **pilih cabang → ketik PIN → masuk**. Tidak ada kode
aktivasi dan tidak ada token perangkat.

| Sebelumnya (PRD §12.1) | Sekarang |
|---|---|
| Owner membuat kode aktivasi, tablet menukarnya dengan `deviceToken` | Dihapus: `POST /devices/activate`, `POST /devices/activation-codes`, tabel `device_activation_codes` (V4) |
| `GET /login-options`, `POST /auth/pin-login`, `POST /auth/refresh` butuh `Authorization: Bearer <deviceToken>` | Publik. `pin-login` dan `refresh` wajib header `X-Device-Id` |
| Perangkat terdaftar oleh owner | Aplikasi membuat UUID instalasi sendiri saat pertama dibuka (tidak terlihat pengguna); server mencatat tablet pada percobaan login pertama dan mengaudit `DEVICE_ACTIVATED` saat login pertamanya berhasil |

Yang **tetap** berlaku lewat `X-Device-Id`: batas 5 PIN salah per tablet per 5 menit, satu sesi per
tablet, refresh token hanya dari tablet yang sama, dan owner bisa **memblokir tablet hilang**
(`DELETE /devices/{id}`) — tablet itu tidak bisa login maupun refresh lagi.

**Risiko yang diterima owner.** Tanpa pengikatan perangkat, login PIN bisa dicoba dari mana saja di
internet, dan ID instalasi bisa dikarang ulang untuk menghindari kunci per tablet. PIN 4 digit hanya
punya 10.000 kombinasi. Pengaman tambahan yang dipasang: **maksimal 20 percobaan login PIN per alamat
jaringan per 5 menit** (`429 RATE_LIMITED`; semua tablet satu toko biasanya berbagi satu alamat, jadi
batasnya dibuat longgar untuk pemakaian normal). Penyerang yang berganti-ganti alamat tetap bisa
menebak. Saran untuk mengurangi risiko tanpa menambah langkah bagi kasir:

1. **PIN owner 6 digit** (sudah didukung, 4–6 digit) — ruang tebakan untuk akun paling berkuasa naik
   100×.
2. Pantau audit `LOGIN_FAILED` / `PIN_LOCKED` di laporan owner (M3), dan pertimbangkan alert bila
   lonjakan.
3. Bila suatu saat terjadi insiden, aktivasi perangkat bisa dikembalikan sebagai langkah sekali pasang
   oleh owner, tanpa mengubah alur harian kasir.

## 1. Kode error baru (usulan tambahan Lampiran B)

| Code | HTTP | Pesan | Kapan |
|---|---|---|---|
| `DEVICE_UNAUTHORIZED` | 401 | Tablet ini sudah diblokir owner — hubungi owner untuk memakai tablet lain. | `pin-login`, `refresh`, atau request ber-sesi dari tablet yang diblokir owner. Beda dengan `UNAUTHENTICATED` (kembali ke layar PIN). |
| `STAFF_REQUIRED` | 422 | Pilih staff dulu. | `verify-pin` / `switch-staff` tanpa `staffId` atau dengan id yang tidak dikenal. Teks dari `VerifyStaffPinUseCase`; di klien ini `BusinessRuleException` tanpa kode. |

## 2. Pesan untuk kode yang sudah ada tetapi belum punya teks

| Code | HTTP | Pesan | Sumber |
|---|---|---|---|
| `UNAUTHENTICATED`, `TOKEN_EXPIRED` | 401 | Sesi berakhir — silakan login ulang dengan PIN. | `ObserveSessionContextUseCase.requireCurrent` |
| `FORBIDDEN` | 403 | Fitur ini hanya untuk owner/admin. | baru |
| `BRANCH_SCOPE` | 403 | Kasir hanya bisa mengakses data Cabang {nama}. | baru |
| `APP_UPDATE_REQUIRED` | 426 | Versi aplikasi Prima Wash sudah terlalu lama — perbarui aplikasi dulu sebelum melanjutkan. (`details.minAppVersion`) | baru |
| `RATE_LIMITED` | 429 | Terlalu banyak permintaan dari perangkat ini — tunggu sebentar lalu coba lagi. | baru |
| `STAFF_INACTIVE` (verify-pin) | 422 | Akun {nama} nonaktif — PIN lama tidak bisa dipakai. | `VerifyStaffPinUseCase` — berbeda satu kata dari versi login ("sudah diblokir"), dipertahankan seperti klien |
| `NAME_REQUIRED` (reward) | 422 | Nama reward wajib diisi. | `AddRewardUseCase` |
| `PRICE_REQUIRED` (tambah layanan) | 422 | Harga per unit belum diisi. | `AddServiceUseCase` (PRD §8.4 menyebutnya, Lampiran B hanya memuat versi simpan harga) |
| `NOT_FOUND` | 404 | Cabang tidak ditemukan. / Akun tidak ditemukan. / Layanan tidak ditemukan. / Reward tidak ditemukan. | use case admin klien |
| `NOT_FOUND` | 404 | Perangkat tidak ditemukan. / Customer tidak ditemukan. / Rate poin belum diatur. | baru |
| `VALIDATION_ERROR` | 400 | Parameter limit harus angka 1–100. / Cursor tidak valid — muat ulang daftar dari awal. / Format tanggal harus YYYY-MM-DD. / ID customer dari perangkat sudah dipakai customer lain. | baru |

## 3. `action_type` audit baru (usulan tambahan Lampiran C)

| action_type | Kalimat `action` | Kapan |
|---|---|---|
| `CUSTOMER_UPDATED` | Ubah nama customer {lama} → {baru} / Ubah opt-in WA customer {nama}: ya | `PATCH /customers/{id}` selain opt-out. Opt-out memakai `CUSTOMER_OPTED_OUT` yang sudah ada. |

Kalimat audit lain yang tidak tertulis di PRD tetapi disalin dari klien: `SERVICE_TOGGLED` "Nonaktifkan
layanan {nama} ({kategori}) — berlaku semua cabang", `LOYALTY_RATE_CHANGED` "Simpan pengaturan loyalty
— Rp10.000 = 100 poin, berlaku semua cabang", `REWARD_ADDED` "Tambah reward {nama} — 1.000 poin, nilai
Rp10.000" (ditambah " · minimum belanja Rp75.000" bila `minSubtotal` diisi), `REWARD_TOGGLED`
"Nonaktifkan reward {nama} (1.500 poin) — berlaku semua cabang", `LOGOUT` "Logout dari perangkat Cabang
{nama}". Kalimat baru: `DEVICE_ACTIVATED` "Tablet baru dipakai login pertama kali di Cabang {nama}",
`DEVICE_REVOKED` "Blokir perangkat {nama}", `LOGIN_FAILED` "PIN ditolak di perangkat Cabang {nama} —
{KODE}", `PIN_LOCKED` "Perangkat {nama} dikunci 5 menit — 5 PIN salah dalam 5 menit" / "Akun {nama}
dikunci 15 menit — 5 PIN salah berturut-turut".

## 4. Bentuk request/respons yang PRD belum tentukan

- `GET /devices` → `{ items: [Device] }` (hanya tablet yang pernah login; `id` = `X-Device-Id`,
  `firstSeenAt`); `DELETE /devices/{id}` → `{ device, changed }`.
- `POST /auth/refresh` — header `X-Device-Id` + `{ refreshToken }` → bentuk yang sama dengan `pin-login`
  (termasuk `context`).
- `POST /auth/switch-branch` → `{ changed, accessToken?, accessTokenExpiresIn?, refreshToken?, refreshTokenExpiresIn?, context }`;
  token `null` bila cabang sama.
- `POST /auth/logout` → `{ lastBranchId }`.
- `PATCH` toggle/ubah (branch, staff, service, reward, customer) → `{ <entitas>, changed }`.
- `PATCH /services/prices` → `{ items: [semua layanan], changedCount }`.
- `PUT /loyalty/rate` → `{ rate, changed }`.
- `GET /branches/overview` → `{ date, items: [{ branch, revenue, txCount, targetRatio, latestShift, activeCashiers }] }`.
- Nilai kosong dikirim sebagai `null` (bukan field dihilangkan), sesuai contoh PRD.

## 5. Keputusan perilaku yang perlu dikonfirmasi owner / tim Android

1. **Tanpa aktivasi perangkat** — lihat bagian 0.
2. **`X-Device-Id` wajib di `pin-login` dan `refresh`**, opsional di endpoint lain; bila dikirim di
   endpoint ber-sesi harus sama dengan tablet sesi (kalau tidak → 401).
3. **Sesi berakhir 18 jam sejak login**; rotasi refresh token tidak memperpanjangnya. "Satu hari
   operasional" dibaca sebagai batas mutlak.
4. **Satu perangkat, satu sesi staff.** Login PIN atau ganti staff mencabut sesi lain di tablet yang sama.
5. **Batas PIN per perangkat berlaku untuk semua penolakan setelah PIN dievaluasi** (`PIN_UNKNOWN`,
   `STAFF_INACTIVE`, `STAFF_WRONG_BRANCH`, `PIN_WRONG`), bukan hanya PIN yang salah — dua penolakan terakhir
   juga membocorkan bahwa PIN itu milik akun. Percobaan yang ditolak karena perangkat sedang terkunci
   tidak diaudit ulang (supaya kunci tidak diperpanjang terus).
6. **Kunci akun juga berlaku di `pin-login`**: PIN yang benar untuk akun yang sedang terkunci → `423`.
   Counter akun hanya bertambah di `verify-pin`/`switch-staff` (di login tanpa pilih nama, PIN salah tidak
   bisa diatribusikan ke akun).
7. **Percobaan yang memicu kunci tetap mendapat error aslinya**; `423` baru di percobaan berikutnya
   (sesuai skenario §16 #14 "percobaan ke-6").
8. **`STAFF_WRONG_BRANCH` menyebut nama pemilik PIN** seperti klien sekarang. Dengan batas 5 percobaan
   per perangkat risikonya kecil, tetapi owner mungkin ingin pesan yang lebih umum.
9. **`switch-staff` tidak menolak cabang nonaktif** (hanya login & buka shift yang ditolak, PRD §8.2), agar
   serah terima di shift yang sedang diselesaikan tetap bisa.
10. **Simpan price list tanpa perubahan tidak menulis audit ringkasan** (klien selalu menulisnya). Mengikuti
    pola "nilai sama → tanpa audit" dari PRD §8.2.
11. **Simpan rate yang sama dengan rate aktif tidak menyisipkan baris baru** di `loyalty_rates`.
12. **Opt-out lewat `PATCH /customers/{id}` membatalkan pesan WA `QUEUED` customer tsb**, sama seperti
    balasan STOP (§10.4).
13. **Owner boleh memblokir tablet yang sedang ia pakai** (sesinya ikut berakhir). Tidak ada kode error
    khusus seperti `BRANCH_IN_USE`.
14. **`minSubtotal` = 0 diperlakukan sebagai tanpa minimum** (`null`).
15. **Daftar tablet owner hanya menampilkan tablet yang pernah berhasil login**, supaya percobaan PIN
    dari ID instalasi karangan tidak memenuhi daftar.
16. **`staffProof` dari `verify-pin` sudah disimpan di M1** (tabel `staff_proofs`, sekali pakai, 5 menit),
    tetapi baru dipakai `POST /shifts` di M2.
