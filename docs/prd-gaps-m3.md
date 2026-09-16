# Celah PRD yang diisi di M3

> **Status: menunggu persetujuan owner.** Setelah disetujui, isinya diterapkan ke PRD (§8.9, §9.4,
> Lampiran B) seperti yang dilakukan untuk [`prd-gaps-m1.md`](prd-gaps-m1.md) dan
> [`prd-gaps-m2.md`](prd-gaps-m2.md), dan dokumen ini tinggal menjadi riwayat. Bila berbeda, PRD yang
> berlaku.

Lanjutan dari M2. Bagian ini mencakup **`GET /reports/dashboard` saja** — bagian M3 lainnya
(`/reports/daily-sales`, `/audit`, impor CSV) belum dikerjakan dan akan ditambahkan ke dokumen ini saat
menyusul.

PRD §9.4 menyebut nama setiap field dashboard dan definisinya, tetapi tidak menyebut bentuk JSON-nya,
batas parameternya, atau apa yang terjadi di kasus kosong. Yang di bawah ini adalah jawaban yang dipakai
implementasi.

## 1. Parameter dan batasnya

| Parameter | Default | Batas | Alasan |
|---|---|---|---|
| `date` | hari ini (WIB) | `YYYY-MM-DD` | PRD §9.4 "default hari ini". |
| `periodDays` | `30` | 1–365, di luar itu `400` | PRD menyebut default 30 (T11) tanpa batas atas. 365 menahan query yang tidak masuk akal tanpa menghalangi laporan setahun. |
| `auditRangeDays` | `1` | hanya `1`, `7`, `30`; selainnya `400` | PRD §9.4 menulis "(1/7/30)" — diperlakukan sebagai pilihan tertutup, bukan sekadar contoh. |
| `branchId` | semua cabang | cabang tidak dikenal → `404` | Konsisten dengan endpoint lain; kosong = semua cabang (owner). |

Pesan `400` baru (usulan tambahan Lampiran B, `VALIDATION_ERROR`):

| Pesan | Kapan |
|---|---|
| Parameter periodDays harus angka 1–365. | `periodDays` bukan angka atau di luar rentang |
| Parameter auditRangeDays harus 1, 7, atau 30. | `auditRangeDays` di luar tiga pilihan |

Keduanya mengikuti pola pesan yang sudah ada ("Parameter limit harus angka 1–100.").

## 2. Rasio: `null` ≠ 0

PRD menulis "null bila 0" untuk `optInRate` dan `deliveryRate`. Aturan yang dipakai lebih luas: **setiap
rasio bernilai `null` bila penyebutnya 0**, termasuk `redemptionRate` dan `targetRatio` (target cabang
belum diisi). Cabang tanpa customer melaporkan "belum ada data", bukan "0% opt-in" — itu dua hal berbeda
untuk owner.

Rasio dikirim sebagai angka 0–1 dengan 4 desimal (`0.8235`), bukan persen. `targetRatio` boleh > 1.

## 3. `deliveryRate` sebelum M5

Dihitung dari pesan `STATUS_SIAP_DIAMBIL` yang di-*queue* dalam periode: `delivered ÷ (delivered +
failed)`. Baris berstatus `QUEUED` **tidak** dihitung sebagai gagal — sampai worker M5 berjalan pesan
belum pernah punya kesempatan terkirim, jadi nilainya `null` ("belum ada data"), bukan 0%. Ini konsisten
dengan T6: jangan mengaku tahu status kirim yang belum terjadi.

`READ` dihitung sebagai *delivered* — pesan yang sama satu langkah lebih jauh; kalau tidak, rasio justru
turun ketika customer membaca pesannya.

## 4. Bentuk `chart[]`

PRD: "7 hari s.d. `date`: omzet per cabang di scope". Bentuk yang dipakai:

```jsonc
"chart": [ { "date": "2026-09-09", "total": 3900000,
             "branches": [ { "branchId": "…", "revenue": 2100000 } ] } ]
```

Selalu **7 hari berurutan terlama dulu**, dan setiap hari memuat **semua cabang dalam scope** meski
nol — klien menggambar langsung tanpa perlu mengisi celah sendiri. `total` disertakan agar chart satu
garis tidak perlu menjumlah sendiri.

## 5. `topServices[]`

Omzet per nama layanan dihitung dari **subtotal item**, yaitu sebelum diskon reward yang berlaku di level
order. Akibatnya Σ `topServices.revenue` bisa sedikit lebih besar dari omzet bersih periode; yang diukur
adalah komposisi apa yang dijual, dan `share` konsisten di dalam dirinya sendiri (jumlahnya 1).

4 teratas + `"Lainnya"`; baris `"Lainnya"` hanya muncul bila memang ada sisa. Layanan dengan omzet 0
tidak masuk daftar. Urutan omzet sama dipecah menurut nama agar daftar tidak bertukar posisi antar
refresh.

## 6. Batas `audit[]` di dashboard

PRD menulis "entri `auditRangeDays` terakhir" tanpa batas jumlah — pada jendela 30 hari itu bisa ribuan
baris dalam satu respons. Dashboard membawa **maksimal 100 entri terbaru** dan menambahkan field
`auditTruncated: boolean`; sisanya lewat `GET /audit` yang memang punya filter dan paging (PRD §8.9).
Field ini tambahan di luar daftar §9.4.

Jendela audit dihitung dari **tanggal bisnis `date`**, bukan dari `now()`: `auditRangeDays = 7` pada
`date = 2026-09-10` berarti 4–10 September WIB. Dengan begitu mengganti `date` menggeser seluruh halaman
secara konsisten.

## 7. `pendingSync`

Σ `devices.pending_count` dari tablet yang **`last_branch_id`-nya ada di scope dan belum diblokir**.
Tablet yang belum pernah memilih cabang tidak masuk hitungan cabang mana pun. PRD tidak menyebut
perlakuan tablet yang sudah dicabut owner.

## 8. Akses

Owner saja, sesuai matriks akses §4.1 ("Laporan, log audit: ✘ kasir"). Kasir mendapat `403 FORBIDDEN`
dengan pesan yang sudah ada, "Fitur ini hanya untuk owner/admin."
