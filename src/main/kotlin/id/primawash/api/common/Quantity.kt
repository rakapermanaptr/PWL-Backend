package id.primawash.api.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Quantity rules (CLAUDE.md "Data & Time Rules"): `NUMERIC(7,1)`, a multiple of the service `step`
 * (0.5 for `kg`, 1.0 otherwise), greater than zero and at most 999.
 */
object Quantity {
    val MAX: BigDecimal = BigDecimal("999")
    val KG_STEP: BigDecimal = BigDecimal("0.5")
    val UNIT_STEP: BigDecimal = BigDecimal("1.0")

    fun stepFor(unit: String): BigDecimal = if (unit == "kg") KG_STEP else UNIT_STEP

    fun isValid(
        qty: BigDecimal,
        step: BigDecimal,
    ): Boolean {
        if (qty <= BigDecimal.ZERO || qty > MAX) return false
        if (qty.stripTrailingZeros().scale() > 1) return false
        return qty.remainder(step).compareTo(BigDecimal.ZERO) == 0
    }

    /** Normalises to the single decimal place the column stores, so `4` and `4.0` compare equal. */
    fun normalize(qty: BigDecimal): BigDecimal = qty.setScale(1, RoundingMode.UNNECESSARY)
}
