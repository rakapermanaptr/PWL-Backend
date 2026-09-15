package id.primawash.api.common

import id.primawash.api.db.IdempotencyKeysTable
import id.primawash.api.db.TransactionRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** One mutation's `Idempotency-Key`, scoped to the tablet that sent it (PRD §6.1). */
data class IdempotencyRequest(
    val key: UUID,
    val deviceId: UUID,
    /** `POST /orders`, `POST /shifts/{id}/close`, … — a key reused on another endpoint is a mismatch. */
    val endpoint: String,
    /** SHA-256 of the normalised request body. */
    val requestHash: String,
) {
    companion object {
        /** Whitespace and formatting never make two identical bodies differ. */
        fun hashOf(body: JsonElement): String =
            SecureTokens.sha256Hex(Json.encodeToString(JsonElement.serializer(), body))
    }
}

/** The response as replayed for a retried request: status and JSON body, never a secret. */
data class StoredResponse(
    val status: Int,
    val body: JsonElement,
)

/** Either the action ran now, or it had already run and its stored response is replayed. */
sealed interface Idempotent<out T> {
    data class Fresh<T>(
        val value: T,
    ) : Idempotent<T>

    data class Replayed(
        val response: StoredResponse,
    ) : Idempotent<Nothing>
}

/**
 * Network retries of a mutation (CLAUDE.md "Concurrency Rules"): the same key with the same body
 * returns the stored response; the same key with a different body is `409 IDEMPOTENCY_MISMATCH`.
 * Keys are kept for [RETENTION]; an older key counts as unused.
 *
 * The stored row is written inside the action's own transaction, so an action and the record that it
 * happened commit together. Two requests racing with one key both run the action, but only one can
 * insert the key row — the other rolls back entirely and replays the winner's response.
 *
 * A stored response must never contain a secret (V2 note): callers pass a [snapshot] without tokens.
 */
class IdempotencyStore(
    private val clock: Clock,
) {
    suspend fun <T> execute(
        tx: TransactionRunner,
        request: IdempotencyRequest,
        snapshot: (T) -> StoredResponse,
        action: () -> T,
    ): Idempotent<T> =
        try {
            tx {
                find(request)?.let { return@tx Idempotent.Replayed(it) }
                val value = action()
                save(request, snapshot(value))
                Idempotent.Fresh(value)
            }
        } catch (error: Exception) {
            // A retry that raced the original with the same key loses somewhere — on the key row, on a
            // unique index the action writes (`orders.client_tx_id`), or on a rule the original's
            // commit now breaks (`SHIFT_ALREADY_OPEN`). Once the original's response exists, that
            // response is the answer; otherwise the error stands.
            val stored = runCatching { tx { find(request) } }.getOrNull() ?: throw error
            Idempotent.Replayed(stored)
        }

    /** The stored response for [request], inside the caller's transaction; null when the key is unused. */
    fun find(request: IdempotencyRequest): StoredResponse? {
        val row =
            IdempotencyKeysTable
                .selectAll()
                .where {
                    (IdempotencyKeysTable.deviceId eq request.deviceId) and (IdempotencyKeysTable.key eq request.key)
                }.firstOrNull() ?: return null
        if (Timestamps.fromDb(row[IdempotencyKeysTable.createdAt]).isBefore(cutoff())) return null
        if (row[IdempotencyKeysTable.endpoint] != request.endpoint ||
            row[IdempotencyKeysTable.requestHash] != request.requestHash
        ) {
            throw ConflictException(ErrorCodes.IDEMPOTENCY_MISMATCH, MISMATCH_MESSAGE)
        }
        return StoredResponse(
            row[IdempotencyKeysTable.statusCode],
            Json.parseToJsonElement(row[IdempotencyKeysTable.response]),
        )
    }

    /** Records [response] for [request], inside the caller's transaction. An expired row is replaced. */
    fun save(
        request: IdempotencyRequest,
        response: StoredResponse,
    ) {
        IdempotencyKeysTable.deleteWhere {
            (IdempotencyKeysTable.deviceId eq request.deviceId) and
                (IdempotencyKeysTable.key eq request.key) and
                (IdempotencyKeysTable.createdAt less Timestamps.toDb(cutoff()))
        }
        IdempotencyKeysTable.insert {
            it[key] = request.key
            it[deviceId] = request.deviceId
            it[endpoint] = request.endpoint
            it[requestHash] = request.requestHash
            it[statusCode] = response.status
            it[IdempotencyKeysTable.response] = response.body.toString()
            it[createdAt] = Timestamps.toDb(clock.instant())
        }
    }

    private fun cutoff() = clock.instant().minus(RETENTION)

    companion object {
        val RETENTION: Duration = Duration.ofDays(7)
        const val MISMATCH_MESSAGE =
            "Permintaan ini memakai Idempotency-Key yang sudah dipakai untuk data lain — " +
                "buat kunci baru lalu kirim ulang."
    }
}
