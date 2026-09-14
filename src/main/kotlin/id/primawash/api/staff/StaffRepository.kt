package id.primawash.api.staff

import id.primawash.api.common.Timestamps
import id.primawash.api.db.StaffTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class StaffRecord(
    val id: UUID,
    val name: String,
    val shortName: String,
    val role: Role,
    val branchId: UUID?,
    val pinLookup: String,
    val pinHash: String,
    val pinFailedCount: Int,
    val pinLockedUntil: Instant?,
    val active: Boolean,
    val lastLoginAt: Instant?,
) {
    val isOwner: Boolean get() = role == Role.OWNER

    /** Cashiers work at their own branch only; owners at any branch (client `Staff.canWorkAt`). */
    fun canWorkAt(branch: UUID): Boolean = branchId == null || branchId == branch
}

/** Exposed queries for `staff`. PIN columns are read here but never leave the Service layer. */
class StaffRepository {
    fun findById(
        id: UUID,
        forUpdate: Boolean = false,
    ): StaffRecord? =
        StaffTable
            .selectAll()
            .where { StaffTable.id eq id }
            .let { if (forUpdate) it.forUpdate(ForUpdateOption.ForUpdate) else it }
            .firstOrNull()
            ?.toStaff()

    fun findAll(
        branchId: UUID?,
        active: Boolean?,
    ): List<StaffRecord> =
        StaffTable
            .selectAll()
            .where {
                var condition: Op<Boolean> = Op.TRUE
                if (branchId != null) condition = condition and (StaffTable.branchId eq branchId)
                if (active != null) condition = condition and (StaffTable.active eq active)
                condition
            }.orderBy(StaffTable.sortOrder to SortOrder.ASC, StaffTable.name to SortOrder.ASC)
            .map { it.toStaff() }

    /** Active staff who may work at [branchId]: its cashiers plus every owner. */
    fun findActiveWorkingAt(branchId: UUID): List<StaffRecord> =
        StaffTable
            .selectAll()
            .where {
                (StaffTable.active eq true) and ((StaffTable.branchId eq branchId) or StaffTable.branchId.isNull())
            }.orderBy(StaffTable.sortOrder to SortOrder.ASC, StaffTable.name to SortOrder.ASC)
            .map { it.toStaff() }

    fun findByPinLookup(lookup: String): List<StaffRecord> =
        StaffTable
            .selectAll()
            .where { StaffTable.pinLookup eq lookup }
            .orderBy(StaffTable.active to SortOrder.DESC)
            .map { it.toStaff() }

    fun existsActiveWithPinLookup(
        lookup: String,
        excluding: UUID?,
    ): Boolean =
        StaffTable
            .select(StaffTable.id)
            .where {
                val base = (StaffTable.pinLookup eq lookup) and (StaffTable.active eq true)
                if (excluding == null) base else base and (StaffTable.id neq excluding)
            }.limit(1)
            .any()

    fun allPinLookups(): Set<String> = StaffTable.select(StaffTable.pinLookup).map { it[StaffTable.pinLookup] }.toSet()

    /** Locks the active owner rows, so two owners cannot deactivate each other at the same moment. */
    fun lockActiveOwners(): List<StaffRecord> =
        StaffTable
            .selectAll()
            .where { (StaffTable.role eq Role.OWNER.name) and (StaffTable.active eq true) }
            .orderBy(StaffTable.id to SortOrder.ASC)
            .forUpdate(ForUpdateOption.ForUpdate)
            .map { it.toStaff() }

    @Suppress("LongParameterList")
    fun insert(
        id: UUID,
        name: String,
        shortName: String,
        role: Role,
        branchId: UUID?,
        pinLookup: String,
        pinHash: String,
    ) {
        val nextSort =
            (StaffTable.select(StaffTable.sortOrder.max()).firstOrNull()?.get(StaffTable.sortOrder.max()) ?: 0) + 1
        StaffTable.insert {
            it[StaffTable.id] = id
            it[StaffTable.name] = name
            it[StaffTable.shortName] = shortName
            it[StaffTable.role] = role.name
            it[StaffTable.branchId] = branchId
            it[StaffTable.pinLookup] = pinLookup
            it[StaffTable.pinHash] = pinHash
            it[active] = true
            it[sortOrder] = nextSort
        }
    }

    fun updatePin(
        id: UUID,
        lookup: String,
        hash: String,
    ) {
        StaffTable.update({ StaffTable.id eq id }) {
            it[pinLookup] = lookup
            it[pinHash] = hash
            it[pinFailedCount] = 0
            it[pinLockedUntil] = null
        }
    }

    fun updateActive(
        id: UUID,
        active: Boolean,
    ) {
        StaffTable.update({ StaffTable.id eq id }) { it[StaffTable.active] = active }
    }

    fun recordLogin(
        id: UUID,
        at: Instant,
    ) {
        StaffTable.update({ StaffTable.id eq id }) {
            it[lastLoginAt] = Timestamps.toDb(at)
            it[pinFailedCount] = 0
            it[pinLockedUntil] = null
        }
    }

    fun updatePinFailures(
        id: UUID,
        failedCount: Int,
        lockedUntil: Instant?,
    ) {
        StaffTable.update({ StaffTable.id eq id }) {
            it[pinFailedCount] = failedCount
            it[pinLockedUntil] = lockedUntil?.let(Timestamps::toDb)
        }
    }

    private fun ResultRow.toStaff() =
        StaffRecord(
            id = this[StaffTable.id],
            name = this[StaffTable.name],
            shortName = this[StaffTable.shortName],
            role = Role.valueOf(this[StaffTable.role]),
            branchId = this[StaffTable.branchId],
            pinLookup = this[StaffTable.pinLookup],
            pinHash = this[StaffTable.pinHash],
            pinFailedCount = this[StaffTable.pinFailedCount],
            pinLockedUntil = this[StaffTable.pinLockedUntil]?.let(Timestamps::fromDb),
            active = this[StaffTable.active],
            lastLoginAt = this[StaffTable.lastLoginAt]?.let(Timestamps::fromDb),
        )
}
