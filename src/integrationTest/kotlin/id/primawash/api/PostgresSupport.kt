package id.primawash.api

import com.zaxxer.hikari.HikariDataSource
import id.primawash.api.common.DatabaseConfig
import id.primawash.api.db.DatabaseFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection

/**
 * One Postgres 16 container for the whole integration suite. Never an in-memory database: partial
 * unique indexes, `FOR UPDATE SKIP LOCKED` and the append-only triggers are exactly what needs
 * testing (CLAUDE.md "Testing Conventions").
 */
object PostgresSupport {
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pwl")
            .withUsername("pwl")
            .withPassword("pwl")
            .also { it.start() }

    val dataSource: HikariDataSource by lazy {
        DatabaseFactory
            .dataSource(
                DatabaseConfig(
                    url = container.jdbcUrl,
                    user = container.username,
                    password = container.password,
                    maxPoolSize = 4,
                ),
            ).also { DatabaseFactory.migrate(it) }
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            block(connection)
        }

    /**
     * Wipes every table between tests. `TRUNCATE` does not fire the row-level triggers that keep
     * `audit_log` and `loyalty_rates` append-only, which is why those can be cleared here but never
     * by the application.
     */
    fun truncateAll() =
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    TRUNCATE audit_log, loyalty_rates, order_events, order_items, points_ledger,
                             cash_entries, wa_messages, idempotency_keys, customer_imports, daily_sales,
                             order_number_counters, orders, shifts, customers, sessions,
                             devices, service_price_history, services, rewards,
                             staff, branches
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
        }

    /** Master data the concurrency tests need: one branch, one cashier, one loyalty rate. */
    fun seedMinimal(): Fixtures =
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO branches (id, code, name, daily_target)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'FMU', 'Familia Urban', 5200000);

                    INSERT INTO staff (id, name, short_name, role, branch_id, pin_lookup, pin_hash)
                    VALUES ('22222222-2222-2222-2222-222222222222', 'Siti Nurhaliza', 'Siti N.', 'KASIR',
                            '11111111-1111-1111-1111-111111111111', repeat('a', 64), 'argon2-dummy');

                    INSERT INTO loyalty_rates (id, rupiah_per_step, points_per_step, effective_from)
                    VALUES ('33333333-3333-3333-3333-333333333333', 10000, 100, timestamptz 'epoch');
                    """.trimIndent(),
                )
            }
            Fixtures(
                branchId = "11111111-1111-1111-1111-111111111111",
                staffId = "22222222-2222-2222-2222-222222222222",
                rateId = "33333333-3333-3333-3333-333333333333",
            )
        }

    data class Fixtures(
        val branchId: String,
        val staffId: String,
        val rateId: String,
    )
}
