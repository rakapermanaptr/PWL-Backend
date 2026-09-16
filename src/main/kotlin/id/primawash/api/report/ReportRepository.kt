package id.primawash.api.report

import id.primawash.api.common.Timestamps
import id.primawash.api.db.AuditLogTable
import id.primawash.api.db.CustomersTable
import id.primawash.api.db.DailySalesTable
import id.primawash.api.db.DevicesTable
import id.primawash.api.db.OrderItemsTable
import id.primawash.api.db.OrdersTable
import id.primawash.api.db.WaMessagesTable
import id.primawash.api.order.OrderStatus
import id.primawash.api.wa.WaTemplate
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Revenue and transaction count of one branch on one business date (`daily_sales`). */
data class BranchDaySales(
    val branchId: UUID,
    val businessDate: LocalDate,
    val revenue: Long,
    val txCount: Int,
)

/** Orders still waiting to be collected in one branch; [stale] are the ones sitting past 72 hours. */
data class ReadyCounts(
    val ready: Int,
    val stale: Int,
) {
    companion object {
        val EMPTY = ReadyCounts(0, 0)
    }
}

/** A numerator and the population it is measured against, so the Service can build a ratio. */
data class RatioCounts(
    val matching: Long,
    val total: Long,
)

data class AuditRecord(
    val id: Long,
    val branchId: UUID?,
    val staffId: UUID?,
    val actorName: String,
    val actionType: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val createdAt: Instant,
)

/**
 * Read-only aggregates behind the owner dashboard (PRD §9.4). Every query is scoped to an explicit
 * list of branch ids and an explicit period — never "all time" (trap T11).
 */
class ReportRepository {
    /** `daily_sales` rows of [branchIds] between [from] and [to] inclusive; missing days mean zero. */
    fun dailySales(
        branchIds: List<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): List<BranchDaySales> {
        if (branchIds.isEmpty()) return emptyList()
        return DailySalesTable
            .selectAll()
            .where {
                (DailySalesTable.branchId inList branchIds) and
                    (DailySalesTable.businessDate greaterEq from) and
                    (DailySalesTable.businessDate lessEq to)
            }.map {
                BranchDaySales(
                    branchId = it[DailySalesTable.branchId],
                    businessDate = it[DailySalesTable.businessDate],
                    revenue = it[DailySalesTable.revenue],
                    txCount = it[DailySalesTable.txCount],
                )
            }
    }

    /**
     * Orders in `SIAP` per branch, counted over every business date — an order left uncollected for
     * a month is exactly what this number is for. [staleBefore] is now − 72 hours (§9.4).
     */
    fun readyForPickup(
        branchIds: List<UUID>,
        staleBefore: Instant,
    ): Map<UUID, ReadyCounts> {
        if (branchIds.isEmpty()) return emptyMap()
        val ready = OrdersTable.id.count()
        val stale = Timestamps.toDb(staleBefore)
        val counts =
            OrdersTable
                .select(OrdersTable.branchId, ready)
                .where { (OrdersTable.branchId inList branchIds) and (OrdersTable.status eq OrderStatus.SIAP.name) }
                .groupBy(OrdersTable.branchId)
                .associate { it[OrdersTable.branchId] to it[ready].toInt() }
        val staleCounts =
            OrdersTable
                .select(OrdersTable.branchId, ready)
                .where {
                    (OrdersTable.branchId inList branchIds) and
                        (OrdersTable.status eq OrderStatus.SIAP.name) and
                        (OrdersTable.statusChangedAt less stale)
                }.groupBy(OrdersTable.branchId)
                .associate { it[OrdersTable.branchId] to it[ready].toInt() }
        return counts.mapValues { (branchId, total) -> ReadyCounts(total, staleCounts[branchId] ?: 0) }
    }

    /** Customers whose `home_branch_id` is in scope: how many are opted in, out of how many (§9.4). */
    fun optIn(branchIds: List<UUID>): RatioCounts {
        if (branchIds.isEmpty()) return RatioCounts(0, 0)
        val customers = CustomersTable.id.count()
        val byOptIn =
            CustomersTable
                .select(CustomersTable.optIn, customers)
                .where { CustomersTable.homeBranchId inList branchIds }
                .groupBy(CustomersTable.optIn)
                .associate { it[CustomersTable.optIn] to it[customers] }
        return RatioCounts(matching = byOptIn[true] ?: 0, total = byOptIn.values.sum())
    }

    /**
     * `STATUS_SIAP_DIAMBIL` messages queued in the period: delivered out of delivered + failed.
     * `READ` counts as delivered — it is the same message one step further along, so reading it must
     * not lower the rate. Everything still `QUEUED` is excluded: until the M5 worker runs it has had
     * no chance to be delivered, and counting it would understate the rate rather than report "no
     * data yet" (T6).
     */
    fun statusMessageDelivery(
        branchIds: List<UUID>,
        from: Instant,
        to: Instant,
    ): RatioCounts {
        if (branchIds.isEmpty()) return RatioCounts(0, 0)
        val messages = WaMessagesTable.id.count()
        val byStatus =
            WaMessagesTable
                .select(WaMessagesTable.status, messages)
                .where {
                    (WaMessagesTable.branchId inList branchIds) and
                        (WaMessagesTable.template eq WaTemplate.STATUS_SIAP_DIAMBIL.name) and
                        (WaMessagesTable.queuedAt greaterEq Timestamps.toDb(from)) and
                        (WaMessagesTable.queuedAt less Timestamps.toDb(to))
                }.groupBy(WaMessagesTable.status)
                .associate { it[WaMessagesTable.status] to it[messages] }
        val delivered = (byStatus[DELIVERED] ?: 0) + (byStatus[READ] ?: 0)
        val failed = byStatus[FAILED] ?: 0
        return RatioCounts(matching = delivered, total = delivered + failed)
    }

    /** Orders of the period that redeemed a reward, out of every order of the period (§9.4). */
    fun redemption(
        branchIds: List<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): RatioCounts {
        if (branchIds.isEmpty()) return RatioCounts(0, 0)
        val orders = OrdersTable.id.count()
        val period = OrdersTable.inPeriod(branchIds, from, to)
        val total =
            OrdersTable
                .select(orders)
                .where { period }
                .firstOrNull()
                ?.get(orders) ?: 0
        val redeemed =
            OrdersTable
                .select(orders)
                .where { period and (OrdersTable.redeemedPoints greater 0L) }
                .firstOrNull()
                ?.get(orders)
                ?: 0
        return RatioCounts(matching = redeemed, total = total)
    }

    /** Σ `devices.pending_count` of the tablets last seen working for a branch in scope (§9.4). */
    fun pendingSync(branchIds: List<UUID>): Int {
        if (branchIds.isEmpty()) return 0
        val pending = DevicesTable.pendingCount.sum()
        return DevicesTable
            .select(pending)
            .where { (DevicesTable.lastBranchId inList branchIds) and DevicesTable.revokedAt.isNull() }
            .firstOrNull()
            ?.get(pending)
            ?: 0
    }

    /**
     * Revenue per service name over the period, from the order items themselves — line subtotals,
     * before the order-level reward discount, so the shares describe what was sold.
     */
    fun serviceRevenue(
        branchIds: List<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): Map<String, Long> {
        if (branchIds.isEmpty()) return emptyMap()
        val revenue = OrderItemsTable.subtotal.sum()
        return OrderItemsTable
            .join(OrdersTable, JoinType.INNER, onColumn = OrderItemsTable.orderId, otherColumn = OrdersTable.id)
            .select(OrderItemsTable.name, revenue)
            .where { OrdersTable.inPeriod(branchIds, from, to) }
            .groupBy(OrderItemsTable.name)
            .associate { it[OrderItemsTable.name] to (it[revenue] ?: 0L) }
    }

    /**
     * Audit entries of the window, newest first. Rows with `branch_id = null` apply to every branch
     * and always appear, whatever the scope (CLAUDE.md "Audit Rules"). Reads [limit] + 1 rows so the
     * Service can tell the client the window holds more than the dashboard shows.
     */
    fun audit(
        branchIds: List<UUID>,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<AuditRecord> =
        AuditLogTable
            .selectAll()
            .where {
                val scope =
                    if (branchIds.isEmpty()) {
                        AuditLogTable.branchId.isNull()
                    } else {
                        (AuditLogTable.branchId inList branchIds) or AuditLogTable.branchId.isNull()
                    }
                scope and
                    (AuditLogTable.createdAt greaterEq Timestamps.toDb(from)) and
                    (AuditLogTable.createdAt less Timestamps.toDb(to))
            }.orderBy(AuditLogTable.createdAt to SortOrder.DESC, AuditLogTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .map {
                AuditRecord(
                    id = it[AuditLogTable.id],
                    branchId = it[AuditLogTable.branchId],
                    staffId = it[AuditLogTable.staffId],
                    actorName = it[AuditLogTable.actorName],
                    actionType = it[AuditLogTable.actionType],
                    action = it[AuditLogTable.action],
                    entityType = it[AuditLogTable.entityType],
                    entityId = it[AuditLogTable.entityId],
                    createdAt = Timestamps.fromDb(it[AuditLogTable.createdAt]),
                )
            }

    /** Orders of [branchIds] whose business date falls in the period — revenue is recognised on it. */
    private fun OrdersTable.inPeriod(
        branchIds: List<UUID>,
        from: LocalDate,
        to: LocalDate,
    ) = (branchId inList branchIds) and (businessDate greaterEq from) and (businessDate lessEq to)

    private companion object {
        const val DELIVERED = "DELIVERED"
        const val READ = "READ"
        const val FAILED = "FAILED"
    }
}
