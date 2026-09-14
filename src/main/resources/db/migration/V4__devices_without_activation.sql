-- =============================================================================
-- V4 — perangkat tanpa langkah aktivasi (keputusan owner, 14 September 2026)
--
-- Pengguna tablet adalah kasir dan owner yang tidak boleh direpotkan langkah teknis, jadi login cukup
-- "pilih cabang → ketik PIN". Aplikasi membuat ID instalasinya sendiri saat pertama dibuka dan
-- mengirimnya di header X-Device-Id; server mencatat perangkat itu pada percobaan login pertamanya.
-- Tidak ada lagi token perangkat maupun kode aktivasi.
--
-- Yang tetap dipakai dari tabel devices: batas PIN salah per perangkat, satu sesi per perangkat,
-- pemblokiran tablet oleh owner, dan (M2) kunci idempotensi serta heartbeat antrean offline.
-- =============================================================================

DROP TABLE device_activation_codes;

DROP INDEX devices_token_hash_key;
ALTER TABLE devices DROP COLUMN token_hash;

-- activated_at sekarang berarti "pertama kali terlihat"; activated_by tidak lagi diisi.
ALTER TABLE devices ALTER COLUMN name SET DEFAULT 'Tablet';
