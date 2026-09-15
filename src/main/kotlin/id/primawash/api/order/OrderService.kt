package id.primawash.api.order

import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.branch.BranchService
import id.primawash.api.catalog.CatalogService
import id.primawash.api.catalog.RewardRecord
import id.primawash.api.catalog.ServiceRecord
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.IdempotencyRequest
import id.primawash.api.common.IdempotencyStore
import id.primawash.api.common.Idempotent
import id.primawash.api.common.Money
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.Pagination
import id.primawash.api.common.Quantity
import id.primawash.api.common.StoredResponse
import id.primawash.api.common.ValidationException
import id.primawash.api.common.WibClock
import id.primawash.api.customer.CustomerRecord
import id.primawash.api.customer.CustomerService
import id.primawash.api.db.TransactionRunner
import id.primawash.api.db.isUniqueViolation
import id.primawash.api.shift.ShiftService
import id.primawash.api.wa.WaRecipient
import id.primawash.api.wa.WaService
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CartItem(
    val serviceId: UUID,
    val qty: BigDecimal,
    val unitPrice: Long,
)

data class PlaceOrderCommand(
    val clientTxId: UUID,
    val customerId: UUID?,
    val items: List<CartItem>,
    val rewardId: UUID?,
    val payment: PaymentMethod,
    val note: String?,
    val expectedTotal: Long,
)

data class PlacedOrder(
    val order: OrderRecord,
    val customer: CustomerRecord?,
)

enum class AdvanceOutcome {
    ADVANCED,
    ALREADY_COMPLETED,
}

data class AdvancedOrder(
    val outcome: AdvanceOutcome,
    val order: OrderRecord,
    val notificationQueued: Boolean,
)

data class OrderPage(
    val items: List<OrderRecord>,
    val nextCursor: String?,
    val counts: Map<OrderStatus, Long>,
)

data class OrderDetail(
    val order: OrderRecord,
    val events: List<OrderEventRecord>,
)

data class NextNumber(
    val number: String,
    val businessDate: LocalDate,
)

/**
 * Orders (PRD §8.6). The server is authoritative (CLAUDE.md Invariant 1): the client's prices and total
 * are assertions to verify, never values to store, and every total and point is recomputed here.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class OrderService(
    private val tx: TransactionRunner,
    private val repository: OrderRepository,
    private val sales: SaleRecorder,
    private val branches: BranchService,
    private val catalog: CatalogService,
    private val customers: CustomerService,
    private val shifts: ShiftService,
    private val whatsApp: WaService,
    private val audit: AuditWriter,
    private val idempotency: IdempotencyStore,
    private val clock: Clock,
) {
    /**
     * `POST /orders` — "Simpan & Bayar" online (PRD §8.6, `PlaceOrderUseCase`). Rules in the documented
     * order; the first that fails is the cashier's message:
     *
     * 1. token branch exists (404) → 2. an open shift (`SHIFT_NOT_OPEN`) → 3. items not empty
     * (`EMPTY_CART`) → 4. every service exists and is active, every quantity > 0, a multiple of the
     * step and ≤ 999 (`INVALID_ITEM`) → 5. every `unitPrice` equals the price list (`409 PRICE_CHANGED`
     * with the current prices) → 6. reward active, customer chosen with enough points, subtotal ≥
     * `minSubtotal` (`INSUFFICIENT_POINTS` / `REWARD_NOT_ELIGIBLE`) → 7. `expectedTotal` equals the
     * server total (`409 TOTAL_MISMATCH`).
     *
     * A `clientTxId` that already has an order is `409 DUPLICATE_TRANSACTION`, never a second order.
     * Effects, all in one transaction: see [SaleRecorder], plus audit `ORDER_CREATED` "Transaksi {nomor} ·
     * Rp… · Tunai[ · redeem n poin]" and, when a reward is used, `REWARD_REDEEMED`.
     */
    suspend fun place(
        principal: StaffPrincipal,
        command: PlaceOrderCommand,
        request: IdempotencyRequest,
        snapshot: (PlacedOrder) -> StoredResponse,
    ): Idempotent<PlacedOrder> =
        try {
            idempotency.execute(tx, request, snapshot) { placeInTx(principal, command) }
        } catch (error: Exception) {
            if (!isUniqueViolation(error, CLIENT_TX_INDEX)) throw error
            throw duplicate(tx { repository.findByClientTxId(command.clientTxId) } ?: throw error)
        }

    private fun placeInTx(
        principal: StaffPrincipal,
        command: PlaceOrderCommand,
    ): PlacedOrder {
        val branch = branches.find(principal.branchId) ?: throw BranchService.branchNotFound()
        repository.findByClientTxId(command.clientTxId)?.let { throw duplicate(it) }
        val shift = shifts.lockOpenShift(branch.id) ?: throw shiftNotOpen(branch.name)
        val items = priceCart(command.items)
        val customer =
            command.customerId?.let { customers.lockForOrder(it) ?: throw NotFoundException(CUSTOMER_NOT_FOUND) }
        val reward = command.rewardId?.let { eligibleReward(it, customer, items.sumOf { item -> item.subtotal }) }

        val capturedAt = clock.instant()
        val rate = catalog.rateAt(capturedAt)
        val totals = OrderMath.totals(items.map { it.subtotal }, reward?.value, rate, hasCustomer = customer != null)
        if (command.expectedTotal != totals.total) throw totalMismatch(totals)

        val recorded =
            sales.record(
                SaleDraft(
                    branch = branch,
                    shift = shift,
                    clientTxId = command.clientTxId,
                    source = OrderSource.ONLINE,
                    capturedAt = capturedAt,
                    customer = customer,
                    items = items,
                    note = command.note?.trim().orEmpty(),
                    reward = reward,
                    totals = totals,
                    rate = rate,
                    payment = command.payment,
                    staffId = principal.staffId,
                    deviceId = principal.deviceId,
                    flags = emptyList(),
                ),
            )
        auditPlaced(principal, recorded.order, reward)
        return PlacedOrder(recorded.order, recorded.customer)
    }

    /**
     * Rules 3–5 of `POST /orders`: items present, every service available with a valid quantity, then
     * every asserted unit price equal to the price list. Returns the item snapshots with server subtotals.
     */
    private fun priceCart(cart: List<CartItem>): List<OrderItemRecord> {
        if (cart.isEmpty()) throw BusinessRuleException(ErrorCodes.EMPTY_CART, EMPTY_CART_MESSAGE)
        val services = catalog.findServices(cart.map { it.serviceId })
        cart.forEach { item ->
            val service = services[item.serviceId]?.takeIf { it.active } ?: throw unavailable(services[item.serviceId])
            if (!Quantity.isValid(item.qty, service.step)) throw invalidQuantity(service)
        }
        cart.firstOrNull { services.getValue(it.serviceId).price != it.unitPrice }?.let { repriced ->
            throw priceChanged(services.getValue(repriced.serviceId), cart.map { services.getValue(it.serviceId) })
        }
        return cart.map { item ->
            val service = services.getValue(item.serviceId)
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

    /** Rule 6: the reward is active, a customer with enough points is chosen, and the minimum spend is met (T9). */
    private fun eligibleReward(
        rewardId: UUID,
        customer: CustomerRecord?,
        subtotal: Long,
    ): RewardRecord {
        val reward =
            catalog.findRewards(listOf(rewardId)).firstOrNull()?.takeIf { it.active }
                ?: throw BusinessRuleException(ErrorCodes.REWARD_NOT_ELIGIBLE, "Reward ini sudah tidak tersedia.")
        if (customer == null || customer.points < reward.cost) {
            throw BusinessRuleException(ErrorCodes.INSUFFICIENT_POINTS, "Saldo poin belum cukup untuk reward ini.")
        }
        reward.minSubtotal?.takeIf { subtotal < it }?.let {
            throw BusinessRuleException(
                ErrorCodes.REWARD_NOT_ELIGIBLE,
                "Reward ini butuh minimum belanja ${Money.formatRupiah(it)}.",
            )
        }
        return reward
    }

    /** Effect 8: `ORDER_CREATED` in the client's wording, and `REWARD_REDEEMED` for the redemption report. */
    private fun auditPlaced(
        principal: StaffPrincipal,
        order: OrderRecord,
        reward: RewardRecord?,
    ) {
        val redeem = reward?.let { " · redeem ${Money.grouped(it.cost)} poin" }.orEmpty()
        audit.write(
            AuditEntry(
                branchId = order.branchId,
                actor = principal.auditActor,
                type = AuditActionType.ORDER_CREATED,
                action = "Transaksi ${order.number} · ${Money.formatRupiah(
                    order.total,
                )} · ${order.payment.label}$redeem",
                entityType = ENTITY,
                entityId = order.id.toString(),
                metadata = saleMetadata(order),
            ),
        )
        if (reward == null) return
        audit.write(
            AuditEntry(
                branchId = order.branchId,
                actor = principal.auditActor,
                type = AuditActionType.REWARD_REDEEMED,
                action = "Redeem ${reward.name} · ${Money.grouped(
                    reward.cost,
                )} poin · ${order.customerName} · ${order.number}",
                entityType = ENTITY,
                entityId = order.id.toString(),
                metadata =
                    buildJsonObject {
                        put("rewardId", reward.id.toString())
                        put("cost", reward.cost)
                        put("discount", order.discount)
                        put("customerId", order.customerId.toString())
                    },
            ),
        )
    }

    /**
     * `POST /orders/{id}/advance` (PRD §8.6, `AdvanceOrderStatusUseCase`). Order: exists (404) → a
     * cashier may only touch their own branch (`403 BRANCH_SCOPE`) → the status is still [fromStatus]
     * (`409 STATUS_CHANGED` with the current order — another tablet moved it) → `SELESAI` answers
     * `ALREADY_COMPLETED`. Otherwise exactly one step forward.
     *
     * Effects in one transaction: status, `status_changed_at`, `version + 1`, a `STATUS_CHANGED` event
     * with staff and tablet. On reaching `SIAP` the customer's consent is read now (T7): opted in →
     * `STATUS_SIAP_DIAMBIL` queued and `wa_status = MENUNGGU`; otherwise `BELUM_OPTIN`. A changed
     * `wa_status` writes a `WA_STATUS_CHANGED` event. Audit `ORDER_STATUS_CHANGED` "Ubah status {nomor} →
     * {label}[ · WA dijadwalkan]" — scheduled, never "terkirim" (T6).
     */
    suspend fun advance(
        principal: StaffPrincipal,
        orderId: UUID,
        fromStatus: OrderStatus,
    ): AdvancedOrder =
        tx {
            val order =
                repository.findById(orderId, forUpdate = true) ?: throw NotFoundException("Order tidak ditemukan.")
            principal.scopedBranch(order.branchId)
            if (order.status != fromStatus) {
                throw ConflictException(
                    ErrorCodes.STATUS_CHANGED,
                    "Status order sudah diubah dari perangkat lain.",
                    buildJsonObject { put("order", order.toDetailsJson()) },
                )
            }
            val next = order.status.next ?: return@tx AdvancedOrder(AdvanceOutcome.ALREADY_COMPLETED, order, false)

            val now = clock.instant()
            var waStatus = order.waStatus
            var queued = false
            if (next == OrderStatus.SIAP) {
                val customer = order.customerId?.let { customers.lockForOrder(it) }
                val branch = requireNotNull(branches.find(order.branchId))
                queued =
                    customer?.let {
                        whatsApp.queueReadyForPickup(
                            branchId = order.branchId,
                            orderId = order.id,
                            recipient = WaRecipient(it.id, it.name, it.phone, it.optIn, it.optInAt),
                            orderNumber = order.number,
                            branchName = branch.name,
                            branchHours = branch.hours,
                        )
                    } ?: false
                waStatus = if (queued) WaStatus.MENUNGGU else WaStatus.BELUM_OPTIN
            }
            repository.updateStatus(order.id, next, waStatus, now)
            repository.insertEvent(
                orderId = order.id,
                branchId = order.branchId,
                type = EVENT_STATUS_CHANGED,
                fromStatus = order.status.name,
                toStatus = next.name,
                staffId = principal.staffId,
                deviceId = principal.deviceId,
                payload = buildJsonObject { put("number", order.number) },
                at = now,
            )
            if (waStatus != order.waStatus) {
                repository.insertEvent(
                    orderId = order.id,
                    branchId = order.branchId,
                    type = EVENT_WA_STATUS_CHANGED,
                    fromStatus = null,
                    toStatus = null,
                    staffId = principal.staffId,
                    deviceId = principal.deviceId,
                    payload =
                        buildJsonObject {
                            put("from", order.waStatus.name)
                            put("to", waStatus.name)
                        },
                    at = now,
                )
            }
            audit.write(
                AuditEntry(
                    branchId = order.branchId,
                    actor = principal.auditActor,
                    type = AuditActionType.ORDER_STATUS_CHANGED,
                    action = "Ubah status ${order.number} → ${next.label}${if (queued) " · WA dijadwalkan" else ""}",
                    entityType = ENTITY,
                    entityId = order.id.toString(),
                    metadata =
                        buildJsonObject {
                            put("from", order.status.name)
                            put("to", next.name)
                            put("notificationQueued", queued)
                        },
                ),
            )
            AdvancedOrder(AdvanceOutcome.ADVANCED, requireNotNull(repository.findById(order.id)), queued)
        }

    /**
     * `GET /orders` (PRD §8.6): newest capture first. Without `from`/`to`, orders not yet `SELESAI` plus
     * every order of the last 7 business days. [q] matches the number, the customer name, or the digits of
     * the phone. Counts per status follow the same filter without the status.
     */
    suspend fun list(
        branchIds: Collection<UUID>?,
        status: OrderStatus?,
        q: String?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        cursor: String?,
    ): OrderPage {
        val after = decodeCursor(cursor)
        val today = WibClock.businessDate(clock.instant())
        val filter = OrderFilter(branchIds, status, q, from, to, recentSince = today.minusDays(RECENT_DAYS - 1))
        return tx {
            val rows = repository.list(filter, after, limit + 1)
            val page = rows.take(limit)
            val next = if (rows.size > limit) page.last().let { encodeCursor(it.capturedAt, it.id) } else null
            OrderPage(page, next, repository.countByStatus(filter))
        }
    }

    /** `GET /orders/{id}`: the order with its event history; a cashier only sees their own branch. */
    suspend fun detail(
        principal: StaffPrincipal,
        id: UUID,
    ): OrderDetail =
        tx {
            val order = repository.findById(id) ?: throw NotFoundException("Order tidak ditemukan.")
            principal.scopedBranch(order.branchId)
            OrderDetail(order, repository.events(id))
        }

    /**
     * `GET /orders/next-number` (§7.3, `NextOrderIdUseCase`): an estimate for the POS header only. The
     * final number is taken from the counter when the order is saved.
     */
    suspend fun nextNumber(branchId: UUID): NextNumber =
        tx {
            val branch = branches.find(branchId) ?: throw BranchService.branchNotFound()
            val now = clock.instant()
            val date = WibClock.businessDate(now)
            NextNumber(SaleRecorder.orderNumber(branch.code, now, repository.lastSequence(branch.id, date) + 1), date)
        }

    // ---- Used by SyncService, inside its transaction ------------------------------------------------

    fun findByIds(ids: Collection<UUID>): List<OrderRecord> = repository.findByIds(ids)

    fun activeOrders(branchId: UUID): List<OrderRecord> {
        val today = WibClock.businessDate(clock.instant())
        val filter = OrderFilter(listOf(branchId), null, null, null, null, today.minusDays(RECENT_DAYS - 1))
        return repository.list(filter, after = null, limit = null)
    }

    companion object {
        private const val ENTITY = "order"
        private const val CLIENT_TX_INDEX = "orders_client_tx_id_key"
        private const val EVENT_STATUS_CHANGED = "STATUS_CHANGED"
        private const val EVENT_WA_STATUS_CHANGED = "WA_STATUS_CHANGED"
        private const val RECENT_DAYS = 7L
        const val EMPTY_CART_MESSAGE = "Belum ada layanan di order ini."
        const val CUSTOMER_NOT_FOUND = "Customer tidak ditemukan."

        fun shiftNotOpen(branchName: String) =
            BusinessRuleException(
                ErrorCodes.SHIFT_NOT_OPEN,
                "Shift Cabang $branchName belum dibuka — buka shift dulu sebelum mencatat transaksi.",
            )

        private fun totalMismatch(totals: OrderTotals) =
            ConflictException(
                ErrorCodes.TOTAL_MISMATCH,
                "Total berubah — muat ulang keranjang.",
                buildJsonObject {
                    put("subtotal", totals.subtotal)
                    put("discount", totals.discount)
                    put("total", totals.total)
                    put("earnedPoints", totals.earnedPoints)
                },
            )

        fun unavailable(service: ServiceRecord?) =
            BusinessRuleException(
                ErrorCodes.INVALID_ITEM,
                service?.let { "Layanan ${it.name} sudah tidak tersedia." }
                    ?: "Layanan yang dipilih sudah tidak tersedia.",
            )

        fun invalidQuantity(service: ServiceRecord) =
            BusinessRuleException(ErrorCodes.INVALID_ITEM, "Jumlah ${service.name} tidak valid.")

        fun saleMetadata(order: OrderRecord) =
            buildJsonObject {
                put("number", order.number)
                put("total", order.total)
                put("payment", order.payment.name)
                put("earnedPoints", order.earnedPoints)
                put("redeemedPoints", order.redeemedPoints)
                put("source", order.source.name)
                putJsonArray("flags") { order.flags.forEach { add(it.name) } }
            }

        private fun duplicate(existing: OrderRecord) =
            ConflictException(
                ErrorCodes.DUPLICATE_TRANSACTION,
                "Transaksi ini sudah tersimpan sebagai ${existing.number} — muat ulang daftar order.",
                buildJsonObject { put("order", existing.toDetailsJson()) },
            )

        private fun priceChanged(
            changed: ServiceRecord,
            cart: List<ServiceRecord>,
        ) = ConflictException(
            ErrorCodes.PRICE_CHANGED,
            "Harga ${changed.name} baru saja diubah owner — cek ulang total sebelum bayar.",
            buildJsonObject {
                putJsonArray("services") {
                    cart.distinctBy { it.id }.forEach { service ->
                        addJsonObject {
                            put("id", service.id.toString())
                            put("category", service.category.name)
                            put("name", service.name)
                            put("price", service.price)
                            put("unit", service.unit)
                            put("step", service.step.toDouble())
                            put("active", service.active)
                        }
                    }
                }
            },
        )

        private fun encodeCursor(
            capturedAt: Instant,
            id: UUID,
        ) = Pagination.encode("${capturedAt.toEpochMilli()}|$id")

        private fun decodeCursor(cursor: String?): Pair<Instant, UUID>? =
            Pagination.decode(cursor)?.let { raw ->
                val parts = raw.split("|")
                val millis = parts.getOrNull(0)?.toLongOrNull()
                val id = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (parts.size != 2 || millis == null || id == null) {
                    throw ValidationException("Cursor tidak valid — muat ulang daftar dari awal.")
                }
                Instant.ofEpochMilli(millis) to id
            }
    }
}
