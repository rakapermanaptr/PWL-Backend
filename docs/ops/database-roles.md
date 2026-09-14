# Hak akses database & catatan operasional

Dokumen pendek untuk deployment. Skema-nya sendiri ada di
`src/main/resources/db/migration/V1__init.sql`.

## Dua role, bukan satu

Migrasi dijalankan oleh **pemilik skema**, aplikasi berjalan sebagai role terbatas. Pemisahan ini
yang membuat `audit_log` benar-benar append-only: aplikasi tidak punya hak `UPDATE`/`DELETE` di sana
sama sekali, bukan sekadar tidak pernah memanggilnya.

```sql
-- Dijalankan sekali per environment oleh DBA/owner skema.
CREATE ROLE pwl_migrator LOGIN PASSWORD '<dari secret manager>';
CREATE ROLE pwl_app      LOGIN PASSWORD '<dari secret manager>';

ALTER DATABASE pwl OWNER TO pwl_migrator;
GRANT USAGE ON SCHEMA public TO pwl_app;

-- Hak dasar aplikasi
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO pwl_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pwl_app;

-- Jejak audit: hanya boleh ditambah dan dibaca.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM pwl_app;

-- Riwayat rate loyalty: baris baru saja, tidak pernah diubah (P0 #6).
REVOKE UPDATE, DELETE, TRUNCATE ON loyalty_rates FROM pwl_app;

-- Tabel yang dibuat migrasi berikutnya mengikuti pola yang sama.
ALTER DEFAULT PRIVILEGES FOR ROLE pwl_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pwl_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pwl_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO pwl_app;
```

`DATABASE_USER` aplikasi di staging/produksi adalah `pwl_app`. Flyway dijalankan terpisah dengan
`pwl_migrator` (job migrasi sebelum rollout), **bukan** oleh proses API. Di `dev` satu role saja
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
