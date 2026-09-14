package id.primawash.api.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Rupiah is always a `Long` (BIGINT in Postgres). No floats, no decimals — anywhere (CLAUDE.md).
 */
object Money {
    private val rupiahFormat: DecimalFormat
        get() =
            DecimalFormat(
                "#,###",
                DecimalFormatSymbols(Locale.forLanguageTag("id-ID")).apply {
                    groupingSeparator = '.'
                },
            )

    /**
     * Line total = `round_half_up(qty × unitPrice)`, computed in `BigDecimal` (CLAUDE.md "Rounding").
     * Quantity carries one decimal (0.5 kg steps), so the product is rounded, never truncated.
     */
    fun lineTotal(
        qty: BigDecimal,
        unitPrice: Long,
    ): Long =
        qty
            .multiply(BigDecimal.valueOf(unitPrice))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

    /** "Rp75.000" — the exact shape used inside Indonesian error messages and WhatsApp templates. */
    fun formatRupiah(amount: Long): String = "Rp" + rupiahFormat.format(amount)
}
