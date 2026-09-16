package id.primawash.api.report

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The pure arithmetic of the owner dashboard (PRD §9.4). Kept away from the database so every
 * ratio and the "top 4 + Lainnya" split can be tested on plain numbers.
 */
object ReportMath {
    /** Ratios are shares, not money: four decimals is far more precision than the UI shows. */
    private const val RATIO_SCALE = 4

    /** PRD §9.4 shows four services by name; everything else is folded into one "Lainnya" row. */
    const val TOP_SERVICE_COUNT = 4

    const val OTHER_SERVICES_LABEL = "Lainnya"

    /**
     * A share in `0..1`, or `null` when the denominator is 0 — "null bila 0" in PRD §9.4. A rate
     * with nothing to measure is unknown, never `0.0`: an empty branch must not read as 0% opt-in.
     */
    fun ratio(
        numerator: Long,
        denominator: Long,
    ): Double? {
        if (denominator <= 0L) return null
        return BigDecimal
            .valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_UP)
            .toDouble()
    }

    /**
     * Revenue per service name, biggest first: the top [TOP_SERVICE_COUNT] by revenue, then the
     * remainder as one [OTHER_SERVICES_LABEL] row. `share` is each row's part of the period's total
     * item revenue, so the shares of a full list add up to 1.
     *
     * Ties are broken by name so two services with identical revenue keep a stable order between
     * refreshes rather than swapping places in the owner's list.
     */
    fun topServices(revenueByName: Map<String, Long>): List<ServiceRevenue> {
        val ranked =
            revenueByName
                .filterValues { it > 0L }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        val total = ranked.sumOf { it.value }
        if (total <= 0L) return emptyList()

        val top = ranked.take(TOP_SERVICE_COUNT).map { ServiceRevenue(it.key, it.value, share(it.value, total)) }
        val rest = ranked.drop(TOP_SERVICE_COUNT).sumOf { it.value }
        return if (rest > 0L) top + ServiceRevenue(OTHER_SERVICES_LABEL, rest, share(rest, total)) else top
    }

    private fun share(
        value: Long,
        total: Long,
    ): Double = ratio(value, total) ?: 0.0
}

/** One row of `topServices[]` (PRD §9.4): revenue of a service name and its share of the period. */
data class ServiceRevenue(
    val name: String,
    val revenue: Long,
    val share: Double,
)
