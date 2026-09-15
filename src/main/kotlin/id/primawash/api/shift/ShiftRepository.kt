package id.primawash.api.shift

import id.primawash.api.common.Timestamps
import id.primawash.api.db.CashEntriesTable
import id.primawash.api.db.ShiftsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class ShiftRecord(
    val id: UUID,
    val branchId: UUID,
    val openedByStaffId: UUID,
    val openedByName: String,
    val openedAt: Instant,
    val closedAt: Instant?,
    val closedByStaffId: UUID?,
    val openingCash: Long,
    val cashSales: Long,
    val transferSales: Long,
    val txCount: Int,
    val pointsIssued: Long,
    val countedCash: Long,
    val recapExpected: Long?,
    val recapActual: Long?,
    val version: Int,
) {
    val open: Boolean get() = closedAt == null
}

data class CashEntryRecord(
    val id: UUID,
    val shiftId: UUID,
    val branchId: UUID,
    val kind: CashEntryKind,
    val label: String,
    val note: String,
    val amount: Long,
    val orderId: UUID?,
    val staffId: UUID,
    val createdAt: Instant,
)

/** Exposed queries for `shifts` and `cash_entries`. */
@Suppress("TooManyFunctions")
class ShiftRepository {
    fun findById(
        id: UUID,
        forUpdate: Boolean = false,
    ): ShiftRecord? =
        ShiftsTable
            .selectAll()
            .where { ShiftsTable.id eq id }
            .lockedIf(forUpdate)
            .firstOrNull()
            ?.toShift()

    fun findByIds(ids: Collection<UUID>): List<ShiftRecord> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            ShiftsTable.selectAll().where { ShiftsTable.id inList ids }.map { it.toShift() }
        }

    /** The open shift of a branch — at most one, guaranteed by `shifts_one_open_per_branch`. */
    fun findOpen(
        branchId: UUID,
        forUpdate: Boolean = false,
    ): ShiftRecord? =
        ShiftsTable
            .selectAll()
            .where { (ShiftsTable.branchId eq branchId) and ShiftsTable.closedAt.isNull() }
            .lockedIf(forUpdate)
            .firstOrNull()
            ?.toShift()

    fun findLatest(
        branchId: UUID,
        forUpdate: Boolean = false,
    ): ShiftRecord? =
        ShiftsTable
            .selectAll()
            .where { ShiftsTable.branchId eq branchId }
            .orderBy(ShiftsTable.openedAt to SortOrder.DESC, ShiftsTable.id to SortOrder.DESC)
            .limit(1)
            .lockedIf(forUpdate)
            .firstOrNull()
            ?.toShift()

    /** The most recent shift of each branch in [branchIds] (every branch when null). */
    fun latestPerBranch(branchIds: Collection<UUID>?): List<ShiftRecord> =
        ShiftsTable
            .selectAll()
            .withDistinctOn(ShiftsTable.branchId to SortOrder.ASC)
            .let { query -> branchIds?.let { ids -> query.where { ShiftsTable.branchId inList ids } } ?: query }
            .orderBy(ShiftsTable.branchId to SortOrder.ASC, ShiftsTable.openedAt to SortOrder.DESC)
            .map { it.toShift() }

    /** Newest first, keyed on `(opened_at, id)` so a page never shifts under the client. */
    fun history(
        branchId: UUID?,
        openedFrom: Instant?,
        openedUntil: Instant?,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<ShiftRecord> =
        ShiftsTable
            .selectAll()
            .where {
                var condition: Op<Boolean> = Op.TRUE
                branchId?.let { condition = condition and (ShiftsTable.branchId eq it) }
                openedFrom?.let { condition = condition and (ShiftsTable.openedAt greaterEq Timestamps.toDb(it)) }
                openedUntil?.let { condition = condition and (ShiftsTable.openedAt less Timestamps.toDb(it)) }
                after?.let { (openedAt, id) ->
                    val at = Timestamps.toDb(openedAt)
                    condition = condition and
                        ((ShiftsTable.openedAt less at) or ((ShiftsTable.openedAt eq at) and (ShiftsTable.id less id)))
                }
                condition
            }.orderBy(ShiftsTable.openedAt to SortOrder.DESC, ShiftsTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toShift() }

    @Suppress("LongParameterList")
    fun insert(
        id: UUID,
        branchId: UUID,
        openedBy: UUID,
        openedByName: String,
        openingCash: Long,
        at: Instant,
    ) {
        ShiftsTable.insert {
            it[ShiftsTable.id] = id
            it[ShiftsTable.branchId] = branchId
            it[openedByStaffId] = openedBy
            it[ShiftsTable.openedByName] = openedByName
            it[openedAt] = Timestamps.toDb(at)
            it[ShiftsTable.openingCash] = openingCash
            it[countedCash] = openingCash
        }
    }

    fun addSale(
        id: UUID,
        cash: Long,
        transfer: Long,
        points: Long,
    ) {
        ShiftsTable.update({ ShiftsTable.id eq id }) {
            it[cashSales] = cashSales + cash
            it[transferSales] = transferSales + transfer
            it[txCount] = txCount + 1
            it[pointsIssued] = pointsIssued + points
            it[version] = version + 1
        }
    }

    fun updateCountedCash(
        id: UUID,
        amount: Long,
    ) {
        ShiftsTable.update({ ShiftsTable.id eq id }) {
            it[countedCash] = amount
            it[version] = version + 1
        }
    }

    fun close(
        id: UUID,
        countedCash: Long,
        expected: Long,
        closedBy: UUID,
        at: Instant,
    ) {
        ShiftsTable.update({ ShiftsTable.id eq id }) {
            it[ShiftsTable.countedCash] = countedCash
            it[recapExpected] = expected
            it[recapActual] = countedCash
            it[closedAt] = Timestamps.toDb(at)
            it[closedByStaffId] = closedBy
            it[version] = version + 1
        }
    }

    fun touch(id: UUID) {
        ShiftsTable.update({ ShiftsTable.id eq id }) { it[version] = version + 1 }
    }

    // ---- Cash entries ----------------------------------------------------------------------------

    fun entries(shiftId: UUID): List<CashEntryRecord> =
        CashEntriesTable
            .selectAll()
            .where { CashEntriesTable.shiftId eq shiftId }
            .orderBy(CashEntriesTable.createdAt to SortOrder.ASC, CashEntriesTable.id to SortOrder.ASC)
            .map { it.toEntry() }

    /** Σ amount of every entry except `SALE` per shift — the part of expected cash not in `cash_sales` (§9.3). */
    fun nonSaleTotals(shiftIds: Collection<UUID>): Map<UUID, Long> {
        if (shiftIds.isEmpty()) return emptyMap()
        val total = CashEntriesTable.amount.sum()
        return CashEntriesTable
            .select(CashEntriesTable.shiftId, total)
            .where {
                (CashEntriesTable.shiftId inList shiftIds) and (CashEntriesTable.kind neq CashEntryKind.SALE.name)
            }.groupBy(CashEntriesTable.shiftId)
            .associate { it[CashEntriesTable.shiftId] to (it[total] ?: 0L) }
    }

    @Suppress("LongParameterList")
    fun insertEntry(
        shiftId: UUID,
        branchId: UUID,
        kind: CashEntryKind,
        label: String,
        note: String,
        amount: Long,
        orderId: UUID?,
        staffId: UUID,
        at: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        CashEntriesTable.insert {
            it[CashEntriesTable.id] = id
            it[CashEntriesTable.shiftId] = shiftId
            it[CashEntriesTable.branchId] = branchId
            it[CashEntriesTable.kind] = kind.name
            it[CashEntriesTable.label] = label
            it[CashEntriesTable.note] = note
            it[CashEntriesTable.amount] = amount
            it[CashEntriesTable.orderId] = orderId
            it[CashEntriesTable.staffId] = staffId
            it[createdAt] = Timestamps.toDb(at)
        }
        return id
    }

    private fun Query.lockedIf(forUpdate: Boolean): Query =
        if (forUpdate) forUpdate(ForUpdateOption.ForUpdate) else this

    private fun ResultRow.toShift() =
        ShiftRecord(
            id = this[ShiftsTable.id],
            branchId = this[ShiftsTable.branchId],
            openedByStaffId = this[ShiftsTable.openedByStaffId],
            openedByName = this[ShiftsTable.openedByName],
            openedAt = Timestamps.fromDb(this[ShiftsTable.openedAt]),
            closedAt = this[ShiftsTable.closedAt]?.let(Timestamps::fromDb),
            closedByStaffId = this[ShiftsTable.closedByStaffId],
            openingCash = this[ShiftsTable.openingCash],
            cashSales = this[ShiftsTable.cashSales],
            transferSales = this[ShiftsTable.transferSales],
            txCount = this[ShiftsTable.txCount],
            pointsIssued = this[ShiftsTable.pointsIssued],
            countedCash = this[ShiftsTable.countedCash],
            recapExpected = this[ShiftsTable.recapExpected],
            recapActual = this[ShiftsTable.recapActual],
            version = this[ShiftsTable.version],
        )

    private fun ResultRow.toEntry() =
        CashEntryRecord(
            id = this[CashEntriesTable.id],
            shiftId = this[CashEntriesTable.shiftId],
            branchId = this[CashEntriesTable.branchId],
            kind = CashEntryKind.valueOf(this[CashEntriesTable.kind]),
            label = this[CashEntriesTable.label],
            note = this[CashEntriesTable.note],
            amount = this[CashEntriesTable.amount],
            orderId = this[CashEntriesTable.orderId],
            staffId = this[CashEntriesTable.staffId],
            createdAt = Timestamps.fromDb(this[CashEntriesTable.createdAt]),
        )
}
