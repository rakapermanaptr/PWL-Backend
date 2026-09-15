package id.primawash.api.order

import id.primawash.api.catalog.LoyaltyRateRecord
import id.primawash.api.common.Money
import java.math.BigDecimal
import kotlin.math.min

/** PRD Lampiran A `OrderStatus`; the label is what audit sentences show ("Siap diambil"). */
enum class OrderStatus(
    val label: String,
) {
    DITERIMA("Diterima"),
    PROSES("Diproses"),
    SIAP("Siap diambil"),
    SELESAI("Selesai"),
    ;

    /** Forward exactly one step; `null` once completed (§9.2 — no skipping, no going back). */
    val next: OrderStatus?
        get() = entries.getOrNull(ordinal + 1)
}

enum class PaymentMethod(
    val label: String,
) {
    TUNAI("Tunai"),
    TRANSFER("Transfer"),
}

/** Cached state of the "siap diambil" notification (PRD §7.2 `orders.wa_status`). */
enum class WaStatus {
    TERKIRIM,
    MENUNGGU,
    GAGAL,
    BELUM_OPTIN,
}

enum class OrderSource {
    ONLINE,
    OFFLINE_SYNC,
}

/**
 * Why an offline transaction was accepted but needs the owner's eye (PRD §11.2). The label is the
 * Indonesian phrase appended to its audit sentence.
 */
enum class OrderFlag(
    val label: String,
) {
    LATE_AFTER_SHIFT_CLOSE("masuk setelah shift ditutup"),
    PRICE_MISMATCH("harga beda dengan price list"),
    SERVICE_INACTIVE("layanan sudah nonaktif"),
    STALE_CAPTURE("transaksi lebih dari 7 hari"),
    CUSTOMER_PHONE_MATCHED("customer baru ternyata sudah terdaftar"),
}

data class OrderTotals(
    val subtotal: Long,
    val discount: Long,
    val total: Long,
    val earnedPoints: Long,
)

/**
 * Totals and points, rule for rule from `CalculateCartTotalsUseCase` and PRD §9.1 — but computed in
 * `BigDecimal`/`Long`, never in floating point:
 *
 * ```
 * item.subtotal = round_half_up(qty × unitPrice)
 * subtotal      = Σ item.subtotal
 * discount      = reward ? min(reward.value, subtotal) : 0
 * total         = subtotal − discount
 * earnedPoints  = customer ? floor(total / rate.rupiahPerStep) × rate.pointsPerStep : 0
 * ```
 */
object OrderMath {
    fun lineSubtotal(
        qty: BigDecimal,
        unitPrice: Long,
    ): Long = Money.lineTotal(qty, unitPrice)

    fun totals(
        lineSubtotals: List<Long>,
        rewardValue: Long?,
        rate: LoyaltyRateRecord,
        hasCustomer: Boolean,
    ): OrderTotals {
        val subtotal = lineSubtotals.sum()
        val discount = rewardValue?.let { min(it, subtotal) } ?: 0
        val total = subtotal - discount
        val earned = if (hasCustomer) pointsFor(total, rate) else 0
        return OrderTotals(subtotal, discount, total, earned)
    }

    fun pointsFor(
        total: Long,
        rate: LoyaltyRateRecord,
    ): Long = (total / rate.rupiahPerStep) * rate.pointsPerStep

    /** "Cuci Setrika 4,5 kg + Bed Cover 2 pcs" — the client's `Order.serviceSummary`, used in the receipt. */
    fun serviceSummary(items: List<OrderItemRecord>): String =
        items.joinToString(" + ") { "${it.name} ${qtyLabel(it.qty)} ${it.unit}" }

    /** "4,5" / "6" — client `Double.qtyLabel()`. */
    fun qtyLabel(qty: BigDecimal): String {
        val normalized = qty.stripTrailingZeros()
        if (normalized.scale() <= 0) return normalized.toBigInteger().toString()
        return normalized.toPlainString().replace('.', ',')
    }
}
