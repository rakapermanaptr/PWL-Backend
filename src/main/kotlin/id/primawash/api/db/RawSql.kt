package id.primawash.api.db

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet

/**
 * Plain SQL on the connection of the current Exposed transaction, for the few statements the DSL does
 * not express: `INSERT … ON CONFLICT … RETURNING` for order numbers, `xid8` watermarks for delta sync,
 * and the `UNION ALL` change feed. Never opens a connection of its own (CLAUDE.md Invariant 2).
 *
 * Parameters are bound with `setObject`, which the Postgres driver maps for `UUID`, `LocalDate`,
 * `OffsetDateTime`, `String` and numbers. SQL text is always a constant — values only ever travel as
 * parameters.
 */
object RawSql {
    fun <T> query(
        sql: String,
        vararg params: Any?,
        map: (ResultSet) -> T,
    ): List<T> =
        connection().prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(map(rows)) }
            }
        }

    fun <T> single(
        sql: String,
        vararg params: Any?,
        map: (ResultSet) -> T,
    ): T = query(sql, *params, map = map).single()

    private fun connection(): Connection = TransactionManager.current().connection.connection as Connection
}
