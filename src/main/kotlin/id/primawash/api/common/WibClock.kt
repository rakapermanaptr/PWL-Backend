package id.primawash.api.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The business day is Asia/Jakarta, always derived from the transaction's `capturedAt` — never from
 * `now()` and never from the server's system timezone (CLAUDE.md "Data & Time Rules"). An offline
 * transaction synced tomorrow still belongs to the day it happened.
 */
object WibClock {
    val ZONE: ZoneId = ZoneId.of("Asia/Jakarta")

    fun businessDate(capturedAt: Instant): LocalDate = capturedAt.atZone(ZONE).toLocalDate()

    /** Inclusive start of a business day, as a UTC instant — for range queries on `captured_at`. */
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(ZONE).toInstant()

    /** Exclusive end of a business day. */
    fun endOfDay(date: LocalDate): Instant = startOfDay(date.plusDays(1))

    /** `MMdd` of the business day — the middle segment of an order number (PRD §7.3). */
    fun orderNumberDatePart(capturedAt: Instant): String =
        businessDate(capturedAt).let { "%02d%02d".format(it.monthValue, it.dayOfMonth) }

    fun nowUtc(): Instant = Instant.now().atOffset(ZoneOffset.UTC).toInstant()
}
