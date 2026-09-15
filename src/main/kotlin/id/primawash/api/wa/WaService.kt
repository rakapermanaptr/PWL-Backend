package id.primawash.api.wa

import id.primawash.api.common.PhoneNumber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** PRD Lampiran A `WaTemplate`. */
enum class WaTemplate {
    OPT_IN_CONFIRM,
    STRUK_DIGITAL,
    STATUS_SIAP_DIAMBIL,
    REMINDER_3_HARI,
}

/** Who a message is for, read from the `customers` row at the moment the event happens (trap T7). */
data class WaRecipient(
    val customerId: UUID,
    val name: String,
    val phone: String,
    val optIn: Boolean,
    /** When the current consent was given; `null` for rows that predate consent timestamps. */
    val optInAt: Instant?,
)

/** Template variables of `struk_digital` (PRD §10.2), already formatted as the message shows them. */
data class ReceiptParams(
    val orderNumber: String,
    val branchName: String,
    val services: String,
    val total: String,
    val payment: String,
    val earnedPoints: String,
    val pointsBalance: String,
)

/**
 * The WhatsApp outbox writer (CLAUDE.md Invariant 3). Every function runs inside the caller's business
 * transaction and only inserts `QUEUED` rows: if the order or status change rolls back, so do its
 * messages. Nothing in this API process ever calls the Cloud API — the worker that drains the outbox is
 * the final milestone (M5), and until then `QUEUED` is the honest state (T6).
 *
 * A customer who is not opted in right now gets no row at all, and neither does a walk-in.
 */
class WaService(
    private val repository: WaRepository,
    private val clock: Clock,
) {
    /**
     * After a paid order (PRD §10.2): `OPT_IN_CONFIRM` once per consent — on the first order since the
     * customer's latest opt-in — then `STRUK_DIGITAL`. Returns whether anything was queued.
     *
     * The caller holds the customer row lock, so two orders for one customer cannot both decide that the
     * confirmation is still missing.
     */
    fun queueForPaidOrder(
        branchId: UUID,
        orderId: UUID,
        recipient: WaRecipient,
        receipt: ReceiptParams,
    ): Boolean {
        if (!recipient.optIn) return false
        val now = clock.instant()
        val consentSince = recipient.optInAt ?: Instant.EPOCH
        if (!repository.existsSince(recipient.customerId, WaTemplate.OPT_IN_CONFIRM, consentSince)) {
            queue(
                branchId,
                orderId,
                recipient,
                WaTemplate.OPT_IN_CONFIRM,
                buildJsonObject {
                    put("customerName", recipient.name)
                    put("stopKeyword", STOP_KEYWORD)
                },
                now,
            )
        }
        queue(
            branchId,
            orderId,
            recipient,
            WaTemplate.STRUK_DIGITAL,
            buildJsonObject {
                put("customerName", recipient.name)
                put("orderNumber", receipt.orderNumber)
                put("branchName", receipt.branchName)
                put("services", receipt.services)
                put("total", receipt.total)
                put("payment", receipt.payment)
                put("earnedPoints", receipt.earnedPoints)
                put("pointsBalance", receipt.pointsBalance)
            },
            now,
        )
        return true
    }

    /** Order moved to `SIAP` (PRD §8.6): `STATUS_SIAP_DIAMBIL` only when the customer is opted in now. */
    @Suppress("LongParameterList")
    fun queueReadyForPickup(
        branchId: UUID,
        orderId: UUID,
        recipient: WaRecipient,
        orderNumber: String,
        branchName: String,
        branchHours: String,
    ): Boolean {
        if (!recipient.optIn) return false
        queue(
            branchId,
            orderId,
            recipient,
            WaTemplate.STATUS_SIAP_DIAMBIL,
            buildJsonObject {
                put("customerName", recipient.name)
                put("orderNumber", orderNumber)
                put("branchName", branchName)
                put("branchHours", branchHours)
            },
            clock.instant(),
        )
        return true
    }

    @Suppress("LongParameterList")
    private fun queue(
        branchId: UUID,
        orderId: UUID,
        recipient: WaRecipient,
        template: WaTemplate,
        params: JsonObject,
        at: Instant,
    ) {
        repository.insertQueued(
            branchId = branchId,
            orderId = orderId,
            customerId = recipient.customerId,
            template = template,
            toPhone = PhoneNumber.toE164(recipient.phone),
            params = params,
            at = at,
        )
    }

    private companion object {
        const val STOP_KEYWORD = "STOP"
    }
}
