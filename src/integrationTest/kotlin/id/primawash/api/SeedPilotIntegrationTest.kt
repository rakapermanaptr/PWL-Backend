package id.primawash.api

import id.primawash.api.auth.PinHasher
import id.primawash.api.tools.seedPilot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

/** PRD Lampiran D — the pilot master data must land exactly once, however often the task runs. */
class SeedPilotIntegrationTest {
    private val hasher = PinHasher("pepper-untuk-test")

    @BeforeEach
    fun setUp() {
        PostgresSupport.truncateAll()
    }

    @Test
    fun `should seed pilot master data`() {
        PostgresSupport.withConnection { connection ->
            val summary = seedPilot(connection, hasher)

            summary.branches shouldBe 3
            summary.services shouldBe 16
            summary.rewards shouldBe 3
            summary.staff shouldBe 7
            summary.rates shouldBe 1

            count(connection, "SELECT count(*) FROM services WHERE NOT active") shouldBe 1
            count(connection, "SELECT count(*) FROM staff WHERE role = 'OWNER' AND branch_id IS NULL") shouldBe 1
            count(connection, "SELECT count(*) FROM rewards WHERE min_subtotal = 75000") shouldBe 1
            count(connection, "SELECT count(*) FROM services WHERE unit = 'kg' AND step = 0.5") shouldBe 8
        }
    }

    @Test
    fun `should be idempotent when run twice`() {
        PostgresSupport.withConnection { connection ->
            seedPilot(connection, hasher)
            val second = seedPilot(connection, hasher)

            second.branches shouldBe 0
            second.services shouldBe 0
            second.staff shouldBe 0
            count(connection, "SELECT count(*) FROM staff") shouldBe 7
        }
    }

    @Test
    fun `should store pins as argon2id with an hmac lookup, never in clear text`() {
        PostgresSupport.withConnection { connection ->
            seedPilot(connection, hasher)
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT pin_lookup, pin_hash FROM staff WHERE name = 'Siti Nurhaliza'",
                    ).use { rows ->
                        rows.next() shouldBe true
                        val lookup = rows.getString("pin_lookup")
                        val hash = rows.getString("pin_hash")
                        lookup shouldBe hasher.lookup("1234")
                        hash.startsWith("\$argon2id\$") shouldBe true
                        hasher.verify("1234", hash) shouldBe true
                        hash.contains("1234") shouldBe false
                    }
            }
        }
    }

    private fun count(
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
