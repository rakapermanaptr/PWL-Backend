-- =============================================================================
-- V3 — penyimpanan yang dibutuhkan autentikasi M1 (PRD §8.1, §12.2)
--
-- Menutup dua keputusan desain yang dicatat saat review skema M0:
--   * penyimpanan `staffProof` hasil POST /auth/verify-pin (sekali pakai, 5 menit);
--   * batas PIN salah per perangkat (5 salah dalam 5 menit → kunci 5 menit).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- staffProof — bukti bahwa PIN staff tertentu baru saja diverifikasi di perangkat ini.
--
-- Diterbitkan verify-pin, dipakai sekali oleh aksi yang butuh identitas staff lain (buka shift di
-- M2). Hanya hash token yang disimpan; token mentah hanya ada di respons verify-pin.
-- -----------------------------------------------------------------------------
CREATE TABLE staff_proofs (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash char(64)    NOT NULL,
    staff_id   uuid        NOT NULL REFERENCES staff (id),
    device_id  uuid        NOT NULL REFERENCES devices (id),
    branch_id  uuid        NOT NULL REFERENCES branches (id),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT staff_proofs_expiry_after_creation CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX staff_proofs_token_hash_key ON staff_proofs (token_hash);
CREATE INDEX staff_proofs_staff_idx ON staff_proofs (staff_id) WHERE used_at IS NULL;

-- -----------------------------------------------------------------------------
-- Batas PIN salah per perangkat (§12.2)
--
-- Setiap percobaan PIN yang gagal sudah wajib tercatat di audit_log sebagai LOGIN_FAILED dengan
-- device_id-nya, jadi jendela "5 salah dalam 5 menit" dihitung dari baris audit itu sendiri — tidak
-- ada tabel penghitung kedua yang bisa berbeda dari jejak audit. Kunci aktifnya disimpan di
-- perangkat supaya pengecekan sebelum mengevaluasi PIN cukup satu baris.
-- -----------------------------------------------------------------------------
ALTER TABLE devices ADD COLUMN pin_locked_until timestamptz;

CREATE INDEX audit_log_device_login_failed_idx ON audit_log (device_id, created_at DESC)
    WHERE action_type = 'LOGIN_FAILED';

-- -----------------------------------------------------------------------------
-- Sesi
-- -----------------------------------------------------------------------------

-- Refresh token dirotasi setiap dipakai; masa berlaku sesi tetap dihitung dari login (18 jam, satu
-- hari operasional), jadi rotasi tidak memperpanjang sesi tanpa batas.
ALTER TABLE sessions ADD COLUMN refreshed_at timestamptz;

-- Satu perangkat dipakai satu staff pada satu waktu: login atau ganti staff mencabut sesi lain di
-- perangkat yang sama.
CREATE INDEX sessions_device_idx ON sessions (device_id) WHERE revoked_at IS NULL;

-- Kode aktivasi yang belum dipakai per pembuat, untuk daftar/pembersihan.
CREATE INDEX device_activation_codes_open_idx ON device_activation_codes (expires_at) WHERE used_at IS NULL;
