package id.primawash.api.common

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * Cursor pagination (PRD §6.1): `?limit=50&cursor=<opaque>` → `{ "items": [...], "nextCursor": ... }`.
 *
 * The cursor is opaque to the client; inside it is either an offset (lists ordered by a value that
 * changes, such as visit counts) or the last seen key (append-only lists such as the points ledger).
 */
object Pagination {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 100

    fun limit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        val value = raw.toIntOrNull()
        if (value == null || value !in 1..MAX_LIMIT) {
            throw ValidationException("Parameter limit harus angka 1–$MAX_LIMIT.")
        }
        return value
    }

    fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    fun decode(cursor: String?): String? =
        cursor?.let {
            runCatching { String(Base64.getUrlDecoder().decode(it)) }
                .getOrElse { throw ValidationException("Cursor tidak valid — muat ulang daftar dari awal.") }
        }

    fun decodeOffset(cursor: String?): Long =
        decode(cursor)?.let { value ->
            value.removePrefix(OFFSET_PREFIX).toLongOrNull()?.takeIf { value.startsWith(OFFSET_PREFIX) && it >= 0 }
                ?: throw ValidationException("Cursor tidak valid — muat ulang daftar dari awal.")
        } ?: 0

    fun encodeOffset(offset: Long): String = encode(OFFSET_PREFIX + offset)

    private const val OFFSET_PREFIX = "o:"
}

@Serializable
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)
