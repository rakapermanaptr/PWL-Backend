package id.primawash.api

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

/**
 * Constraints added by `V2__schema_review_hardening.sql`: defaults the service must decide, quantity
 * steps, orders that cannot be deleted, points and cash entries that cannot be doubled, and
 * idempotency keys scoped to their device.
 */
class SchemaHardeningTest : SchemaTestBase() {
    @Test
    fun `should require the service to decide wa status and counted cash explicitly`() {
        PostgresSupport.withConnection { connection ->
            val shift =
                assertThrows<SQLException> {
                    exec(
                        connection,
                        "INSERT INTO shifts (branch_id, opened_by_staff_id, opened_by_name, opening_cash) " +
                            "VALUES ('${fixtures.branchId}', '${fixtures.staffId}', 'Siti N.', 500000)",
                    )
                }
            shift.message!! shouldContain "counted_cash"

            val shiftId = openShift(connection)
            val order =
                assertThrows<SQLException> {
                    exec(
                        connection,
                        "INSERT INTO orders (number, branch_id, business_date, seq, client_tx_id, customer_name, " +
                            "subtotal, total, loyalty_rate_id, payment, shift_id, staff_id, captured_at) " +
                            "VALUES ('TBT-0829-001', '${fixtures.branchId}', DATE '2026-08-29', 1, " +
                            "gen_random_uuid(), 'Tanpa nama', 45000, 45000, '${fixtures.rateId}', 'TUNAI', " +
                            "'$shiftId', '${fixtures.staffId}', now())",
                    )
                }
            order.message!! shouldContain "wa_status"
        }
    }

    @Test
    fun `should reject a quantity that is not a multiple of the unit step`() {
        PostgresSupport.withConnection { connection ->
            val orderId = insertOrder(connection, openShift(connection), CLIENT_TX_ID, seq = 1, number = "TBT-0829-001")
            val serviceId = insertService(connection)
            insertItem(connection, orderId, serviceId, position = 1, qty = "4.5", unit = "kg")

            val kg =
                assertThrows<SQLException> {
                    insertItem(connection, orderId, serviceId, position = 2, qty = "4.3", unit = "kg")
                }
            kg.message!! shouldContain "order_items_qty_step"

            val pcs =
                assertThrows<SQLException> {
                    insertItem(connection, orderId, serviceId, position = 3, qty = "1.5", unit = "pcs")
                }
            pcs.message!! shouldContain "order_items_qty_step"
        }
    }

    @Test
    fun `should refuse to delete an order that has items`() {
        PostgresSupport.withConnection { connection ->
            val orderId = insertOrder(connection, openShift(connection), CLIENT_TX_ID, seq = 1, number = "TBT-0829-001")
            insertItem(connection, orderId, insertService(connection), position = 1, qty = "2.0", unit = "kg")

            val failure =
                assertThrows<SQLException> { exec(connection, "DELETE FROM orders WHERE id = '$orderId'") }
            failure.message!! shouldContain "order_items_order_id_fkey"
        }
    }

    @Test
    fun `should record earned points at most once per order and never as zero`() {
        PostgresSupport.withConnection { connection ->
            val orderId = insertOrder(connection, openShift(connection), CLIENT_TX_ID, seq = 1, number = "TBT-0829-001")
            val customerId = insertCustomer(connection)
            insertLedger(connection, customerId, orderId, type = "EARN", delta = 400)

            val twice =
                assertThrows<SQLException> {
                    insertLedger(connection, customerId, orderId, type = "EARN", delta = 400)
                }
            twice.message!! shouldContain "points_ledger_order_type_key"

            val zero =
                assertThrows<SQLException> {
                    insertLedger(connection, customerId, orderId = null, type = "EARN", delta = 0)
                }
            zero.message!! shouldContain "points_ledger_delta_sign"
        }
    }

    @Test
    fun `should keep one sale cash entry per cash order`() {
        PostgresSupport.withConnection { connection ->
            val shiftId = openShift(connection)
            val orderId = insertOrder(connection, shiftId, CLIENT_TX_ID, seq = 1, number = "TBT-0829-001")
            insertCashEntry(connection, shiftId, kind = "SALE", orderId = orderId)

            val twice =
                assertThrows<SQLException> { insertCashEntry(connection, shiftId, kind = "SALE", orderId = orderId) }
            twice.message!! shouldContain "cash_entries_sale_order_key"

            val orphan =
                assertThrows<SQLException> { insertCashEntry(connection, shiftId, kind = "SALE", orderId = null) }
            orphan.message!! shouldContain "cash_entries_sale_has_order"
        }
    }

    @Test
    fun `should scope idempotency keys to the device that created them`() {
        PostgresSupport.withConnection { connection ->
            val first = insertDevice(connection, "Tablet Tebet 1")
            val second = insertDevice(connection, "Tablet Tebet 2")
            insertIdempotencyKey(connection, first, IDEMPOTENCY_KEY)
            insertIdempotencyKey(connection, second, IDEMPOTENCY_KEY)

            val replay = assertThrows<SQLException> { insertIdempotencyKey(connection, first, IDEMPOTENCY_KEY) }
            replay.message!! shouldContain "idempotency_keys_pkey"
        }
    }

    private companion object {
        const val CLIENT_TX_ID = "88888888-8888-8888-8888-888888888888"
        const val IDEMPOTENCY_KEY = "99999999-9999-9999-9999-999999999999"
    }
}
