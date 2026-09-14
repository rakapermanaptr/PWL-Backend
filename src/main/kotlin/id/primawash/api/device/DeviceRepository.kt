package id.primawash.api.device

import id.primawash.api.common.Timestamps
import id.primawash.api.db.DevicesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class DeviceRecord(
    val id: UUID,
    val name: String,
    val platform: String,
    val appVersion: String,
    val lastBranchId: UUID?,
    val activatedAt: Instant,
    val lastSeenAt: Instant?,
    val pendingCount: Int,
    val revokedAt: Instant?,
    val pinLockedUntil: Instant?,
) {
    /** A device that has never completed a PIN login has no branch yet. */
    val hasLoggedIn: Boolean get() = lastBranchId != null
}

/** Exposed queries for `devices`. A device is identified by the installation id the app generates. */
class DeviceRepository {
    /** Devices that have completed at least one PIN login — attempts alone never list a tablet. */
    fun findLoggedIn(): List<DeviceRecord> =
        DevicesTable
            .selectAll()
            .where { DevicesTable.lastBranchId.isNotNull() }
            .orderBy(DevicesTable.activatedAt to SortOrder.DESC)
            .map { it.toDevice() }

    fun findById(
        id: UUID,
        forUpdate: Boolean = false,
    ): DeviceRecord? =
        DevicesTable
            .selectAll()
            .where { DevicesTable.id eq id }
            .let { if (forUpdate) it.forUpdate(ForUpdateOption.ForUpdate) else it }
            .firstOrNull()
            ?.toDevice()

    /** Records the installation the first time it is seen; an existing row is left untouched. */
    fun insertIfAbsent(
        id: UUID,
        appVersion: String,
        at: Instant,
    ) {
        DevicesTable.insertIgnore {
            it[DevicesTable.id] = id
            it[name] = "Tablet"
            it[platform] = "ANDROID"
            it[DevicesTable.appVersion] = appVersion
            it[activatedAt] = Timestamps.toDb(at)
            it[lastSeenAt] = Timestamps.toDb(at)
        }
    }

    fun revoke(
        id: UUID,
        at: Instant,
    ) {
        DevicesTable.update({ DevicesTable.id eq id }) { it[revokedAt] = Timestamps.toDb(at) }
    }

    fun recordLogin(
        id: UUID,
        branchId: UUID,
        name: String,
        appVersion: String?,
        at: Instant,
    ) {
        DevicesTable.update({ DevicesTable.id eq id }) {
            it[lastBranchId] = branchId
            it[DevicesTable.name] = name
            if (appVersion != null) it[DevicesTable.appVersion] = appVersion
            it[lastSeenAt] = Timestamps.toDb(at)
        }
    }

    fun updatePinLock(
        id: UUID,
        lockedUntil: Instant?,
    ) {
        DevicesTable.update({ DevicesTable.id eq id }) { it[pinLockedUntil] = lockedUntil?.let(Timestamps::toDb) }
    }

    private fun ResultRow.toDevice() =
        DeviceRecord(
            id = this[DevicesTable.id],
            name = this[DevicesTable.name],
            platform = this[DevicesTable.platform],
            appVersion = this[DevicesTable.appVersion],
            lastBranchId = this[DevicesTable.lastBranchId],
            activatedAt = Timestamps.fromDb(this[DevicesTable.activatedAt]),
            lastSeenAt = this[DevicesTable.lastSeenAt]?.let(Timestamps::fromDb),
            pendingCount = this[DevicesTable.pendingCount],
            revokedAt = this[DevicesTable.revokedAt]?.let(Timestamps::fromDb),
            pinLockedUntil = this[DevicesTable.pinLockedUntil]?.let(Timestamps::fromDb),
        )
}
