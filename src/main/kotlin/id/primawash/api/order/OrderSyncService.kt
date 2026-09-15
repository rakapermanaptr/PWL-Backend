package id.primawash.api.order

import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.branch.BranchRecord
import id.primawash.api.branch.BranchService
import id.primawash.api.catalog.CatalogService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.DomainException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.IdempotencyRequest
import id.primawash.api.common.IdempotencyStore
import id.primawash.api.common.Idempotent
import id.primawash.api.common.Money
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.Quantity
import id.primawash.api.common.StoredResponse
import id.primawash.api.customer.CustomerRecord
import id.primawash.api.customer.CustomerService
import id.primawash.api.db.TransactionRunner
import id.primawash.api.db.isUniqueViolation
import id.primawash.api.shift.ShiftRecord
import id.primawash.api.shift.ShiftService
import id.primawash.api.staff.StaffService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One offline item as the tablet captured it — its unit price is what the customer paid. */
data class SyncItem(
    val serviceId: UUID,
    val qty: BigDecimal,
    val unitPrice: Long,
)

data class NewOfflineCustomer(
    val id: UUID?,
    val name: String?,
    val phone: String?,
    val optIn: Boolean,
)

/**
 * One transaction of the offline queue. Format problems found while reading the request are carried in
 * [invalid] so they reject this transaction only, not the whole batch.
 */
data class SyncTransaction(
    val clientTxId: UUID?,
    val capturedAt: Instant?,
    val shiftId: UUID?,
    val staffId: UUID?,
    val customerId: UUID?,
    val newCustomer: NewOfflineCustomer?,
    val items: List<SyncItem>,
    val hasReward: Boolean,
    val payment: PaymentMethod?,
    val note: String?,
    val invalid: DomainException? = null,
)

enum class SyncStatus {
    CREATED,
    DUPLICATE,
    REJECTED,
}

data class SyncResult(
    val clientTxId: UUID?,
    val status: SyncStatus,
    val order: OrderRecord? = null,
    val error: DomainException? = null,
    val customerIdMapping: Pair<UUID, UUID>? = null,
)

data class SyncOutcome(
    val results: List<SyncResult>,
) {
    private val created get() = results.filter { it.status == SyncStatus.CREATED }.mapNotNull { it.order }

    val createdCount: Int get() = created.size
    val duplicateCount: Int get() = results.count { it.status == SyncStatus.DUPLICATE }
    val rejectedCount: Int get() = results.count { it.status == SyncStatus.REJECTED }
    val total: Long get() = created.sumOf { it.total }
    val points: Long get() = created.sumOf { it.earnedPoints }
}

/**
 * `POST /orders/sync` — the offline queue (PRD §11.2). These transactions were **already paid**: the rule
 * is to get them recorded, flag anything odd, and never lose money. It fixes trap T5 of the client, which
 * demanded an open shift at sync time, used the current loyalty rate and stopped at the first failure.
 *
 * Each transaction is its own database transaction: a rejection never stops the batch, and a crash
 * midway leaves the recorded ones recorded — the retry answers them `DUPLICATE` by `clientTxId`.
 */
@Suppress("LongParameterList")
class OrderSyncService(
    private val tx: TransactionRunner,
    private val repository: OrderRepository,
    private val sales: SaleRecorder,
    private val branches: BranchService,
    private val catalog: CatalogService,
    private val customers: CustomerService,
    private val shifts: ShiftService,
    private val staff: StaffService,
    private val audit: AuditWriter,
    private val idempotency: IdempotencyStore,
    private val clock: Clock,
) {
    /**
     * Processes [transactions] in the order given (the client sends them by `capturedAt`), then writes
     * one summary audit "Sync {n} transaksi offline Cabang {nama} · Rp… · ID {pertama}–{terakhir}" when
     * anything was created. A retried request with the same `Idempotency-Key` replays the first answer.
     */
    suspend fun sync(
        principal: StaffPrincipal,
        transactions: List<SyncTransaction>,
        request: IdempotencyRequest,
        snapshot: (SyncOutcome) -> StoredResponse,
    ): Idempotent<SyncOutcome> {
        tx { idempotency.find(request) }?.let { return Idempotent.Replayed(it) }
        val results = transactions.map { syncOne(principal, it) }
        val outcome = SyncOutcome(results)
        tx { writeSummaryAudit(principal, outcome) }
        try {
            tx { if (idempotency.find(request) == null) idempotency.save(request, snapshot(outcome)) }
        } catch (error: Exception) {
            // The same request, sent twice at once, stored its answer first; ours is equivalent.
            if (!isUniqueViolation(error, IDEMPOTENCY_KEY)) throw error
        }
        return Idempotent.Fresh(outcome)
    }

    private suspend fun syncOne(
        principal: StaffPrincipal,
        transaction: SyncTransaction,
    ): SyncResult {
        transaction.invalid?.let { return SyncResult(transaction.clientTxId, SyncStatus.REJECTED, error = it) }
        val clientTxId = requireNotNull(transaction.clientTxId)
        return try {
            tx { recordOne(principal, transaction, clientTxId) }
        } catch (error: DomainException) {
            SyncResult(clientTxId, SyncStatus.REJECTED, error = error)
        } catch (error: Exception) {
            // Two syncs of one queue raced: the other request recorded this transaction a moment ago.
            // A new customer's phone can race the same way; the retry finds the customer and proceeds.
            when {
                isUniqueViolation(error, CLIENT_TX_INDEX) ->
                    tx { repository.findByClientTxId(clientTxId) }
                        ?.let { SyncResult(clientTxId, SyncStatus.DUPLICATE, order = it) } ?: throw error
                isUniqueViolation(error, CustomerService.PHONE_INDEX) ->
                    try {
                        tx { recordOne(principal, transaction, clientTxId) }
                    } catch (retryError: DomainException) {
                        SyncResult(clientTxId, SyncStatus.REJECTED, error = retryError)
                    }
                else -> throw error
            }
        }
    }

    /**
     * Rules for one transaction, in order: already recorded → `DUPLICATE` · `capturedAt` more than 5 minutes
     * ahead of the server → `CAPTURED_IN_FUTURE` · a reward → `REDEEM_OFFLINE` · no items → `EMPTY_CART` · an
     * unknown service or an invalid quantity or price → `INVALID_ITEM` · an unknown `customerId` → 404 ·
     * an invalid new customer → `NAME_REQUIRED`/`PHONE_INVALID` · a branch that never had a shift →
     * `SHIFT_NOT_OPEN`. Everything else is accepted and, where odd, flagged and audited.
     */
    private fun recordOne(
        principal: StaffPrincipal,
        transaction: SyncTransaction,
        clientTxId: UUID,
    ): SyncResult {
        repository.findByClientTxId(clientTxId)?.let { return SyncResult(clientTxId, SyncStatus.DUPLICATE, order = it) }
        val capturedAt = requireNotNull(transaction.capturedAt)
        checkAcceptable(transaction, capturedAt)

        val flags = linkedSetOf<OrderFlag>()
        val items = snapshotItems(transaction.items, capturedAt, flags)
        val branch = branches.find(principal.branchId) ?: throw BranchService.branchNotFound()
        val actorStaff = transaction.staffId?.let { staff.find(it) }?.takeIf { it.canWorkAt(branch.id) }
        val actor =
            actorStaff?.let { AuditActor.staff(it.id, it.shortName, it.isOwner, principal.deviceId) }
                ?: principal.auditActor
        val (customer, mapping) = resolveCustomer(transaction, branch, actor, flags)
        val shift = shiftFor(branch, transaction.shiftId, flags)
        if (capturedAt.isBefore(clock.instant().minus(STALE_AFTER))) flags += OrderFlag.STALE_CAPTURE

        val rate = catalog.rateAt(capturedAt)
        val totals = OrderMath.totals(items.map { it.subtotal }, null, rate, hasCustomer = customer != null)
        val recorded =
            sales.record(
                SaleDraft(
                    branch = branch,
                    shift = shift,
                    clientTxId = clientTxId,
                    source = OrderSource.OFFLINE_SYNC,
                    capturedAt = capturedAt,
                    customer = customer,
                    items = items,
                    note = transaction.note?.trim().orEmpty(),
                    reward = null,
                    totals = totals,
                    rate = rate,
                    payment = requireNotNull(transaction.payment),
                    staffId = actorStaff?.id ?: principal.staffId,
                    deviceId = principal.deviceId,
                    flags = flags.toList(),
                ),
            )
        auditSynced(principal, actor, recorded.order, transaction.shiftId)
        return SyncResult(clientTxId, SyncStatus.CREATED, order = recorded.order, customerIdMapping = mapping)
    }

    /** The only reasons a paid transaction is sent back: a clock ahead of the server, a redemption, no items. */
    private fun checkAcceptable(
        transaction: SyncTransaction,
        capturedAt: Instant,
    ) {
        if (capturedAt.isAfter(clock.instant().plus(FUTURE_TOLERANCE))) {
            throw BusinessRuleException(
                ErrorCodes.CAPTURED_IN_FUTURE,
                "Waktu transaksi di tablet lebih maju dari jam server — cek jam tablet lalu sync ulang.",
            )
        }
        if (transaction.hasReward) {
            throw BusinessRuleException(
                ErrorCodes.REDEEM_OFFLINE,
                "Redeem poin butuh koneksi — batalkan redemption atau tunggu online.",
            )
        }
        if (transaction.items.isEmpty()) {
            throw BusinessRuleException(
                ErrorCodes.EMPTY_CART,
                OrderService.EMPTY_CART_MESSAGE,
            )
        }
    }

    /**
     * The tablet's unit prices are kept — the customer paid them. An unknown service or an impossible
     * quantity or price rejects; an inactive service or a price that differs from the list at [capturedAt]
     * only flags.
     */
    private fun snapshotItems(
        cart: List<SyncItem>,
        capturedAt: Instant,
        flags: MutableSet<OrderFlag>,
    ): List<OrderItemRecord> {
        val services = catalog.findServices(cart.map { it.serviceId })
        return cart.map { item ->
            val service = services[item.serviceId] ?: throw OrderService.unavailable(null)
            if (!Quantity.isValid(item.qty, service.step)) throw OrderService.invalidQuantity(service)
            if (item.unitPrice <= 0) {
                throw BusinessRuleException(ErrorCodes.INVALID_ITEM, "Harga ${service.name} tidak valid.")
            }
            if (!service.active) flags += OrderFlag.SERVICE_INACTIVE
            if (catalog.priceAt(service, capturedAt) != item.unitPrice) flags += OrderFlag.PRICE_MISMATCH
            val qty = Quantity.normalize(item.qty)
            OrderItemRecord(
                service.id,
                service.name,
                qty,
                service.unit,
                item.unitPrice,
                OrderMath.lineSubtotal(qty, item.unitPrice),
            )
        }
    }

    /** The existing customer, the one registered offline (upserted by phone), or a walk-in. */
    private fun resolveCustomer(
        transaction: SyncTransaction,
        branch: BranchRecord,
        actor: AuditActor,
        flags: MutableSet<OrderFlag>,
    ): Pair<CustomerRecord?, Pair<UUID, UUID>?> {
        transaction.customerId?.let { id ->
            return (customers.lockForOrder(id) ?: throw NotFoundException(OrderService.CUSTOMER_NOT_FOUND)) to null
        }
        val new = transaction.newCustomer ?: return null to null
        val resolved = customers.resolveOffline(new.id, new.name, new.phone, new.optIn, branch.id, actor)
        if (resolved.matchedByPhone) flags += OrderFlag.CUSTOMER_PHONE_MATCHED
        return resolved.customer to new.id?.let { it to resolved.customer.id }
    }

    /** Per transaction: the client's sentence, marked offline, with any flags spelled out for the owner. */
    private fun auditSynced(
        principal: StaffPrincipal,
        actor: AuditActor,
        order: OrderRecord,
        reportedShiftId: UUID?,
    ) {
        val flagged = if (order.flags.isEmpty()) "" else " · ditandai: " + order.flags.joinToString(", ") { it.label }
        audit.write(
            AuditEntry(
                branchId = order.branchId,
                actor = actor,
                type = AuditActionType.ORDER_SYNCED,
                action =
                    "Transaksi ${order.number} · ${Money.formatRupiah(order.total)} · ${order.payment.label} · " +
                        "offline$flagged",
                entityType = "order",
                entityId = order.id.toString(),
                metadata =
                    buildJsonObject {
                        OrderService.saleMetadata(order).forEach { (key, value) -> put(key, value) }
                        put("capturedAt", order.capturedAt.toString())
                        put("reportedShiftId", reportedShiftId?.toString())
                        put("syncedBy", principal.staffId.toString())
                    },
            ),
        )
    }

    /**
     * The shift a paid offline transaction lands in (PRD §11.2) — never a reason to reject it: its own
     * shift while still open; else the branch's open shift; else its own (closed) shift, or the branch's
     * latest when the tablet sent none, flagged `LATE_AFTER_SHIFT_CLOSE`. Only a branch that never had a
     * shift at all has nowhere to put the money.
     */
    private fun shiftFor(
        branch: BranchRecord,
        reportedShiftId: UUID?,
        flags: MutableSet<OrderFlag>,
    ): ShiftRecord {
        val reported = reportedShiftId?.let { shifts.lockShift(it) }?.takeIf { it.branchId == branch.id }
        if (reported?.open == true) return reported
        shifts.lockOpenShift(branch.id)?.let { return it }
        val closed =
            reported ?: shifts.lockLatestShift(branch.id)
                ?: throw BusinessRuleException(
                    ErrorCodes.SHIFT_NOT_OPEN,
                    "Shift Cabang ${branch.name} belum dibuka — buka shift dulu sebelum mencatat transaksi.",
                )
        flags += OrderFlag.LATE_AFTER_SHIFT_CLOSE
        return closed
    }

    private fun writeSummaryAudit(
        principal: StaffPrincipal,
        outcome: SyncOutcome,
    ) {
        val created = outcome.results.filter { it.status == SyncStatus.CREATED }.mapNotNull { it.order }
        if (created.isEmpty()) return
        audit.write(
            AuditEntry(
                branchId = principal.branchId,
                actor = principal.auditActor,
                type = AuditActionType.ORDER_SYNCED,
                action =
                    "Sync ${created.size} transaksi offline Cabang ${principal.branchName} · " +
                        "${Money.formatRupiah(outcome.total)} · ID ${created.first().number}–${created.last().number}",
                entityType = "order_sync",
                entityId = created.first().id.toString(),
                metadata =
                    buildJsonObject {
                        put("created", outcome.createdCount)
                        put("duplicates", outcome.duplicateCount)
                        put("rejected", outcome.rejectedCount)
                        put("total", outcome.total)
                    },
            ),
        )
    }

    companion object {
        const val MAX_TRANSACTIONS = 50
        private const val CLIENT_TX_INDEX = "orders_client_tx_id_key"
        private const val IDEMPOTENCY_KEY = "idempotency_keys_pkey"
        private val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)
        private val STALE_AFTER: Duration = Duration.ofDays(7)
    }
}
