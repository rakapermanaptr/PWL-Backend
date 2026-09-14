package id.primawash.api

import org.junit.jupiter.api.BeforeEach
import java.sql.Connection

/**
 * Shared setup and raw-SQL row builders for the schema constraint suites. The suites talk to Postgres
 * directly — no repositories — so a failing test points at the constraint, not at application code.
 */
abstract class SchemaTestBase {
    protected lateinit var fixtures: PostgresSupport.Fixtures

    @BeforeEach
    fun resetDatabase() {
        PostgresSupport.truncateAll()
        fixtures = PostgresSupport.seedMinimal()
    }

    protected fun exec(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    protected fun queryId(
        connection: Connection,
        sql: String,
    ): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                it.next()
                it.getString(1)
            }
        }

    protected fun insertService(connection: Connection): String =
        queryId(
            connection,
            "INSERT INTO services (category, name, price, unit, step) " +
                "VALUES ('KILOAN_REGULER', 'Cuci Setrika', 10000, 'kg', 0.5) RETURNING id",
        )

    @Suppress("LongParameterList")
    protected fun insertItem(
        connection: Connection,
        orderId: String,
        serviceId: String,
        position: Int,
        qty: String,
        unit: String,
    ) = exec(
        connection,
        "INSERT INTO order_items (order_id, position, service_id, name, qty, unit, unit_price, subtotal) " +
            "VALUES ('$orderId', $position, '$serviceId', 'Cuci Setrika', $qty, '$unit', 10000, 45000)",
    )

    protected fun insertCustomer(connection: Connection): String =
        queryId(
            connection,
            "INSERT INTO customers (name, phone, phone_digits, home_branch_id) " +
                "VALUES ('Dewi Anggraini', '0812-3390-4471', '081233904471', '${fixtures.branchId}') RETURNING id",
        )

    protected fun insertLedger(
        connection: Connection,
        customerId: String,
        orderId: String?,
        type: String,
        delta: Long,
    ) = exec(
        connection,
        "INSERT INTO points_ledger (customer_id, order_id, type, delta, balance_after) " +
            "VALUES ('$customerId', ${orderId?.let { "'$it'" } ?: "NULL"}, '$type', $delta, 400)",
    )

    protected fun insertCashEntry(
        connection: Connection,
        shiftId: String,
        kind: String,
        orderId: String?,
    ) = exec(
        connection,
        "INSERT INTO cash_entries (shift_id, branch_id, kind, label, amount, order_id, staff_id) " +
            "VALUES ('$shiftId', '${fixtures.branchId}', '$kind', 'Penjualan tunai', 45000, " +
            "${orderId?.let { "'$it'" } ?: "NULL"}, '${fixtures.staffId}')",
    )

    protected fun insertDevice(
        connection: Connection,
        name: String,
    ): String =
        queryId(
            connection,
            "INSERT INTO devices (name, token_hash) VALUES ('$name', md5('$name') || md5('$name')) RETURNING id",
        )

    protected fun insertIdempotencyKey(
        connection: Connection,
        deviceId: String,
        key: String,
    ) = exec(
        connection,
        "INSERT INTO idempotency_keys (key, device_id, endpoint, request_hash, status_code, response) " +
            "VALUES ('$key', '$deviceId', 'POST /orders', repeat('c', 64), 201, '{}')",
    )

    protected fun openShift(connection: Connection): String =
        connection
            .prepareStatement(
                "INSERT INTO shifts (branch_id, opened_by_staff_id, opened_by_name, opening_cash, counted_cash) " +
                    "VALUES (?, ?, 'Siti N.', 500000, 500000) RETURNING id",
            ).use { statement ->
                statement.setObject(1, java.util.UUID.fromString(fixtures.branchId))
                statement.setObject(2, java.util.UUID.fromString(fixtures.staffId))
                statement.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }

    protected fun insertStaff(
        connection: Connection,
        name: String,
        active: Boolean,
    ) {
        connection.createStatement().use {
            it.execute(
                "INSERT INTO staff (name, short_name, role, branch_id, pin_lookup, pin_hash, active) " +
                    "VALUES ('$name', 'X.', 'KASIR', '${fixtures.branchId}', repeat('a', 64), 'x', $active)",
            )
        }
    }

    @Suppress("LongParameterList")
    protected fun insertOrder(
        connection: Connection,
        shiftId: String,
        clientTxId: String,
        seq: Int,
        number: String,
        subtotal: Long = 45_000,
        discount: Long = 0,
        total: Long = 45_000,
    ): String =
        connection.createStatement().use {
            it.execute(
                """
                INSERT INTO orders (number, branch_id, business_date, seq, client_tx_id, customer_name,
                                    subtotal, discount, total, loyalty_rate_id, payment, wa_status,
                                    shift_id, staff_id, captured_at)
                VALUES ('$number', '${fixtures.branchId}', DATE '2026-08-29', $seq, '$clientTxId',
                        'Tanpa nama', $subtotal, $discount, $total, '${fixtures.rateId}', 'TUNAI',
                        'BELUM_OPTIN', '$shiftId', '${fixtures.staffId}', timestamptz '2026-08-29T04:24:00Z')
                RETURNING id
                """.trimIndent(),
            )
            it.resultSet.use { rows ->
                rows.next()
                rows.getString(1)
            }
        }

    protected fun count(
        connection: Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                it.next()
                it.getInt(1)
            }
        }
}
