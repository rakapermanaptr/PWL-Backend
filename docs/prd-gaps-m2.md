# Celah PRD yang diisi di M2

> **Status: seluruh isi dokumen ini sudah diterapkan ke PRD Draft 1.2 (15 September 2026)** — §6, §7.2, §8.6,
> §8.7, §8.10, §11, §15, Lampiran A–C. Dokumen ini dipertahankan sebagai riwayat keputusan; bila berbeda, PRD yang
> berlaku.

Catatan untuk PRD Backend (sumber asli di repo Android `PrimaWash` → `docs/backend/`) dan untuk tim
Android, lanjutan dari [`prd-gaps-m1.md`](prd-gaps-m1.md). Semua yang ada di sini **sudah
diimplementasikan** di M2 (order, sinkronisasi offline, shift & kas, cache tablet, heartbeat) karena
endpoint-nya butuh jawaban, tetapi belum tertulis di PRD saat itu.

Aturan yang dipakai saat mengisi sama dengan M1: teks dari use case klien dipakai apa adanya; hanya yang
benar-benar belum ada yang didrafkan baru.

## 1. Kode error baru (usulan tambahan Lampiran B)

| Code | HTTP | Pesan | Kapan |
|---|---|---|---|
| `STAFF_PROOF_INVALID` | 422 | Verifikasi PIN sudah kedaluwarsa — pilih staff dan masukkan PIN lagi. | `POST /shifts` dengan `staffProof` kosong, tidak dikenal, sudah dipakai, kedaluwarsa (5 menit), dari tablet/cabang lain, atau staff-nya kini nonaktif/pindah cabang. |
| `CAPTURED_IN_FUTURE` | 422 | Waktu transaksi di tablet lebih maju dari jam server — cek jam tablet lalu sync ulang. | Per transaksi di `POST /orders/sync` bila `capturedAt` > jam server + 5 menit (PRD §11.2 menyebut 422 tanpa kode). |
| `DUPLICATE_TRANSACTION` | 409 | Transaksi ini sudah tersimpan sebagai {nomor} — muat ulang daftar order. | `POST /orders` dengan `clientTxId` yang sudah punya order tetapi `Idempotency-Key` berbeda (aplikasi kehilangan kuncinya). `details.order` berisi order yang ada. Kunci sama → respons tersimpan diputar ulang, bukan error ini. |

## 2. Pesan untuk kode yang sudah ada tetapi belum punya teks

| Code | HTTP | Pesan | Sumber |
|---|---|---|---|
| `INVALID_ITEM` | 422 | Layanan yang dipilih sudah tidak tersedia. | baru — `serviceId` yang tidak ada sama sekali (nama tidak diketahui) |
| `INVALID_ITEM` | 422 | Jumlah {nama} tidak valid. | PRD §8.6 |
| `INVALID_ITEM` (sync) | 422 | Harga {nama} tidak valid. | baru — `unitPrice` ≤ 0 di antrean offline |
| `REWARD_NOT_ELIGIBLE` | 422 | Reward ini sudah tidak tersedia. | baru — reward nonaktif/tidak ada. Minimum belanja memakai teks Lampiran B |
| `SHIFT_NOT_OPEN` (kas) | 422 | Shift Cabang {nama} belum dibuka. | PRD §8.7 |
| `SHIFT_CLOSED` (hitung kas) | 409 | Shift Cabang {nama} sudah ditutup. | Lampiran B, juga dipakai `PUT /shifts/{id}/counted-cash` |
| `NOT_FOUND` | 404 | Order tidak ditemukan. / Shift tidak ditemukan. | **beda dengan PRD §8.6** "Order {number} tidak ditemukan." — permintaan memakai `id`, jadi server tidak tahu nomornya |
| `VALIDATION_ERROR` | 400 | Maksimal 50 transaksi per sync. / Hitungan uang fisik tidak boleh negatif. / Cursor tidak valid — muat ulang data dari awal (bootstrap). | baru |

## 3. `action_type` audit & kalimat

Tidak ada `action_type` baru. Kalimat yang tidak tertulis di PRD:

| action_type | Kalimat `action` | Kapan |
|---|---|---|
| `REWARD_REDEEMED` | Redeem {reward} · {cost} poin · {customer} · {nomor} | Baris kedua di samping `ORDER_CREATED` saat order memakai reward (PRD §8.6 efek 8 menyebut barisnya, bukan kalimatnya). |
| `ORDER_SYNCED` (per transaksi) | Transaksi {nomor} · Rp… · Tunai · offline[ · ditandai: masuk setelah shift ditutup, harga beda dengan price list, …] | Satu per transaksi `CREATED`; ringkasan batch memakai kalimat PRD §11.2. |
| `CUSTOMER_REGISTERED` (offline) | sama dengan online; `metadata.offline = true` | Customer baru dari antrean offline yang nomornya belum terdaftar. |
| `STAFF_SWITCHED` | Login PIN sebagai {kasir, Cabang …} | `POST /shifts` dengan proof staff lain: tablet diserahkan ke pembuka shift. |

## 4. Bentuk request/respons yang PRD belum tentukan

- **Order** menambah `rewardId`, `createdAt`, `shiftId`, `flags` (kosong untuk online). Walk-in:
  `customerId = null`, `customerName = "Tanpa nama"`, `customerPhone = "—"`, dan `customer = null` di
  respons `POST /orders`.
- `GET /orders/{id}` → `{ order, events: [{ id, type, fromStatus, toStatus, staffId, deviceId, createdAt, payload }] }`;
  `type` ∈ `CREATED`, `STATUS_CHANGED`, `WA_STATUS_CHANGED`.
- `GET /orders/next-number` → `{ number, businessDate }`.
- `POST /orders/{id}/advance` → `ALREADY_COMPLETED` juga membawa `notificationQueued: false`.
- `POST /orders/sync` → tiap hasil selalu punya `clientTxId, status, order, error, customerIdMapping`
  (yang tidak berlaku `null`); `error = { code, message }`; `customerIdMapping = { clientId, serverId }`.
- **Shift** → `{ id, branchId, open, openedByStaffId, openedByName, openedAt, closedAt, closedByStaffId,
  openingCash, cashSales, transferSales, txCount, pointsIssued, countedCash, expectedCash, recap, version }`;
  `expectedCash` dihitung saat dibaca, `recap` dibekukan saat tutup (`null` selama terbuka).
- `GET /shifts/current` → `{ shift | null, entries }` (entri terlama dulu); `GET /shifts/latest` →
  `{ items }`; `GET /shifts` → `{ items, nextCursor }`.
- `POST /shifts` → `{ shift, session | null }`; `POST /shifts/{id}/cash-entries` → `{ entry, shift }`;
  `PUT /shifts/{id}/counted-cash` → `{ shift }`; `POST /shifts/{id}/close` → `{ shift, recap }`.
- `GET /sync/bootstrap` → `{ data: { branches, staff, services, rewards, loyaltyRates, customers, orders, shifts }, cursor }`;
  `GET /sync/changes` → `{ changes: { …koleksi yang sama… }, nextCursor, hasMore }`. Staff hanya
  `{ id, name, shortName, role, branchId, active }`.
- `POST /devices/heartbeat` — `pendingCount` wajib (≥ 0) → `{ serverTime }`.

## 5. Keputusan perilaku yang perlu dikonfirmasi owner / tim Android

1. **Cursor delta sync = watermark id transaksi Postgres, bukan `updated_at` + id** (PRD §8.10 menulis
   `updated_at`). `updated_at = now()` adalah waktu *mulai* transaksi: transaksi yang mulai lebih dulu
   tetapi commit belakangan akan tertinggal di belakang cursor dan perubahannya tidak pernah sampai ke
   tablet. Dengan watermark (V5, `change_xid`) tidak ada perubahan yang hilang; harganya satu baris kadang
   terkirim dua kali — klien cukup upsert. Transaksi database yang sangat lama menahan watermark (lebih
   banyak baris terkirim ulang), jadi pantau transaksi *idle* di produksi. Cursor bersifat opaque.
2. **Scope cache tablet**: order hanya cabang sesi; staff & shift — kasir: cabangnya (+ owner), owner:
   semua; cabang, katalog (termasuk nonaktif), rate, dan **seluruh customer** global. Pada skala 10× data
   customer penuh di bootstrap perlu ditinjau ulang.
3. **`OPT_IN_CONFIRM` dikirim sekali per persetujuan**: bersama transaksi pertama sejak `opt_in_at`
   terakhir (customer yang opt-out lalu opt-in lagi mendapat konfirmasi baru). `REMINDER_3_HARI` dijamin
   maksimal satu per order oleh unique index — pengirim & scheduler-nya tetap M5.
4. **`staffProof` menentukan pembuka shift.** Bila staff pada proof bukan pemilik sesi tablet, tablet
   diserahkan kepadanya: sesi lama berakhir dan respons berisi `session` baru (setara
   `sessionRepository.update { staffId }` di `OpenShiftUseCase`). Proof hanya dipakai bila semua aturan
   lain lolos — pembukaan yang ditolak tidak menghanguskannya. Respons yang diputar ulang lewat
   `Idempotency-Key` tidak berisi token (`session = null`).
5. **`Idempotency-Key` wajib** di `POST /orders`, `/orders/sync`, `/shifts`, `/shifts/{id}/cash-entries`,
   `/shifts/{id}/close` (tanpa kunci → 400). `advance` tidak memakainya — `fromStatus` sudah melindungi —
   dan `PUT counted-cash` tidak (idempoten secara alami).
6. **Antrean offline hanya ditolak** bila: jam tablet > server + 5 menit, ada redeem, keranjang kosong,
   layanan tidak dikenal, `qty`/harga tidak valid, `customerId` tidak ada, data customer baru tidak valid,
   atau **cabang belum pernah punya shift sama sekali** (tidak ada tempat mencatat uangnya). Format yang
   salah hanya menolak transaksi itu, bukan satu batch.
7. **Tanpa `shiftId` dan tanpa shift terbuka**, transaksi offline masuk ke shift terakhir cabang dengan
   flag `LATE_AFTER_SHIFT_CLOSE`.
8. **Transaksi yang masuk ke shift yang sudah ditutup** menambah `cash_sales`/`transfer_sales` shift itu
   (dan entri `SALE`), tetapi **rekap yang dibekukan tidak berubah** — selisihnya terlihat di laporan owner.
9. **Flag baru `CUSTOMER_PHONE_MATCHED`** (customer baru offline ternyata nomornya sudah terdaftar) dan
   nama flag `SERVICE_INACTIVE`, `STALE_CAPTURE` (PRD hanya menyebut "flag"). Daftar flag dikunci
   constraint database; flag baru butuh migrasi.
10. **`PRICE_MISMATCH` membandingkan dengan harga pada `capturedAt`** (dari riwayat perubahan harga), bukan
    harga sekarang — harga yang diubah owner setelah transaksi offline tidak memicu flag.
11. **`staffId` transaksi offline** dipakai sebagai kasir order bila staff itu boleh bekerja di cabang
    sesi; bila tidak dikenal, staff sesi yang dipakai.
12. **Order dengan total Rp0** (diskon reward menutup semua) tidak menulis entri kas `SALE`.
13. **Owner di `GET /orders` tanpa `branchId`** mendapat cabang sesi tablet; semua cabang hanya dengan
    `scope=all`.
14. **Hitungan kas sementara (`PUT counted-cash`) tidak diaudit**; hitungan final diaudit saat tutup.
15. **Heartbeat tidak diaudit** dan field `online` belum disimpan (hanya `pendingCount`, versi aplikasi,
    dan `last_seen_at`).
16. **`DUPLICATE_TRANSACTION` di `POST /orders`** (bagian 1) — klien cukup menghapus transaksi dari layar
    dan memuat ulang daftar order.
