# Hak akses database & catatan operasional

Dokumen pendek untuk deployment. Skema-nya sendiri ada di
`src/main/resources/db/migration/V1__init.sql`.

## Dua role, bukan satu

Migrasi dijalankan oleh **pemilik skema**, aplikasi berjalan sebagai role terbatas. Pemisahan ini
yang membuat `audit_log` benar-benar append-only: aplikasi tidak punya hak `UPDATE`/`DELETE` di sana
sama sekali, bukan sekadar tidak pernah memanggilnya.

Urutannya penting: `REVOKE` pada `audit_log` baru bisa dijalankan setelah tabelnya dibuat migrasi.

```sql
-- 1. Sebagai admin cluster (di DigitalOcean: doadmin), terhadap database bawaan (defaultdb).
CREATE ROLE pwl_migrator LOGIN PASSWORD '<dari secret manager>';
CREATE ROLE pwl_app      LOGIN PASSWORD '<dari secret manager>';
-- PG16: admin harus anggota role ini untuk membuat database atas nama role tersebut.
GRANT pwl_migrator TO doadmin;
CREATE DATABASE pwl OWNER pwl_migrator;
REVOKE ALL ON DATABASE pwl FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE pwl TO pwl_app;

-- 2. Masih sebagai admin, terhadap database pwl — sebelum migrasi pertama.
GRANT USAGE ON SCHEMA public TO pwl_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pwl_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pwl_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pwl_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO pwl_app;
```

3. Jalankan migrasi sebagai `pwl_migrator` (`./gradlew flywayMigrate` dengan `DATABASE_USER=pwl_migrator`).

```sql
-- 4. Sebagai pwl_migrator, terhadap pwl — setelah V1 diterapkan.
-- Jejak audit: hanya boleh ditambah dan dibaca.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM pwl_app;
-- Riwayat rate loyalty: baris baru saja, tidak pernah diubah (P0 #6).
REVOKE UPDATE, DELETE, TRUNCATE ON loyalty_rates FROM pwl_app;
-- Riwayat Flyway milik migrator; aplikasi hanya membaca.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM pwl_app;
```

Tabel yang dibuat migrasi berikutnya otomatis mendapat hak dasar lewat `ALTER DEFAULT PRIVILEGES`.
Kalau migrasi baru menambah tabel yang juga append-only, `REVOKE`-nya ditulis di migrasi itu sendiri.

Cek cepat setelah setup, login sebagai `pwl_app`: `UPDATE audit_log …`, `DELETE FROM loyalty_rates …`,
dan `CREATE TABLE …` harus gagal dengan `permission denied`; `UPDATE branches …` harus berhasil.

`DATABASE_USER` aplikasi di staging/produksi adalah `pwl_app`. Flyway dijalankan terpisah dengan
`pwl_migrator` (job migrasi sebelum rollout, `bin/pwl-migrate`), **bukan** oleh proses API —
di staging/prod `RUN_MIGRATIONS_ON_BOOT` bernilai `false`. Job migrasi hanya menerima variabel
`DATABASE_*`; ia tidak memegang `JWT_SECRET` maupun `PIN_PEPPER`. Di `dev` satu role saja
(`pwl`) sudah cukup dan migrasi ikut berjalan saat boot.

## Lapis kedua: trigger

Selain hak akses, `V1__init.sql` memasang trigger yang menolak `UPDATE`/`DELETE` pada `audit_log`
dan `loyalty_rates`. Trigger tidak ikut kena pada `TRUNCATE`, yang memang hanya dipakai oleh suite
integration test untuk membersihkan container-nya sendiri.

## Backup & retensi (PRD §13)

| Hal | Nilai |
|---|---|
| Backup | Harian + PITR 7 hari; RPO ≤ 5 menit, RTO ≤ 1 jam |
| Retensi order & audit | ≥ 5 tahun |
| `idempotency_keys` | Dibersihkan scheduler setelah 7 hari |
| Payload webhook | 30 hari (mulai M5) |

## Zona waktu

Server dan database berjalan UTC (`TZ=UTC`, `PGTZ=UTC`, pool menjalankan `SET TIME ZONE 'UTC'`).
Tanggal bisnis dihitung di aplikasi dengan `Asia/Jakarta` dari `captured_at` — tidak pernah dari
zona sistem, dan tidak pernah dari `now()`.
