package id.primawash.api.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `should round half up when quantity has a half step`() {
        // 4,5 kg × Rp10.000 = Rp45.000 tepat
        Money.lineTotal(BigDecimal("4.5"), 10_000) shouldBe 45_000L
        // 2,5 × Rp7.999 = 19.997,5 → dibulatkan ke atas
        Money.lineTotal(BigDecimal("2.5"), 7_999) shouldBe 19_998L
    }

    @Test
    fun `should never lose rupiah to floating point`() {
        Money.lineTotal(BigDecimal("0.1"), 10_000) shouldBe 1_000L
        Money.lineTotal(BigDecimal("3.3"), 3_333) shouldBe 10_999L
    }

    @Test
    fun `should format rupiah the way the cashier reads it`() {
        Money.formatRupiah(75_000) shouldBe "Rp75.000"
        Money.formatRupiah(5_200_000) shouldBe "Rp5.200.000"
        Money.formatRupiah(0) shouldBe "Rp0"
    }
}
