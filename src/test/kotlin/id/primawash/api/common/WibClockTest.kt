package id.primawash.api.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class WibClockTest {
    @Test
    fun `should derive the business date in Jakarta, not UTC`() {
        // 29 Agustus 23.30 WIB = 16.30 UTC — masih hari yang sama di Jakarta.
        WibClock.businessDate(Instant.parse("2026-08-29T16:30:00Z")) shouldBe LocalDate.of(2026, 8, 29)
        // 29 Agustus 17.30 UTC = 30 Agustus 00.30 WIB — sudah hari berikutnya.
        WibClock.businessDate(Instant.parse("2026-08-29T17:30:00Z")) shouldBe LocalDate.of(2026, 8, 30)
    }

    @Test
    fun `should build the MMdd part of an order number from capturedAt`() {
        WibClock.orderNumberDatePart(Instant.parse("2026-08-29T04:24:00Z")) shouldBe "0829"
        // Transaksi offline yang di-sync besok tetap memakai hari transaksinya.
        WibClock.orderNumberDatePart(Instant.parse("2026-01-05T22:10:00Z")) shouldBe "0106"
    }

    @Test
    fun `should bound a business day with UTC instants`() {
        WibClock.startOfDay(LocalDate.of(2026, 8, 29)) shouldBe Instant.parse("2026-08-28T17:00:00Z")
        WibClock.endOfDay(LocalDate.of(2026, 8, 29)) shouldBe Instant.parse("2026-08-29T17:00:00Z")
    }
}
