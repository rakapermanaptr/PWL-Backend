package id.primawash.api.order

import id.primawash.api.support.TxFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderMathTest {
    private val rate = TxFixtures.RATE

    @Test
    fun `should compute the pilot receipt of acceptance scenario 1`() {
        // Cuci Setrika 4,5 kg × Rp10.000, redeem "Gratis Cuci Kering 2 kg" (Rp14.000).
        val line = OrderMath.lineSubtotal(BigDecimal("4.5"), 10_000)
        val totals = OrderMath.totals(listOf(line), rewardValue = 14_000, rate = rate, hasCustomer = true)

        totals shouldBe OrderTotals(subtotal = 45_000, discount = 14_000, total = 31_000, earnedPoints = 300)
    }

    @Test
    fun `should never discount more than the subtotal`() {
        val totals = OrderMath.totals(listOf(10_000), rewardValue = 14_000, rate = rate, hasCustomer = true)

        totals.discount shouldBe 10_000
        totals.total shouldBe 0
        totals.earnedPoints shouldBe 0
    }

    @Test
    fun `should earn points on whole rate steps of the total, only for a customer`() {
        OrderMath.pointsFor(19_999, rate) shouldBe 100
        OrderMath.pointsFor(9_999, rate) shouldBe 0
        OrderMath.totals(listOf(45_000), null, rate, hasCustomer = false).earnedPoints shouldBe 0
    }

    @Test
    fun `should round each line half up in rupiah`() {
        OrderMath.lineSubtotal(BigDecimal("2.5"), 7_999) shouldBe 19_998
    }

    @Test
    fun `should describe the items the way the receipt shows them`() {
        val items =
            listOf(
                OrderItemRecord(TxFixtures.CUCI_SETRIKA.id, "Cuci Setrika", BigDecimal("4.5"), "kg", 10_000, 45_000),
                OrderItemRecord(TxFixtures.BED_COVER.id, "Bed Cover", BigDecimal("2.0"), "pcs", 35_000, 70_000),
            )

        OrderMath.serviceSummary(items) shouldBe "Cuci Setrika 4,5 kg + Bed Cover 2 pcs"
    }

    @Test
    fun `should move status forward one step and stop at SELESAI`() {
        OrderStatus.DITERIMA.next shouldBe OrderStatus.PROSES
        OrderStatus.PROSES.next shouldBe OrderStatus.SIAP
        OrderStatus.SIAP.next shouldBe OrderStatus.SELESAI
        OrderStatus.SELESAI.next shouldBe null
    }
}
