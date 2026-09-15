package id.primawash.api.order

import id.primawash.api.common.Timestamps
import id.primawash.api.db.OrderEventsTable
import id.primawash.api.db.OrderItemsTable
import id.primawash.api.db.OrdersTable
import id.primawash.api.db.RawSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OrderItemRecord(
    val serviceId: UUID,
    val name: String,
    val qty: BigDecimal,
    val unit: String,
    val unitPrice: Long,
    val subtotal: Long,
)

data class OrderRecord(
    val id: UUID,
    val number: String,
    val branchId: UUID,
    val businessDate: LocalDate,
    val clientTxId: UUID,
    val source: OrderSource,
    val customerId: UUID?,
    val customerName: String,
    val customerPhone: String,
    val note: String,
    val subtotal: Long,
    val discount: Long,
    val total: Long,
    val rewardId: UUID?,
    val rewardName: String?,
    val redeemedPoints: Long,
    val earnedPoints: Long,
    val loyaltyRateId: UUID,
    val payment: PaymentMethod,
    val status: OrderStatus,
    val waStatus: WaStatus,
    val shiftId: UUID,
    val staffId: UUID,
    val deviceId: UUID?,
    val capturedAt: Instant,
    val createdAt: Instant,
    val statusChangedAt: Instant,
    val flags: List<OrderFlag>,
    val version: Int,
    val items: List<OrderItemRecord>,
)

data class OrderEventRecord(
    val id: Long,
    val type: String,
    val fromStatus: String?,
    val toStatus: String?,
    val staffId: UUID?,
    val deviceId: UUID?,
    val createdAt: Instant,
    val payload: JsonObject,
)

/** What `GET /orders` filters on (PRD §8.6). */
data class OrderFilter(
    /** Null = every branch (owner with `scope=all`). */
    val branchIds: Collection<UUID>?,
    val status: OrderStatus?,
    val query: String?,
    val from: LocalDate?,
    val to: LocalDate?,
    /** Without from/to: orders not yet `SELESAI`, plus every order of business dates on or after this. */
    val recentSince: LocalDate?,
)

/** Exposed queries for `orders`, `order_items`, `order_events`, `order_number_counters` and `daily_sales`. */
@Suppress("TooManyFunctions")
class OrderRepository {
    /**
     * The next order sequence of a branch on a business day, atomically: two tablets saving at the same
     * moment serialize on the counter row and never get the same number (T4).
     */
    fun nextSequence(
        branchId: UUID,
        businessDate: LocalDate,
    ): Int =
        RawSql.single(
            """
            INSERT INTO order_number_counters (branch_id, business_date, last_seq) VALUES (?, ?, 1)
            ON CONFLICT (branch_id, business_date)
            DO UPDATE SET last_seq = order_number_counters.last_seq + 1
            RETURNING last_seq
            """.trimIndent(),
            branchId,
            businessDate,
        ) { it.getInt(1) }

    /** The last sequence handed out, without taking one — for `GET /orders/next-number`. */
    fun lastSequence(
        branchId: UUID,
        businessDate: LocalDate,
    ): Int =
        RawSql
            .query(
                "SELECT last_seq FROM order_number_counters WHERE branch_id = ? AND business_date = ?",
                branchId,
                businessDate,
            ) { it.getInt(1) }
            .firstOrNull() ?: 0

    fun insert(
        order: OrderRecord,
        seq: Int,
    ) {
        OrdersTable.insert {
            it[id] = order.id
            it[number] = order.number
            it[branchId] = order.branchId
            it[businessDate] = order.businessDate
            it[OrdersTable.seq] = seq
            it[clientTxId] = order.clientTxId
            it[orderSource] = order.source.name
            it[customerId] = order.customerId
            it[customerName] = order.customerName
            it[customerPhone] = order.customerPhone
            it[note] = order.note
            it[subtotal] = order.subtotal
            it[discount] = order.discount
            it[total] = order.total
            it[rewardId] = order.rewardId
            it[rewardName] = order.rewardName
            it[redeemedPoints] = order.redeemedPoints
            it[earnedPoints] = order.earnedPoints
            it[loyaltyRateId] = order.loyaltyRateId
            it[payment] = order.payment.name
            it[status] = order.status.name
            it[waStatus] = order.waStatus.name
            it[shiftId] = order.shiftId
            it[staffId] = order.staffId
            it[deviceId] = order.deviceId
            it[capturedAt] = Timestamps.toDb(order.capturedAt)
            it[createdAt] = Timestamps.toDb(order.createdAt)
            it[statusChangedAt] = Timestamps.toDb(order.statusChangedAt)
            it[flags] = order.flags.map { flag -> flag.name }
        }
        OrderItemsTable.batchInsert(order.items.withIndex(), shouldReturnGeneratedValues = false) { (index, item) ->
            this[OrderItemsTable.id] = UUID.randomUUID()
            this[OrderItemsTable.orderId] = order.id
            this[OrderItemsTable.position] = index + 1
            this[OrderItemsTable.serviceId] = item.serviceId
            this[OrderItemsTable.name] = item.name
            this[OrderItemsTable.qty] = item.qty
            this[OrderItemsTable.unit] = item.unit
            this[OrderItemsTable.unitPrice] = item.unitPrice
            this[OrderItemsTable.subtotal] = item.subtotal
        }
    }

    @Suppress("LongParameterList")
    fun insertEvent(
        orderId: UUID,
        branchId: UUID,
        type: String,
        fromStatus: String?,
        toStatus: String?,
        staffId: UUID?,
        deviceId: UUID?,
        payload: JsonObject,
        at: Instant,
    ) {
        OrderEventsTable.insert {
            it[OrderEventsTable.orderId] = orderId
            it[OrderEventsTable.branchId] = branchId
            it[OrderEventsTable.type] = type
            it[OrderEventsTable.fromStatus] = fromStatus
            it[OrderEventsTable.toStatus] = toStatus
            it[OrderEventsTable.staffId] = staffId
            it[OrderEventsTable.deviceId] = deviceId
            it[OrderEventsTable.payload] = payload.toString()
            it[createdAt] = Timestamps.toDb(at)
        }
    }

    fun findById(
        id: UUID,
        forUpdate: Boolean = false,
    ): OrderRecord? =
        OrdersTable
            .selectAll()
            .where { OrdersTable.id eq id }
            .let { if (forUpdate) it.forUpdate(ForUpdateOption.ForUpdate) else it }
            .toOrders()
            .firstOrNull()

    fun findByClientTxId(clientTxId: UUID): OrderRecord? =
        OrdersTable
            .selectAll()
            .where { OrdersTable.clientTxId eq clientTxId }
            .toOrders()
            .firstOrNull()

    fun findByIds(ids: Collection<UUID>): List<OrderRecord> =
        if (ids.isEmpty()) emptyList() else OrdersTable.selectAll().where { OrdersTable.id inList ids }.toOrders()

    /** Newest `captured_at` first, keyed on `(captured_at, id)` so new orders never shift a page. */
    fun list(
        filter: OrderFilter,
        after: Pair<Instant, UUID>?,
        limit: Int?,
    ): List<OrderRecord> =
        OrdersTable
            .selectAll()
            .where {
                var condition = filter.toCondition(includeStatus = true)
                after?.let { (capturedAt, id) ->
                    val at = Timestamps.toDb(capturedAt)
                    condition = condition and
                        (
                            (OrdersTable.capturedAt less at) or
                                ((OrdersTable.capturedAt eq at) and (OrdersTable.id less id))
                        )
                }
                condition
            }.orderBy(OrdersTable.capturedAt to SortOrder.DESC, OrdersTable.id to SortOrder.DESC)
            .let { query -> limit?.let { query.limit(it) } ?: query }
            .toOrders()

    /** Orders per status under [filter], ignoring its status — the tab counters of the order screen. */
    fun countByStatus(filter: OrderFilter): Map<OrderStatus, Long> {
        val count = OrdersTable.id.count()
        return OrdersTable
            .select(OrdersTable.status, count)
            .where { filter.toCondition(includeStatus = false) }
            .groupBy(OrdersTable.status)
            .associate { OrderStatus.valueOf(it[OrdersTable.status]) to it[count] }
    }

    fun events(orderId: UUID): List<OrderEventRecord> =
        OrderEventsTable
            .selectAll()
            .where { OrderEventsTable.orderId eq orderId }
            .orderBy(OrderEventsTable.id to SortOrder.ASC)
            .map {
                OrderEventRecord(
                    id = it[OrderEventsTable.id],
                    type = it[OrderEventsTable.type],
                    fromStatus = it[OrderEventsTable.fromStatus],
                    toStatus = it[OrderEventsTable.toStatus],
                    staffId = it[OrderEventsTable.staffId],
                    deviceId = it[OrderEventsTable.deviceId],
                    createdAt = Timestamps.fromDb(it[OrderEventsTable.createdAt]),
                    payload = Json.parseToJsonElement(it[OrderEventsTable.payload]).jsonObject,
                )
            }

    fun updateStatus(
        id: UUID,
        status: OrderStatus,
        waStatus: WaStatus,
        at: Instant,
    ) {
        OrdersTable.update({ OrdersTable.id eq id }) {
            it[OrdersTable.status] = status.name
            it[OrdersTable.waStatus] = waStatus.name
            it[statusChangedAt] = Timestamps.toDb(at)
            it[version] = version + 1
        }
    }

    /** Revenue is recognised on the business date of `captured_at` (§9.4), also for late offline syncs. */
    fun addDailySale(
        branchId: UUID,
        businessDate: LocalDate,
        total: Long,
    ) {
        RawSql.single(
            """
            INSERT INTO daily_sales (branch_id, business_date, revenue, tx_count, updated_at)
            VALUES (?, ?, ?, 1, now())
            ON CONFLICT (branch_id, business_date)
            DO UPDATE SET revenue = daily_sales.revenue + EXCLUDED.revenue,
                          tx_count = daily_sales.tx_count + 1,
                          updated_at = now()
            RETURNING tx_count
            """.trimIndent(),
            branchId,
            businessDate,
            total,
        ) { it.getInt(1) }
    }

    private fun OrderFilter.toCondition(includeStatus: Boolean): Op<Boolean> {
        var condition: Op<Boolean> = Op.TRUE
        branchIds?.let { condition = condition and (OrdersTable.branchId inList it) }
        if (includeStatus) status?.let { condition = condition and (OrdersTable.status eq it.name) }
        from?.let { condition = condition and (OrdersTable.businessDate greaterEq it) }
        to?.let { condition = condition and (OrdersTable.businessDate lessEq it) }
        if (from == null && to == null) {
            recentSince?.let {
                condition = condition and
                    ((OrdersTable.status neq OrderStatus.SELESAI.name) or (OrdersTable.businessDate greaterEq it))
            }
        }
        query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%${escapeLike(text)}%"
            var match: Op<Boolean> =
                (OrdersTable.number.lowerCase() like pattern) or (OrdersTable.customerName.lowerCase() like pattern)
            val digits = text.filter { it.isDigit() }
            if (digits.isNotEmpty()) match = match or (phoneDigits() like "%$digits%")
            condition = condition and match
        }
        return condition
    }

    /** The snapshot phone "0812-3390-4471" without its dashes, so digit search matches across groups. */
    private fun phoneDigits() =
        CustomFunction("replace", TextColumnType(), OrdersTable.customerPhone, stringLiteral("-"), stringLiteral(""))

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun Query.toOrders(): List<OrderRecord> {
        val rows = toList()
        if (rows.isEmpty()) return emptyList()
        val items =
            OrderItemsTable
                .selectAll()
                .where { OrderItemsTable.orderId inList rows.map { it[OrdersTable.id] } }
                .orderBy(OrderItemsTable.orderId to SortOrder.ASC, OrderItemsTable.position to SortOrder.ASC)
                .groupBy({ it[OrderItemsTable.orderId] }) {
                    OrderItemRecord(
                        serviceId = it[OrderItemsTable.serviceId],
                        name = it[OrderItemsTable.name],
                        qty = it[OrderItemsTable.qty],
                        unit = it[OrderItemsTable.unit],
                        unitPrice = it[OrderItemsTable.unitPrice],
                        subtotal = it[OrderItemsTable.subtotal],
                    )
                }
        return rows.map { it.toOrder(items[it[OrdersTable.id]].orEmpty()) }
    }

    private fun ResultRow.toOrder(items: List<OrderItemRecord>) =
        OrderRecord(
            id = this[OrdersTable.id],
            number = this[OrdersTable.number],
            branchId = this[OrdersTable.branchId],
            businessDate = this[OrdersTable.businessDate],
            clientTxId = this[OrdersTable.clientTxId],
            source = OrderSource.valueOf(this[OrdersTable.orderSource]),
            customerId = this[OrdersTable.customerId],
            customerName = this[OrdersTable.customerName],
            customerPhone = this[OrdersTable.customerPhone],
            note = this[OrdersTable.note],
            subtotal = this[OrdersTable.subtotal],
            discount = this[OrdersTable.discount],
            total = this[OrdersTable.total],
            rewardId = this[OrdersTable.rewardId],
            rewardName = this[OrdersTable.rewardName],
            redeemedPoints = this[OrdersTable.redeemedPoints],
            earnedPoints = this[OrdersTable.earnedPoints],
            loyaltyRateId = this[OrdersTable.loyaltyRateId],
            payment = PaymentMethod.valueOf(this[OrdersTable.payment]),
            status = OrderStatus.valueOf(this[OrdersTable.status]),
            waStatus = WaStatus.valueOf(this[OrdersTable.waStatus]),
            shiftId = this[OrdersTable.shiftId],
            staffId = this[OrdersTable.staffId],
            deviceId = this[OrdersTable.deviceId],
            capturedAt = Timestamps.fromDb(this[OrdersTable.capturedAt]),
            createdAt = Timestamps.fromDb(this[OrdersTable.createdAt]),
            statusChangedAt = Timestamps.fromDb(this[OrdersTable.statusChangedAt]),
            flags = this[OrdersTable.flags].map { OrderFlag.valueOf(it) },
            version = this[OrdersTable.version],
            items = items,
        )
}
