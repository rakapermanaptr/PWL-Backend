package id.primawash.api.report

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReportMathTest {
    @Test
    fun `should report a rate as null when there is nothing to measure`() {
        ReportMath.ratio(0, 0) shouldBe null
    }

    @Test
    fun `should tell an empty population apart from a zero rate`() {
        ReportMath.ratio(0, 12) shouldBe 0.0
    }

    @Test
    fun `should round a share to four decimals half up`() {
        ReportMath.ratio(1, 3) shouldBe 0.3333
        ReportMath.ratio(2, 3) shouldBe 0.6667
    }

    @Test
    fun `should allow a ratio above one so a branch can beat its target`() {
        ReportMath.ratio(6_000_000, 5_000_000) shouldBe 1.2
    }

    @Test
    fun `should list the four biggest services and fold the rest into Lainnya`() {
        val revenue =
            mapOf(
                "Cuci Setrika" to 500_000L,
                "Cuci Kering" to 300_000L,
                "Setrika Saja" to 100_000L,
                "Bed Cover" to 60_000L,
                "Jas" to 30_000L,
                "Sepatu" to 10_000L,
            )

        val top = ReportMath.topServices(revenue)

        top.map { it.name } shouldBe
            listOf("Cuci Setrika", "Cuci Kering", "Setrika Saja", "Bed Cover", "Lainnya")
        top.last().revenue shouldBe 40_000
        top.sumOf { it.revenue } shouldBe 1_000_000
        top.map { it.share } shouldBe listOf(0.5, 0.3, 0.1, 0.06, 0.04)
    }

    @Test
    fun `should not add a Lainnya row when four services are all there is`() {
        val top = ReportMath.topServices(mapOf("A" to 4L, "B" to 3L, "C" to 2L, "D" to 1L))

        top.map { it.name } shouldBe listOf("A", "B", "C", "D")
    }

    @Test
    fun `should order services with equal revenue by name so the list stays stable`() {
        val top = ReportMath.topServices(mapOf("Sepatu" to 50L, "Bed Cover" to 50L))

        top.map { it.name } shouldBe listOf("Bed Cover", "Sepatu")
    }

    @Test
    fun `should drop services that sold nothing`() {
        ReportMath.topServices(mapOf("Cuci Setrika" to 0L)).shouldBeEmpty()
        ReportMath.topServices(emptyMap()).shouldBeEmpty()
    }
}
