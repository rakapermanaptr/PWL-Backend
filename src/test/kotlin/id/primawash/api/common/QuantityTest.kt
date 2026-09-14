package id.primawash.api.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QuantityTest {
    @Test
    fun `should accept half steps only for kilogram services`() {
        Quantity.isValid(BigDecimal("4.5"), Quantity.stepFor("kg")) shouldBe true
        Quantity.isValid(BigDecimal("4.5"), Quantity.stepFor("pcs")) shouldBe false
        Quantity.isValid(BigDecimal("2"), Quantity.stepFor("pcs")) shouldBe true
    }

    @Test
    fun `should reject quantity outside the allowed range`() {
        Quantity.isValid(BigDecimal("0"), Quantity.KG_STEP) shouldBe false
        Quantity.isValid(BigDecimal("-1.5"), Quantity.KG_STEP) shouldBe false
        Quantity.isValid(BigDecimal("999"), Quantity.UNIT_STEP) shouldBe true
        Quantity.isValid(BigDecimal("999.5"), Quantity.KG_STEP) shouldBe false
    }

    @Test
    fun `should reject more than one decimal place`() {
        Quantity.isValid(BigDecimal("4.25"), Quantity.KG_STEP) shouldBe false
    }
}
