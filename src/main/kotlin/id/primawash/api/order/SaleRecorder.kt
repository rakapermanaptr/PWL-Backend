package id.primawash.api.order

import id.primawash.api.branch.BranchRecord
import id.primawash.api.catalog.CatalogService
import id.primawash.api.catalog.LoyaltyRateRecord
import id.primawash.api.catalog.RewardRecord
import id.primawash.api.common.Money
import id.primawash.api.common.WibClock
import id.primawash.api.customer.CustomerRecord
import id.primawash.api.customer.CustomerService
import id.primawash.api.shift.ShiftRecord
import id.primawash.api.shift.ShiftSale
import id.primawash.api.shift.ShiftService
import id.primawash.api.wa.ReceiptParams
import id.primawash.api.wa.WaRecipient
import id.primawash.api.wa.WaService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A sale whose rules have all been checked; everything [SaleRecorder] needs to write it. */
data class SaleDraft(
    val branch: BranchRecord,
    val shift: ShiftRecord,
    val clientTxId: UUID,
    val source: OrderSource,
    val capturedAt: Instant,
    /** Locked by `CustomerService.lockForOrder`; null for a walk-in. */
    val customer: CustomerRecord?,
    val items: List<OrderItemRecord>,
    val note: String,
    val reward: RewardRecord?,
    val totals: OrderTotals,
    val rate: LoyaltyRateRecord,
    val payment: PaymentMethod,
    val staffId: UUID,
    val deviceId: UUID,
    val flags: List<OrderFlag>,
)

data class RecordedSale(
    val order: OrderRecord,
    /** The customer after points and the visit were applied; null for a walk-in. */
    val customer: CustomerRecord?,
    val whatsAppQueued: Boolean,
)

/**
 * The write half of a paid order — the server's `OrderRepository.recordSale` (PRD §8.6 effects 1–7 and
 * 9). Shared by `POST /orders` and `POST /orders/sync` so an online and an offline sale land in exactly
 * the same rows. Runs inside the caller's transaction; the caller writes the audit rows, whose wording
 * differs between the two paths.
 */
class SaleRecorder(
    private val repository: OrderRepository,
    private val customers: CustomerService,
    private val catalog: CatalogService,
    private val shifts: ShiftService,
    private val whatsApp: WaService,
    private val clock: Clock,
) {
    fun record(draft: SaleDraft): RecordedSale {
        val now = clock.instant()
        val businessDate = WibClock.businessDate(draft.capturedAt)
        // 1. Order number from the counter of the capture's business day, then the order and its items.
        val seq = repository.nextSequence(draft.branch.id, businessDate)
        val order = draft.toOrder(orderNumber(draft.branch.code, draft.capturedAt, seq), now)
        repository.insert(order, seq)

        // 2. Event layer.
        repository.insertEvent(
            orderId = order.id,
            branchId = order.branchId,
            type = EVENT_CREATED,
            fromStatus = null,
            toStatus = OrderStatus.DITERIMA.name,
            staffId = draft.staffId,
            deviceId = draft.deviceId,
            payload =
                buildJsonObject {
                    put("number", order.number)
                    put("total", order.total)
                    put("source", order.source.name)
                },
            at = now,
        )

        // 3. Points ledger, visit and cached balance. 4. Reward usage.
        val customer =
            draft.customer?.let {
                customers.applyPaidOrder(it, order.id, order.earnedPoints, order.redeemedPoints, draft.staffId)
            }
        draft.reward?.let { catalog.recordRewardUse(it.id) }

        // 5–6. Shift totals and, for cash, the SALE entry.
        shifts.recordSale(
            draft.shift,
            ShiftSale(
                orderId = order.id,
                orderNumber = order.number,
                cash = order.payment == PaymentMethod.TUNAI,
                total = order.total,
                earnedPoints = order.earnedPoints,
                customerName = order.customerName,
                staffId = draft.staffId,
            ),
        )

        // 7. Daily sales on the business date of capturedAt.
        repository.addDailySale(order.branchId, businessDate, order.total)

        // 9. WhatsApp outbox — QUEUED rows only, never a send (Invariant 3).
        val queued = customer?.let { queueWhatsApp(order, draft.branch.name, it) } ?: false
        return RecordedSale(order, customer, queued)
    }

    private fun queueWhatsApp(
        order: OrderRecord,
        branchName: String,
        customer: CustomerRecord,
    ): Boolean =
        whatsApp.queueForPaidOrder(
            branchId = order.branchId,
            orderId = order.id,
            recipient = WaRecipient(customer.id, customer.name, customer.phone, customer.optIn, customer.optInAt),
            receipt =
                ReceiptParams(
                    orderNumber = order.number,
                    branchName = branchName,
                    services = OrderMath.serviceSummary(order.items),
                    total = Money.formatRupiah(order.total),
                    payment = order.payment.label,
                    earnedPoints = Money.grouped(order.earnedPoints),
                    pointsBalance = Money.grouped(customer.points),
                ),
        )

    private fun SaleDraft.toOrder(
        number: String,
        now: Instant,
    ) = OrderRecord(
        id = UUID.randomUUID(),
        number = number,
        branchId = branch.id,
        businessDate = WibClock.businessDate(capturedAt),
        clientTxId = clientTxId,
        source = source,
        customerId = customer?.id,
        customerName = customer?.name ?: WALK_IN_NAME,
        customerPhone = customer?.phone ?: WALK_IN_PHONE,
        note = note,
        subtotal = totals.subtotal,
        discount = totals.discount,
        total = totals.total,
        rewardId = reward?.id,
        rewardName = reward?.name,
        redeemedPoints = reward?.cost ?: 0,
        earnedPoints = totals.earnedPoints,
        loyaltyRateId = rate.id,
        payment = payment,
        status = OrderStatus.DITERIMA,
        // §9.1: the consent of the customer right now, not a stamped value (T7).
        waStatus = if (customer?.optIn == true) WaStatus.MENUNGGU else WaStatus.BELUM_OPTIN,
        shiftId = shift.id,
        staffId = staffId,
        deviceId = deviceId,
        capturedAt = capturedAt,
        createdAt = now,
        statusChangedAt = capturedAt,
        flags = flags,
        version = 1,
        items = items,
    )

    companion object {
        const val WALK_IN_NAME = "Tanpa nama"
        const val WALK_IN_PHONE = "—"
        const val EVENT_CREATED = "CREATED"
        private const val SEQ_DIGITS = 3

        /** `{branch.code}-{MMdd}-{seq 3 digit}` → "TBT-0829-015" (PRD §7.3). */
        fun orderNumber(
            branchCode: String,
            capturedAt: Instant,
            seq: Int,
        ): String =
            "$branchCode-${WibClock.orderNumberDatePart(capturedAt)}-${seq.toString().padStart(SEQ_DIGITS, '0')}"
    }
}
