-- =============================================================================
-- V1 — skema awal pwl-cashier (PRD Backend REST API v1, §7 Model data)
--
-- Aturan yang ditegakkan di level database, bukan hanya di kode:
--   * uang    = BIGINT rupiah, kuantitas = NUMERIC(7,1)
--   * waktu   = timestamptz, selalu disimpan UTC
--   * enum    = TEXT + CHECK (bukan tipe enum Postgres) supaya nilai baru
--               (mis. pembayaran QRIS di fase 2) cukup lewat migrasi ALTER
--   * satu shift terbuka per cabang, PIN unik antar staff aktif, nomor order
--     tidak bentrok, sync offline tidak pernah menggandakan order
--
-- Migrasi yang sudah di-merge tidak pernah diedit — tambahkan V2, V3, …
-- =============================================================================

-- Dipakai semua tabel untuk menjaga updated_at tanpa bergantung pada aplikasi.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- -----------------------------------------------------------------------------
-- Cabang & staff
-- -----------------------------------------------------------------------------
CREATE TABLE branches (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         varchar(5)  NOT NULL,
    name         text        NOT NULL,
    address      text        NOT NULL DEFAULT '',
    phone        text        NOT NULL DEFAULT '',
    hours        text        NOT NULL DEFAULT '',
    daily_target bigint      NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    sort_order   int         NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT branches_code_upper CHECK (code = upper(code)),
    CONSTRAINT branches_target_positive CHECK (daily_target > 0)
);
CREATE UNIQUE INDEX branches_code_key ON branches (code);
CREATE TRIGGER branches_set_updated_at BEFORE UPDATE ON branches
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE staff (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name             text        NOT NULL,
    short_name       text        NOT NULL,
    role             text        NOT NULL,
    branch_id        uuid        REFERENCES branches (id),
    -- HMAC-SHA256(PIN_PEPPER, pin) — pepper ada di secret manager, tidak pernah di database (T1).
    pin_lookup       char(64)    NOT NULL,
    pin_hash         text        NOT NULL,
    pin_failed_count int         NOT NULL DEFAULT 0,
    pin_locked_until timestamptz,
    active           boolean     NOT NULL DEFAULT true,
    last_login_at    timestamptz,
    sort_order       int         NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT staff_role_valid CHECK (role IN ('KASIR', 'OWNER')),
    -- Kasir selalu terikat satu cabang; owner lintas cabang (branch_id NULL).
    CONSTRAINT staff_branch_matches_role CHECK (
        (role = 'KASIR' AND branch_id IS NOT NULL) OR (role = 'OWNER' AND branch_id IS NULL)
    )
);
-- Login tanpa memilih nama: PIN harus unik di antara staff aktif (T2 → 409 PIN_CONFLICT).
CREATE UNIQUE INDEX staff_pin_lookup_active_key ON staff (pin_lookup) WHERE active;
CREATE INDEX staff_branch_idx ON staff (branch_id) WHERE active;
CREATE TRIGGER staff_set_updated_at BEFORE UPDATE ON staff
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- Perangkat & sesi
-- -----------------------------------------------------------------------------
CREATE TABLE devices (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name           text        NOT NULL,
    platform       text        NOT NULL DEFAULT 'ANDROID',
    app_version    text        NOT NULL DEFAULT '',
    last_branch_id uuid        REFERENCES branches (id),
    token_hash     char(64)    NOT NULL,
    activated_by   uuid        REFERENCES staff (id),
    activated_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at   timestamptz,
    pending_count  int         NOT NULL DEFAULT 0,
    revoked_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX devices_token_hash_key ON devices (token_hash);
CREATE INDEX devices_branch_idx ON devices (last_branch_id) WHERE revoked_at IS NULL;
CREATE TRIGGER devices_set_updated_at BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE device_activation_codes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash       char(64)    NOT NULL,
    created_by      uuid        NOT NULL REFERENCES staff (id),
    branch_id       uuid        REFERENCES branches (id),
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    used_by_device  uuid        REFERENCES devices (id),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX device_activation_codes_hash_key ON device_activation_codes (code_hash);

CREATE TABLE sessions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id          uuid        NOT NULL REFERENCES devices (id),
    staff_id           uuid        NOT NULL REFERENCES staff (id),
    branch_id          uuid        REFERENCES branches (id),
    refresh_token_hash char(64)    NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz NOT NULL,
    revoked_at         timestamptz
);
CREATE UNIQUE INDEX sessions_refresh_token_key ON sessions (refresh_token_hash);
CREATE INDEX sessions_staff_idx ON sessions (staff_id) WHERE revoked_at IS NULL;

-- -----------------------------------------------------------------------------
-- Customer & poin
-- -----------------------------------------------------------------------------
CREATE TABLE customers (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                text        NOT NULL,
    phone               text        NOT NULL,
    phone_digits        varchar(13) NOT NULL,
    points_balance      bigint      NOT NULL DEFAULT 0,
    opt_in              boolean     NOT NULL DEFAULT false,
    opt_in_at           timestamptz,
    opt_out_at          timestamptz,
    visits              int         NOT NULL DEFAULT 0,
    home_branch_id      uuid        NOT NULL REFERENCES branches (id),
    created_by_staff_id uuid        REFERENCES staff (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customers_points_non_negative CHECK (points_balance >= 0),
    CONSTRAINT customers_phone_digits_format CHECK (phone_digits ~ '^08[0-9]{8,11}$')
);
-- Satu nomor HP = satu customer, lintas cabang (409 PHONE_ALREADY_REGISTERED).
CREATE UNIQUE INDEX customers_phone_digits_key ON customers (phone_digits);
CREATE INDEX customers_name_idx ON customers (lower(name));
CREATE TRIGGER customers_set_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- Katalog: layanan, riwayat harga, rate loyalty, reward
-- -----------------------------------------------------------------------------
CREATE TABLE services (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category   text          NOT NULL,
    name       text          NOT NULL,
    price      bigint        NOT NULL,
    unit       text          NOT NULL,
    step       numeric(3, 1) NOT NULL,
    active     boolean       NOT NULL DEFAULT true,
    sort_order int           NOT NULL DEFAULT 0,
    created_at timestamptz   NOT NULL DEFAULT now(),
    updated_at timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT services_category_valid CHECK (
        category IN ('KILOAN_REGULER', 'KILOAN_EXPRESS', 'SATUAN', 'DRY_CLEAN')
    ),
    CONSTRAINT services_unit_valid CHECK (unit IN ('kg', 'pcs', 'pasang', 'm²')),
    CONSTRAINT services_price_positive CHECK (price > 0),
    -- step 0,5 hanya untuk kiloan; satuan selalu 1,0
    CONSTRAINT services_step_matches_unit CHECK (
        (unit = 'kg' AND step = 0.5) OR (unit <> 'kg' AND step = 1.0)
    )
);
CREATE UNIQUE INDEX services_name_key ON services (lower(name));
CREATE TRIGGER services_set_updated_at BEFORE UPDATE ON services
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE service_price_history (
    id         bigserial PRIMARY KEY,
    service_id uuid        NOT NULL REFERENCES services (id),
    old_price  bigint      NOT NULL,
    new_price  bigint      NOT NULL,
    changed_by uuid        NOT NULL REFERENCES staff (id),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX service_price_history_service_idx ON service_price_history (service_id, changed_at DESC);

-- Riwayat murni: baris disisipkan, tidak pernah diubah. Rate sebuah transaksi = baris dengan
-- effective_from <= captured_at terbaru (P0 #6) — bukan rate saat ini.
CREATE TABLE loyalty_rates (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rupiah_per_step bigint      NOT NULL,
    points_per_step bigint      NOT NULL,
    effective_from  timestamptz NOT NULL,
    created_by      uuid        REFERENCES staff (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT loyalty_rates_rupiah_min CHECK (rupiah_per_step >= 1000),
    CONSTRAINT loyalty_rates_points_min CHECK (points_per_step >= 1)
);
CREATE UNIQUE INDEX loyalty_rates_effective_from_key ON loyalty_rates (effective_from);

CREATE TABLE rewards (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL,
    cost_points  bigint      NOT NULL,
    value_rupiah bigint      NOT NULL,
    note         text        NOT NULL DEFAULT '',
    -- T9: minimum belanja didokumentasikan tapi tidak pernah ditegakkan klien; di sini opsional
    -- dan divalidasi server saat redeem.
    min_subtotal bigint,
    used_count   bigint      NOT NULL DEFAULT 0,
    active       boolean     NOT NULL DEFAULT true,
    sort_order   int         NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT rewards_cost_positive CHECK (cost_points > 0),
    CONSTRAINT rewards_value_positive CHECK (value_rupiah > 0),
    CONSTRAINT rewards_min_subtotal_positive CHECK (min_subtotal IS NULL OR min_subtotal > 0)
);
CREATE TRIGGER rewards_set_updated_at BEFORE UPDATE ON rewards
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- Shift & kas
-- -----------------------------------------------------------------------------
CREATE TABLE shifts (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id           uuid        NOT NULL REFERENCES branches (id),
    opened_by_staff_id  uuid        NOT NULL REFERENCES staff (id),
    opened_by_name      text        NOT NULL,
    opened_at           timestamptz NOT NULL DEFAULT now(),
    closed_at           timestamptz,
    closed_by_staff_id  uuid        REFERENCES staff (id),
    opening_cash        bigint      NOT NULL,
    cash_sales          bigint      NOT NULL DEFAULT 0,
    transfer_sales      bigint      NOT NULL DEFAULT 0,
    tx_count            int         NOT NULL DEFAULT 0,
    points_issued       bigint      NOT NULL DEFAULT 0,
    counted_cash        bigint      NOT NULL DEFAULT 0,
    recap_expected      bigint,
    recap_actual        bigint,
    version             int         NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT shifts_opening_cash_positive CHECK (opening_cash > 0),
    CONSTRAINT shifts_closed_fields CHECK (
        (closed_at IS NULL AND closed_by_staff_id IS NULL) OR
        (closed_at IS NOT NULL AND closed_by_staff_id IS NOT NULL)
    )
);
-- Dua tablet membuka shift bersamaan: satu menang, satu dapat 409 SHIFT_ALREADY_OPEN.
CREATE UNIQUE INDEX shifts_one_open_per_branch ON shifts (branch_id) WHERE closed_at IS NULL;
CREATE INDEX shifts_branch_opened_idx ON shifts (branch_id, opened_at DESC);
CREATE TRIGGER shifts_set_updated_at BEFORE UPDATE ON shifts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- Order
-- -----------------------------------------------------------------------------
CREATE TABLE order_number_counters (
    branch_id     uuid NOT NULL REFERENCES branches (id),
    business_date date NOT NULL,
    last_seq      int  NOT NULL DEFAULT 0,
    PRIMARY KEY (branch_id, business_date)
);

CREATE TABLE orders (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    number            varchar(20) NOT NULL,
    branch_id         uuid        NOT NULL REFERENCES branches (id),
    business_date     date        NOT NULL,
    seq               int         NOT NULL,
    client_tx_id      uuid        NOT NULL,
    source            text        NOT NULL DEFAULT 'ONLINE',
    customer_id       uuid        REFERENCES customers (id),
    customer_name     text        NOT NULL,
    customer_phone    text        NOT NULL DEFAULT '—',
    note              text        NOT NULL DEFAULT '',
    subtotal          bigint      NOT NULL,
    discount          bigint      NOT NULL DEFAULT 0,
    total             bigint      NOT NULL,
    reward_id         uuid        REFERENCES rewards (id),
    reward_name       text,
    redeemed_points   bigint      NOT NULL DEFAULT 0,
    earned_points     bigint      NOT NULL DEFAULT 0,
    loyalty_rate_id   uuid        NOT NULL REFERENCES loyalty_rates (id),
    payment           text        NOT NULL,
    status            text        NOT NULL DEFAULT 'DITERIMA',
    wa_status         text        NOT NULL DEFAULT 'MENUNGGU',
    shift_id          uuid        NOT NULL REFERENCES shifts (id),
    staff_id          uuid        NOT NULL REFERENCES staff (id),
    device_id         uuid        REFERENCES devices (id),
    captured_at       timestamptz NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    status_changed_at timestamptz NOT NULL DEFAULT now(),
    flags             text[]      NOT NULL DEFAULT '{}',
    version           int         NOT NULL DEFAULT 1,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT orders_source_valid CHECK (source IN ('ONLINE', 'OFFLINE_SYNC')),
    CONSTRAINT orders_payment_valid CHECK (payment IN ('TUNAI', 'TRANSFER')),
    CONSTRAINT orders_status_valid CHECK (status IN ('DITERIMA', 'PROSES', 'SIAP', 'SELESAI')),
    CONSTRAINT orders_wa_status_valid CHECK (wa_status IN ('TERKIRIM', 'MENUNGGU', 'GAGAL', 'BELUM_OPTIN')),
    CONSTRAINT orders_amounts_consistent CHECK (subtotal >= 0 AND discount >= 0 AND total = subtotal - discount AND total >= 0),
    CONSTRAINT orders_points_non_negative CHECK (redeemed_points >= 0 AND earned_points >= 0),
    CONSTRAINT orders_reward_fields CHECK (
        (reward_id IS NULL AND reward_name IS NULL AND redeemed_points = 0) OR
        (reward_id IS NOT NULL AND reward_name IS NOT NULL)
    )
);
-- Antrean offline yang terkirim dua kali tidak pernah menghasilkan order kedua.
CREATE UNIQUE INDEX orders_client_tx_id_key ON orders (client_tx_id);
-- Nomor order tidak bentrok walau dua perangkat menyimpan bersamaan (T4).
CREATE UNIQUE INDEX orders_branch_date_seq_key ON orders (branch_id, business_date, seq);
CREATE UNIQUE INDEX orders_branch_date_number_key ON orders (branch_id, business_date, number);
CREATE INDEX orders_branch_status_idx ON orders (branch_id, status, captured_at DESC);
CREATE INDEX orders_customer_idx ON orders (customer_id, captured_at DESC);
CREATE INDEX orders_shift_idx ON orders (shift_id);
CREATE INDEX orders_number_idx ON orders (number);
CREATE TRIGGER orders_set_updated_at BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE order_items (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   uuid          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    position   int           NOT NULL,
    service_id uuid          NOT NULL REFERENCES services (id),
    name       text          NOT NULL,
    qty        numeric(7, 1) NOT NULL,
    unit       text          NOT NULL,
    unit_price bigint        NOT NULL,
    subtotal   bigint        NOT NULL,
    CONSTRAINT order_items_qty_range CHECK (qty > 0 AND qty <= 999),
    CONSTRAINT order_items_price_positive CHECK (unit_price > 0 AND subtotal >= 0)
);
CREATE UNIQUE INDEX order_items_position_key ON order_items (order_id, position);

CREATE TABLE order_events (
    id          bigserial PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    branch_id   uuid        NOT NULL REFERENCES branches (id),
    type        text        NOT NULL,
    from_status text,
    to_status   text,
    staff_id    uuid        REFERENCES staff (id),
    device_id   uuid        REFERENCES devices (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    payload     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT order_events_type_valid CHECK (type IN ('CREATED', 'STATUS_CHANGED', 'WA_STATUS_CHANGED'))
);
CREATE INDEX order_events_order_idx ON order_events (order_id, id);

CREATE TABLE points_ledger (
    id            bigserial PRIMARY KEY,
    customer_id   uuid        NOT NULL REFERENCES customers (id),
    order_id      uuid        REFERENCES orders (id),
    type          text        NOT NULL,
    delta         bigint      NOT NULL,
    balance_after bigint      NOT NULL,
    staff_id      uuid        REFERENCES staff (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT points_ledger_type_valid CHECK (type IN ('EARN', 'REDEEM', 'IMPORT', 'ADJUST')),
    CONSTRAINT points_ledger_balance_non_negative CHECK (balance_after >= 0)
);
CREATE INDEX points_ledger_customer_idx ON points_ledger (customer_id, created_at DESC);

CREATE TABLE cash_entries (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shift_id   uuid        NOT NULL REFERENCES shifts (id),
    branch_id  uuid        NOT NULL REFERENCES branches (id),
    kind       text        NOT NULL,
    label      text        NOT NULL,
    note       text        NOT NULL DEFAULT '',
    amount     bigint      NOT NULL,
    order_id   uuid        REFERENCES orders (id),
    staff_id   uuid        NOT NULL REFERENCES staff (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT cash_entries_kind_valid CHECK (kind IN ('OPENING', 'CASH_IN', 'CASH_OUT', 'SALE')),
    -- amount bertanda: negatif = uang keluar
    CONSTRAINT cash_entries_sign CHECK (
        (kind = 'CASH_OUT' AND amount < 0) OR (kind <> 'CASH_OUT' AND amount > 0)
    )
);
CREATE INDEX cash_entries_shift_idx ON cash_entries (shift_id, created_at);

CREATE TABLE daily_sales (
    branch_id     uuid   NOT NULL REFERENCES branches (id),
    business_date date   NOT NULL,
    revenue       bigint NOT NULL DEFAULT 0,
    tx_count      int    NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (branch_id, business_date)
);

-- -----------------------------------------------------------------------------
-- Outbox WhatsApp
--
-- Tabelnya ada sejak awal karena transaksi bisnis menulis barisnya di dalam transaksi yang sama
-- (Invariant 3). Worker, Cloud API client, dan webhook baru dibangun di fase terakhir — sampai
-- saat itu baris tetap QUEUED, dan itu status yang jujur (T6: klien menandai TERKIRIM tanpa
-- pernah mengirim).
-- -----------------------------------------------------------------------------
CREATE TABLE wa_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id           uuid        NOT NULL REFERENCES branches (id),
    order_id            uuid        REFERENCES orders (id),
    customer_id         uuid        REFERENCES customers (id),
    template            text        NOT NULL,
    to_phone            varchar(15) NOT NULL,
    params              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status              text        NOT NULL DEFAULT 'QUEUED',
    attempts            int         NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    provider_message_id text,
    error_code          text,
    error_reason        text,
    resolution          text        NOT NULL DEFAULT 'NONE',
    resolved_by         uuid        REFERENCES staff (id),
    resolved_at         timestamptz,
    queued_at           timestamptz NOT NULL DEFAULT now(),
    sent_at             timestamptz,
    delivered_at        timestamptz,
    failed_at           timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT wa_messages_template_valid CHECK (
        template IN ('OPT_IN_CONFIRM', 'STRUK_DIGITAL', 'STATUS_SIAP_DIAMBIL', 'REMINDER_3_HARI')
    ),
    CONSTRAINT wa_messages_status_valid CHECK (
        status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT wa_messages_resolution_valid CHECK (resolution IN ('NONE', 'RESENT', 'MANUAL_CALL')),
    CONSTRAINT wa_messages_phone_e164 CHECK (to_phone ~ '^62[0-9]{8,13}$')
);
-- Query worker: ... WHERE status = 'QUEUED' AND next_attempt_at <= now() FOR UPDATE SKIP LOCKED
CREATE INDEX wa_messages_queue_idx ON wa_messages (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX wa_messages_branch_status_idx ON wa_messages (branch_id, status, queued_at DESC);
CREATE INDEX wa_messages_order_idx ON wa_messages (order_id);
CREATE TRIGGER wa_messages_set_updated_at BEFORE UPDATE ON wa_messages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -----------------------------------------------------------------------------
-- Audit (append-only), impor customer, idempotensi
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id          bigserial PRIMARY KEY,
    branch_id   uuid        REFERENCES branches (id),   -- NULL = berlaku semua cabang
    staff_id    uuid        REFERENCES staff (id),      -- T10: bukan hanya nama pelaku
    actor_name  text        NOT NULL,
    action_type varchar(40) NOT NULL,
    action      text        NOT NULL,
    entity_type text,
    entity_id   text,
    metadata    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    device_id   uuid        REFERENCES devices (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_branch_idx ON audit_log (branch_id, created_at DESC);
CREATE INDEX audit_log_action_type_idx ON audit_log (action_type, created_at DESC);
CREATE INDEX audit_log_staff_idx ON audit_log (staff_id, created_at DESC);

-- Append-only ditegakkan dua lapis: hak akses role aplikasi (lihat docs/ops) dan trigger ini,
-- supaya koneksi apa pun — termasuk superuser yang salah pakai — tidak bisa mengubah jejak audit.
CREATE OR REPLACE FUNCTION audit_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log bersifat append-only: % tidak diizinkan', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

-- loyalty_rates adalah riwayat: baris disisipkan, tidak pernah diubah atau dihapus (P0 #6).
CREATE OR REPLACE FUNCTION loyalty_rates_is_history() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'loyalty_rates adalah riwayat: % tidak diizinkan, sisipkan baris baru', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER loyalty_rates_no_update BEFORE UPDATE ON loyalty_rates
    FOR EACH ROW EXECUTE FUNCTION loyalty_rates_is_history();
CREATE TRIGGER loyalty_rates_no_delete BEFORE DELETE ON loyalty_rates
    FOR EACH ROW EXECUTE FUNCTION loyalty_rates_is_history();

CREATE TABLE customer_imports (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name    text        NOT NULL,
    size_bytes   bigint      NOT NULL,
    branch_id    uuid        NOT NULL REFERENCES branches (id),
    created_by   uuid        NOT NULL REFERENCES staff (id),
    status       text        NOT NULL DEFAULT 'PREVIEW',
    preview      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    summary      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    expires_at   timestamptz NOT NULL,
    committed_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_imports_status_valid CHECK (status IN ('PREVIEW', 'COMMITTED', 'EXPIRED'))
);
CREATE INDEX customer_imports_branch_idx ON customer_imports (branch_id, created_at DESC);

-- Retry jaringan atas mutasi: key sama + body sama → respons tersimpan; body berbeda → 409.
CREATE TABLE idempotency_keys (
    key          uuid PRIMARY KEY,
    device_id    uuid        NOT NULL REFERENCES devices (id),
    endpoint     text        NOT NULL,
    request_hash char(64)    NOT NULL,
    status_code  int         NOT NULL,
    response     jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
