-- =============================================================================
-- V2 — hasil review skema M0
--
-- V1 sudah diterapkan di staging, jadi perbaikan ditulis sebagai migrasi baru. Semua perubahan di
-- sini menegakkan aturan PRD §7–§9 di level database; tidak ada kolom atau tabel baru. Data seed
-- pilot (cabang, staff, layanan, reward, rate) tidak tersentuh oleh constraint mana pun di bawah.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Order: default yang diam-diam salah
-- -----------------------------------------------------------------------------

-- §9.1: wa_status awal = customer?.optIn ? MENUNGGU : BELUM_OPTIN. Default MENUNGGU membuat order
-- walk-in terlihat "menunggu kiriman WA" bila service lupa mengisinya — service wajib memutuskan.
ALTER TABLE orders ALTER COLUMN wa_status DROP DEFAULT;

-- Reward selalu berbiaya poin (rewards.cost_points > 0), jadi order ber-reward tanpa poin ditukar
-- berarti redeem tidak tercatat.
ALTER TABLE orders DROP CONSTRAINT orders_reward_fields;
ALTER TABLE orders ADD CONSTRAINT orders_reward_fields CHECK (
    (reward_id IS NULL AND reward_name IS NULL AND redeemed_points = 0) OR
    (reward_id IS NOT NULL AND reward_name IS NOT NULL AND redeemed_points > 0)
);

ALTER TABLE orders ADD CONSTRAINT orders_seq_positive CHECK (seq > 0);

-- Order tidak pernah dihapus (void di P1 adalah pembalikan, bukan DELETE). CASCADE membuat satu
-- DELETE yang keliru menghapus item dan jejak event — lebih baik DELETE-nya gagal.
ALTER TABLE order_items DROP CONSTRAINT order_items_order_id_fkey;
ALTER TABLE order_items ADD CONSTRAINT order_items_order_id_fkey
    FOREIGN KEY (order_id) REFERENCES orders (id);
ALTER TABLE order_events DROP CONSTRAINT order_events_order_id_fkey;
ALTER TABLE order_events ADD CONSTRAINT order_events_order_id_fkey
    FOREIGN KEY (order_id) REFERENCES orders (id);

-- Kuantitas kelipatan step layanan: 0,5 untuk kg, 1 untuk satuan lain (CLAUDE.md "Data & Time").
-- unit di order_items adalah snapshot, dan step selalu mengikuti unit — sama seperti services.
ALTER TABLE order_items ADD CONSTRAINT order_items_unit_valid CHECK (unit IN ('kg', 'pcs', 'pasang', 'm²'));
ALTER TABLE order_items ADD CONSTRAINT order_items_qty_step CHECK (
    (unit = 'kg' AND qty % 0.5 = 0) OR (unit <> 'kg' AND qty % 1 = 0)
);

-- -----------------------------------------------------------------------------
-- Poin: arah mutasi & anti-dobel per order
-- -----------------------------------------------------------------------------

-- EARN selalu menambah, REDEEM selalu mengurangi. Order yang tidak menghasilkan poin tidak menulis
-- baris EARN bernilai 0.
ALTER TABLE points_ledger ADD CONSTRAINT points_ledger_delta_sign CHECK (
    (type = 'EARN' AND delta > 0) OR
    (type = 'REDEEM' AND delta < 0) OR
    (type IN ('IMPORT', 'ADJUST') AND delta <> 0)
);

-- Satu order memberi poin sekali dan menukar poin sekali, walau jalur sync dan online bertemu.
CREATE UNIQUE INDEX points_ledger_order_type_key ON points_ledger (order_id, type)
    WHERE order_id IS NOT NULL;

ALTER TABLE customers ADD CONSTRAINT customers_visits_non_negative CHECK (visits >= 0);

-- -----------------------------------------------------------------------------
-- Shift & kas
-- -----------------------------------------------------------------------------

-- §8.7: counted_cash awal = modal awal, bukan 0. Tanpa default, service yang membuka shift wajib
-- mengisinya; default 0 akan membuat rekap "kurang Rp500.000" bila terlupa.
ALTER TABLE shifts ALTER COLUMN counted_cash DROP DEFAULT;

-- SALE selalu menunjuk order tunai; entri lain tidak pernah.
ALTER TABLE cash_entries ADD CONSTRAINT cash_entries_sale_has_order CHECK (
    (kind = 'SALE') = (order_id IS NOT NULL)
);
-- Satu order tunai = satu entri SALE; satu shift = satu entri OPENING.
CREATE UNIQUE INDEX cash_entries_sale_order_key ON cash_entries (order_id) WHERE kind = 'SALE';
CREATE UNIQUE INDEX cash_entries_opening_shift_key ON cash_entries (shift_id) WHERE kind = 'OPENING';

-- -----------------------------------------------------------------------------
-- Idempotensi
-- -----------------------------------------------------------------------------

-- Key idempotensi milik perangkat yang membuatnya. Dengan PK hanya `key`, perangkat lain yang
-- memakai key yang sama akan menerima respons tersimpan milik perangkat pertama.
--
-- Catatan untuk M1: kolom `response` tidak boleh menyimpan respons yang berisi rahasia —
-- `POST /staff/{id}/reset-pin` (PIN baru, §8.3 "tidak boleh di-cache") dan endpoint `/auth/*`
-- (access/refresh token). Endpoint itu tidak memakai penyimpanan idempotensi.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (device_id, key);
