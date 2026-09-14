package id.primawash.api.common

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Wire format for every instant in the API: ISO-8601 UTC with exactly three fraction digits,
 * `2026-08-29T04:24:00.000Z` (PRD §6.1). Never depends on the JVM default zone.
 */
object Timestamps {
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            ).withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMAT.format(instant)

    /** Postgres stores microseconds; the API speaks milliseconds, so values are truncated on write. */
    fun toDb(instant: Instant): OffsetDateTime = instant.truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC)

    fun fromDb(value: OffsetDateTime): Instant = value.toInstant()
}

fun Instant.toApi(): String = Timestamps.format(this)
