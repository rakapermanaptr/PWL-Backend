package id.primawash.api.branch

import id.primawash.api.common.Timestamps
import id.primawash.api.db.BranchesTable
import id.primawash.api.db.DailySalesTable
import id.primawash.api.db.ShiftsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class BranchRecord(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String,
    val phone: String,
    val hours: String,
    val dailyTarget: Long,
    val active: Boolean,
)

data class ShiftSummary(
    val id: UUID,
    val branchId: UUID,
    val openedByName: String,
    val openedAt: Instant,
    val closedAt: Instant?,
) {
    val open: Boolean get() = closedAt == null
}

data class DailySalesRecord(
    val revenue: Long,
    val txCount: Int,
)

/** Exposed queries for `branches`, plus the per-branch shift and sales reads the branch pages show. */
class BranchRepository {
    fun findAll(): List<BranchRecord> =
        BranchesTable
            .selectAll()
            .orderBy(BranchesTable.sortOrder to SortOrder.ASC, BranchesTable.code to SortOrder.ASC)
            .map { it.toBranch() }

    fun findById(
        id: UUID,
        forUpdate: Boolean = false,
    ): BranchRecord? =
        BranchesTable
            .selectAll()
            .where { BranchesTable.id eq id }
            .let { if (forUpdate) it.forUpdate(ForUpdateOption.ForUpdate) else it }
            .firstOrNull()
            ?.toBranch()

    fun updateDailyTarget(
        id: UUID,
        target: Long,
    ) {
        BranchesTable.update({ BranchesTable.id eq id }) { it[dailyTarget] = target }
    }

    fun updateActive(
        id: UUID,
        active: Boolean,
    ) {
        BranchesTable.update({ BranchesTable.id eq id }) { it[BranchesTable.active] = active }
    }

    /** The most recent shift of every branch that has one — open or closed. */
    fun latestShifts(): Map<UUID, ShiftSummary> =
        ShiftsTable
            .selectAll()
            .withDistinctOn(ShiftsTable.branchId to SortOrder.ASC)
            .orderBy(ShiftsTable.branchId to SortOrder.ASC, ShiftsTable.openedAt to SortOrder.DESC)
            .associate { row ->
                row[ShiftsTable.branchId] to
                    ShiftSummary(
                        id = row[ShiftsTable.id],
                        branchId = row[ShiftsTable.branchId],
                        openedByName = row[ShiftsTable.openedByName],
                        openedAt = Timestamps.fromDb(row[ShiftsTable.openedAt]),
                        closedAt = row[ShiftsTable.closedAt]?.let(Timestamps::fromDb),
                    )
            }

    fun dailySales(date: LocalDate): Map<UUID, DailySalesRecord> =
        DailySalesTable
            .selectAll()
            .where { DailySalesTable.businessDate eq date }
            .associate {
                it[DailySalesTable.branchId] to
                    DailySalesRecord(it[DailySalesTable.revenue], it[DailySalesTable.txCount])
            }

    private fun ResultRow.toBranch() =
        BranchRecord(
            id = this[BranchesTable.id],
            code = this[BranchesTable.code],
            name = this[BranchesTable.name],
            address = this[BranchesTable.address],
            phone = this[BranchesTable.phone],
            hours = this[BranchesTable.hours],
            dailyTarget = this[BranchesTable.dailyTarget],
            active = this[BranchesTable.active],
        )
}
