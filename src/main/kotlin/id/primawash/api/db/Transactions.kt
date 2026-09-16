package id.primawash.api.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.sql.Connection

/**
 * The transaction boundary of a `Service` (CLAUDE.md Invariant 2: one action = one database
 * transaction). Everything written inside [invoke] — the change, its audit rows, and later its
 * outbox rows — commits or rolls back together, on one connection.
 *
 * An interface so Service unit tests can run business rules without a database.
 */
interface TransactionRunner {
    suspend operator fun <T> invoke(block: () -> T): T

    /**
     * A read-only transaction whose queries all see the database as of its first query
     * (`REPEATABLE READ`), for reports built from many queries that must agree with each other.
     */
    suspend fun <T> snapshot(block: () -> T): T = invoke(block)
}

class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override suspend fun <T> invoke(block: () -> T): T =
        withContext(Dispatchers.IO) { transaction(database) { block() } }

    override suspend fun <T> snapshot(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database, Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) { block() }
        }
}

private const val UNIQUE_VIOLATION = "23505"

/**
 * True when [error] is Postgres rejecting a row because of the unique index/constraint [constraint].
 * Races that the code checks optimistically (two owners adding the same PIN, two cashiers registering
 * one phone number) end here, and the Service turns them into the same 409/422 as the pre-check.
 */
fun isUniqueViolation(
    error: Throwable,
    constraint: String,
): Boolean {
    val psql =
        generateSequence(error) { it.cause }
            .firstOrNull { it is PSQLException } as? PSQLException
            ?: return false
    return psql.sqlState == UNIQUE_VIOLATION &&
        psql.serverErrorMessage?.constraint == constraint
}

/**
 * Runs [block] and turns a violation of the unique index [constraint] into [error] — the same answer
 * the Service's pre-check gives, for the race where two requests pass the check together.
 */
inline fun <T> translatingUniqueViolation(
    constraint: String,
    error: () -> Exception,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: Exception) {
        throw if (isUniqueViolation(failure, constraint)) error() else failure
    }
