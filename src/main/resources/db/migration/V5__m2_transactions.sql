-- =============================================================================
-- V5 — transaksi M2: order, shift & kas, sinkronisasi offline, delta sync (PRD §7.2, §8.6–§8.10, §11)
--
-- Tiga keputusan desain dari review skema M0 ditutup di sini:
--   * cursor delta sync = watermark transaction id, bukan updated_at (lihat bagian pertama);
--   * OPT_IN_CONFIRM dikirim sekali per opt-in (ditegakkan service, dikunci baris customer);
--     REMINDER_3_HARI maksimal sekali per order (unique index);
--   * staffProof dipakai sekali oleh POST /shifts (kolom sudah ada di V3).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Delta sync: watermark transaction id (§8.10)
--
-- `updated_at = now()` adalah waktu MULAI transaksi. Transaksi yang mulai lebih dulu tetapi commit
-- belakangan menulis updated_at yang lebih tua dari cursor yang sudah dipegang tablet, dan
-- perubahannya tidak pernah terkirim. Karena itu setiap baris yang disinkron membawa id transaksi
-- penulisnya (`change_xid`). Cursor = xmin snapshot saat server membaca: semua transaksi yang belum
-- commit saat itu punya xid >= xmin, jadi pembacaan berikutnya (`change_xid >= cursor`) pasti
-- menangkapnya. Harga yang dibayar: kadang satu baris terkirim dua kali — klien cukup upsert.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_change_xid() RETURNS trigger AS $$
BEGIN
    NEW.change_xid = pg_current_xact_id();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE branches      ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE staff         ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE services      ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE rewards       ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE loyalty_rates ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE customers     ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE orders        ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE shifts        ADD COLUMN change_xid xid8 NOT NULL DEFAULT pg_current_xact_id();

CREATE TRIGGER branches_set_change_xid BEFORE INSERT OR UPDATE ON branches
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
CREATE TRIGGER staff_set_change_xid BEFORE INSERT OR UPDATE ON staff
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
CREATE TRIGGER services_set_change_xid BEFORE INSERT OR UPDATE ON services
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
CREATE TRIGGER rewards_set_change_xid BEFORE INSERT OR UPDATE ON rewards
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
-- loyalty_rates tidak pernah di-UPDATE (trigger V1 menolaknya); cukup saat INSERT.
CREATE TRIGGER loyalty_rates_set_change_xid BEFORE INSERT ON loyalty_rates
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
CREATE TRIGGER customers_set_change_xid BEFORE INSERT OR UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
CREATE TRIGGER orders_set_change_xid BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();
CREATE TRIGGER shifts_set_change_xid BEFORE INSERT OR UPDATE ON shifts
    FOR EACH ROW EXECUTE FUNCTION set_change_xid();

CREATE INDEX customers_change_idx ON customers (change_xid, id);
CREATE INDEX orders_branch_change_idx ON orders (branch_id, change_xid, id);
CREATE INDEX shifts_branch_change_idx ON shifts (branch_id, change_xid, id);

-- -----------------------------------------------------------------------------
-- Shift & kas
-- -----------------------------------------------------------------------------

-- Order dan entri kas selalu milik shift di cabang yang sama. Kunci gabungan membuat FK di bawah
-- menegakkannya di database, bukan hanya di service.
ALTER TABLE shifts ADD CONSTRAINT shifts_id_branch_key UNIQUE (id, branch_id);
ALTER TABLE orders ADD CONSTRAINT orders_shift_branch_fkey
    FOREIGN KEY (shift_id, branch_id) REFERENCES shifts (id, branch_id);
ALTER TABLE cash_entries ADD CONSTRAINT cash_entries_shift_branch_fkey
    FOREIGN KEY (shift_id, branch_id) REFERENCES shifts (id, branch_id);

ALTER TABLE shifts ADD CONSTRAINT shifts_totals_non_negative CHECK (
    cash_sales >= 0 AND transfer_sales >= 0 AND tx_count >= 0 AND points_issued >= 0 AND counted_cash >= 0
);
-- §8.7: rekap dibekukan tepat saat shift ditutup — tidak sebelum, dan tidak pernah hilang sesudahnya.
ALTER TABLE shifts ADD CONSTRAINT shifts_recap_frozen_on_close CHECK (
    (closed_at IS NULL AND recap_expected IS NULL AND recap_actual IS NULL) OR
    (closed_at IS NOT NULL AND recap_expected IS NOT NULL AND recap_actual IS NOT NULL)
);

ALTER TABLE daily_sales ADD CONSTRAINT daily_sales_non_negative CHECK (revenue >= 0 AND tx_count >= 0);

-- -----------------------------------------------------------------------------
-- Order
-- -----------------------------------------------------------------------------

-- Flag hanya dari daftar yang dikenal laporan owner (§11.2). Flag baru = migrasi baru.
ALTER TABLE orders ADD CONSTRAINT orders_flags_known CHECK (
    flags <@ ARRAY[
        'LATE_AFTER_SHIFT_CLOSE', 'PRICE_MISMATCH', 'SERVICE_INACTIVE', 'STALE_CAPTURE',
        'CUSTOMER_PHONE_MATCHED'
    ]::text[]
);
-- Flag hanya muncul dari jalur sinkronisasi offline; order online ditolak bila ada yang janggal.
ALTER TABLE orders ADD CONSTRAINT orders_online_has_no_flags CHECK (source = 'OFFLINE_SYNC' OR flags = '{}');

ALTER TABLE orders ADD CONSTRAINT orders_version_positive CHECK (version >= 1);

-- Daftar order (§8.6): default "belum SELESAI + 7 hari terakhir", urut captured_at terbaru.
CREATE INDEX orders_branch_date_idx ON orders (branch_id, business_date DESC);
CREATE INDEX orders_branch_captured_idx ON orders (branch_id, captured_at DESC, id DESC);

-- -----------------------------------------------------------------------------
-- Outbox WhatsApp (tetap tanpa pengirim sampai M5)
-- -----------------------------------------------------------------------------

-- §10.2: pengingat 3 hari maksimal satu kali per order, walau scheduler berjalan dua kali.
CREATE UNIQUE INDEX wa_messages_reminder_once_key ON wa_messages (order_id)
    WHERE template = 'REMINDER_3_HARI';
-- OPT_IN_CONFIRM sekali per opt-in: service mengecek pesan sejak opt_in_at terakhir.
CREATE INDEX wa_messages_customer_template_idx ON wa_messages (customer_id, template, queued_at DESC);

-- -----------------------------------------------------------------------------
-- Perangkat & idempotensi
-- -----------------------------------------------------------------------------
ALTER TABLE devices ADD CONSTRAINT devices_pending_count_non_negative CHECK (pending_count >= 0);

ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_status_code_valid CHECK (status_code BETWEEN 200 AND 299);
