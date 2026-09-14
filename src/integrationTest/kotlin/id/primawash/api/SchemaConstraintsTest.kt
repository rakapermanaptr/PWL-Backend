package id.primawash.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

/**
 * The schema is the M0 deliverable that everything else leans on, so the invariants that CLAUDE.md
 * says must live in the database are asserted here against a real Postgres — not in code comments.
 */
class SchemaConstraintsTest : SchemaTestBase() {
    @Test
    fun `should allow only one open shift per branch`() {
        PostgresSupport.withConnection { connection ->
            openShift(connection)
            val failure = assertThrows<SQLException> { openShift(connection) }
            failure.message!! shouldContain "shifts_one_open_per_branch"
        }
    }

    @Test
    fun `should allow a new shift once the previous one is closed`() {
        PostgresSupport.withConnection { connection ->
            val first = openShift(connection)
            connection.createStatement().use {
                it.execute(
                    "UPDATE shifts SET closed_at = now(), closed_by_staff_id = '${fixtures.staffId}' " +
                        "WHERE id = '$first'",
                )
            }
            openShift(connection)
            count(connection, "SELECT count(*) FROM shifts") shouldBe 2
        }
    }

    @Test
    fun `should reject a second active staff account with the same pin`() {
        PostgresSupport.withConnection { connection ->
            val failure = assertThrows<SQLException> { insertStaff(connection, "Bagas Ardhana", active = true) }
            failure.message!! shouldContain "staff_pin_lookup_active_key"
        }
    }

    @Test
    fun `should allow a deactivated staff account to keep a pin that is in use`() {
        PostgresSupport.withConnection { connection ->
            insertStaff(connection, "Yuni Astari", active = false)
            count(connection, "SELECT count(*) FROM staff") shouldBe 2
        }
    }

    @Test
    fun `should reject a duplicate client transaction id`() {
        PostgresSupport.withConnection { connection ->
            val clientTxId = "44444444-4444-4444-4444-444444444444"
            val shiftId = openShift(connection)
            insertOrder(connection, shiftId, clientTxId, seq = 1, number = "TBT-0829-001")
            val failure =
                assertThrows<SQLException> {
                    insertOrder(connection, shiftId, clientTxId, seq = 2, number = "TBT-0829-002")
                }
            failure.message!! shouldContain "orders_client_tx_id_key"
        }
    }

    @Test
    fun `should reject two orders with the same sequence on the same business date`() {
        PostgresSupport.withConnection { connection ->
            val shiftId = openShift(connection)
            insertOrder(connection, shiftId, "55555555-5555-5555-5555-555555555555", seq = 1, number = "TBT-0829-001")
            val failure =
                assertThrows<SQLException> {
                    insertOrder(
                        connection,
                        shiftId,
                        "66666666-6666-6666-6666-666666666666",
                        seq = 1,
                        number = "TBT-0829-009",
                    )
                }
            failure.message!! shouldContain "orders_branch_date_seq_key"
        }
    }

    @Test
    fun `should reject an order whose total does not match subtotal minus discount`() {
        PostgresSupport.withConnection { connection ->
            val shiftId = openShift(connection)
            val failure =
                assertThrows<SQLException> {
                    insertOrder(
                        connection,
                        shiftId,
                        "77777777-7777-7777-7777-777777777777",
                        seq = 3,
                        number = "TBT-0829-003",
                        subtotal = 45_000,
                        discount = 10_000,
                        total = 45_000,
                    )
                }
            failure.message!! shouldContain "orders_amounts_consistent"
        }
    }

    @Test
    fun `should keep audit_log append-only`() {
        PostgresSupport.withConnection { connection ->
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO audit_log (branch_id, staff_id, actor_name, action_type, action) " +
                        "VALUES ('${fixtures.branchId}', '${fixtures.staffId}', 'Siti N. (kasir)', " +
                        "'SHIFT_OPENED', 'Buka shift · modal awal Rp500.000')",
                )
            }
            val update =
                assertThrows<SQLException> {
                    connection.createStatement().use { it.execute("UPDATE audit_log SET action = 'diubah'") }
                }
            update.message!! shouldContain "append-only"

            val delete =
                assertThrows<SQLException> {
                    connection.createStatement().use { it.execute("DELETE FROM audit_log") }
                }
            delete.message!! shouldContain "append-only"
        }
    }

    @Test
    fun `should keep loyalty rates immutable history`() {
        PostgresSupport.withConnection { connection ->
            val failure =
                assertThrows<SQLException> {
                    connection.createStatement().use { it.execute("UPDATE loyalty_rates SET points_per_step = 200") }
                }
            failure.message!! shouldContain "riwayat"
        }
    }

    @Test
    fun `should refuse a negative points balance`() {
        PostgresSupport.withConnection { connection ->
            val failure =
                assertThrows<SQLException> {
                    connection.createStatement().use {
                        it.execute(
                            "INSERT INTO customers (name, phone, phone_digits, points_balance, home_branch_id) " +
                                "VALUES ('Dewi Anggraini', '0812-3390-4471', '081233904471', -1, " +
                                "'${fixtures.branchId}')",
                        )
                    }
                }
            failure.message!! shouldContain "customers_points_non_negative"
        }
    }

    @Test
    fun `should require a cashier to belong to a branch and an owner not to`() {
        PostgresSupport.withConnection { connection ->
            val cashier =
                assertThrows<SQLException> {
                    connection.createStatement().use {
                        it.execute(
                            "INSERT INTO staff (name, short_name, role, pin_lookup, pin_hash) " +
                                "VALUES ('Kasir Tanpa Cabang', 'Kasir T.', 'KASIR', repeat('b', 64), 'x')",
                        )
                    }
                }
            cashier.message!! shouldContain "staff_branch_matches_role"
        }
    }
}
