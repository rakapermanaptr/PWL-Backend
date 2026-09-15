package id.primawash.api.common

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Format-level parsing of request values. A malformed value is a `400 VALIDATION_ERROR` listing the
 * offending fields (PRD §6.2); business rules about a well-formed value belong to the Service.
 */
object Requests {
    private const val INVALID_FORMAT = "Data yang dikirim tidak lengkap atau formatnya salah."

    fun uuid(
        raw: String?,
        field: String,
    ): UUID = uuidOrNull(raw, field) ?: throw invalid(field)

    /** Null when absent; a present but malformed value is still a 400. */
    fun uuidOrNull(
        raw: String?,
        field: String,
    ): UUID? {
        if (raw.isNullOrBlank()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() }
            ?: throw invalid(field)
    }

    fun <T> required(
        value: T?,
        field: String,
    ): T = value ?: throw invalid(field)

    fun boolean(
        raw: String?,
        field: String,
    ): Boolean? =
        when (raw?.lowercase()) {
            null -> null
            "true" -> true
            "false" -> false
            else -> throw invalid(field)
        }

    /** A business date `YYYY-MM-DD`; null when absent. */
    fun date(
        raw: String?,
        field: String,
    ): LocalDate? =
        raw?.let {
            try {
                LocalDate.parse(it)
            } catch (error: DateTimeParseException) {
                throw ValidationException("Format tanggal harus YYYY-MM-DD.", invalid(field).details)
            }
        }

    /** An ISO-8601 instant such as `2026-08-29T03:12:00.000Z`. */
    fun instant(
        raw: String?,
        field: String,
    ): Instant =
        try {
            Instant.parse(required(raw, field))
        } catch (error: DateTimeParseException) {
            throw invalid(field)
        }

    /** A JSON number read as a decimal, so `4.5` never passes through floating point. */
    fun decimal(
        value: JsonPrimitive?,
        field: String,
    ): BigDecimal = value?.takeUnless { it.isString }?.content?.toBigDecimalOrNull() ?: throw invalid(field)

    inline fun <reified E : Enum<E>> enum(
        raw: String?,
        field: String,
    ): E = enumValues<E>().firstOrNull { it.name == raw } ?: throw invalid(field)

    fun invalid(vararg fields: String): ValidationException = invalid(fields.toList())

    fun invalid(fields: List<String>): ValidationException =
        ValidationException(
            INVALID_FORMAT,
            buildJsonObject {
                putJsonArray("fields") { fields.forEach { add(JsonPrimitive(it)) } }
            },
        )

    fun details(vararg pairs: Pair<String, String>) =
        buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }
}
