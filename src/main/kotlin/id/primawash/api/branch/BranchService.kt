package id.primawash.api.branch

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.Money
import id.primawash.api.common.NotFoundException
import id.primawash.api.db.TransactionRunner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID

data class BranchOverview(
    val branch: BranchRecord,
    val sales: DailySalesRecord,
    val latestShift: ShiftSummary?,
)

data class BranchChange(
    val branch: BranchRecord,
    val changed: Boolean,
)

/** Branch master data (PRD §8.2). */
class BranchService(
    private val tx: TransactionRunner,
    private val repository: BranchRepository,
    private val audit: AuditWriter,
) {
    suspend fun list(): List<BranchRecord> = tx { repository.findAll() }

    /**
     * Per branch for the owner's Branch page: revenue and transaction count on [date] (from
     * `daily_sales`, zero until M2 records sales), and the most recent shift (PRD §8.2).
     */
    suspend fun overview(date: LocalDate): List<BranchOverview> =
        tx {
            val sales = repository.dailySales(date)
            val shifts = repository.latestShifts()
            repository.findAll().map { branch ->
                BranchOverview(branch, sales[branch.id] ?: DailySalesRecord(0, 0), shifts[branch.id])
            }
        }

    /**
     * `PATCH /branches/{id}` (PRD §8.2): changes `dailyTarget` and/or `active`.
     *
     * Rules, in order: the branch exists; a target must be > 0 (`TARGET_REQUIRED`, quoting the old
     * target); the branch this device is working for cannot be deactivated (`BRANCH_IN_USE`). Every
     * rule is checked before anything is written. An unchanged value writes no audit row.
     */
    suspend fun update(
        id: UUID,
        dailyTarget: Long?,
        active: Boolean?,
        callerBranchId: UUID,
        actor: AuditActor,
    ): BranchChange =
        tx {
            val branch = repository.findById(id, forUpdate = true) ?: throw branchNotFound()
            if (dailyTarget != null && dailyTarget <= 0) {
                throw BusinessRuleException(
                    ErrorCodes.TARGET_REQUIRED,
                    "Target harian tidak boleh kosong — dikembalikan ke ${Money.formatRupiah(branch.dailyTarget)}.",
                )
            }
            if (active == false && branch.active && branch.id == callerBranchId) {
                throw BusinessRuleException(
                    ErrorCodes.BRANCH_IN_USE,
                    "Tidak bisa menonaktifkan cabang yang sedang dipakai perangkat ini.",
                )
            }

            var updated = branch
            if (dailyTarget != null && dailyTarget != branch.dailyTarget) {
                repository.updateDailyTarget(id, dailyTarget)
                audit.write(
                    AuditEntry(
                        branchId = id,
                        actor = actor,
                        type = AuditActionType.BRANCH_TARGET_CHANGED,
                        action =
                            "Ubah target harian Cabang ${branch.name}: " +
                                "${Money.formatRupiah(branch.dailyTarget)} → ${Money.formatRupiah(dailyTarget)}",
                        entityType = ENTITY,
                        entityId = id.toString(),
                        metadata =
                            buildJsonObject {
                                put("old", branch.dailyTarget)
                                put("new", dailyTarget)
                            },
                    ),
                )
                updated = updated.copy(dailyTarget = dailyTarget)
            }
            if (active != null && active != branch.active) {
                repository.updateActive(id, active)
                audit.write(
                    AuditEntry(
                        branchId = id,
                        actor = actor,
                        type = AuditActionType.BRANCH_TOGGLED,
                        action = "${if (active) "Aktifkan" else "Nonaktifkan"} Cabang ${branch.name}",
                        entityType = ENTITY,
                        entityId = id.toString(),
                        metadata = buildJsonObject { put("active", active) },
                    ),
                )
                updated = updated.copy(active = active)
            }
            BranchChange(updated, changed = updated != branch)
        }

    /** Lookup for other Services; must run inside the caller's transaction. */
    fun find(id: UUID): BranchRecord? = repository.findById(id)

    /** All branches, inside the caller's transaction. */
    fun findAll(): List<BranchRecord> = repository.findAll()

    /** Latest shift per branch, inside the caller's transaction. */
    fun latestShifts(): Map<UUID, ShiftSummary> = repository.latestShifts()

    companion object {
        private const val ENTITY = "branch"

        fun branchNotFound() = NotFoundException("Cabang tidak ditemukan.")
    }
}
