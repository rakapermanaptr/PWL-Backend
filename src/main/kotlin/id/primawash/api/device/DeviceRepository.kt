package id.primawash.api.device

import id.primawash.api.common.Timestamps
import id.primawash.api.db.DeviceActivationCodesTable
import id.primawash.api.db.DevicesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
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
    val activatedBy: UUID?,
    val activatedAt: Instant,
    val lastSeenAt: Instant?,
    val pendingCount: Int,
    val revokedAt: Instant?,
    val pinLockedUntil: Instant?,
)

data class ActivationCodeRecord(
    val id: UUID,
    val createdBy: UUID,
    val branchId: UUID?,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

/** Exposed queries for `devices` and `device_activation_codes`. Only token/code hashes are stored. */
class DeviceRepository {
    fun findAll(): List<DeviceRecord> =
        DevicesTable
            .selectAll()
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

    fun findByTokenHash(hash: String): DeviceRecord? =
        DevicesTable
            .selectAll()
            .where { DevicesTable.tokenHash eq hash }
            .firstOrNull()
            ?.toDevice()

    @Suppress("LongParameterList")
    fun insert(
        id: UUID,
        name: String,
        appVersion: String,
        branchId: UUID?,
        tokenHash: String,
        activatedBy: UUID,
        at: Instant,
    ) {
        DevicesTable.insert {
            it[DevicesTable.id] = id
            it[DevicesTable.name] = name
            it[platform] = "ANDROID"
            it[DevicesTable.appVersion] = appVersion
            it[lastBranchId] = branchId
            it[DevicesTable.tokenHash] = tokenHash
            it[DevicesTable.activatedBy] = activatedBy
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

    fun updateLastBranch(
        id: UUID,
        branchId: UUID,
        at: Instant,
    ) {
        DevicesTable.update({ DevicesTable.id eq id }) {
            it[lastBranchId] = branchId
            it[lastSeenAt] = Timestamps.toDb(at)
        }
    }

    fun updatePinLock(
        id: UUID,
        lockedUntil: Instant?,
    ) {
        DevicesTable.update({ DevicesTable.id eq id }) { it[pinLockedUntil] = lockedUntil?.let(Timestamps::toDb) }
    }

    fun insertActivationCode(
        codeHash: String,
        createdBy: UUID,
        branchId: UUID?,
        at: Instant,
        expiresAt: Instant,
    ) {
        DeviceActivationCodesTable.insert {
            it[id] = UUID.randomUUID()
            it[DeviceActivationCodesTable.codeHash] = codeHash
            it[DeviceActivationCodesTable.createdBy] = createdBy
            it[DeviceActivationCodesTable.branchId] = branchId
            it[createdAt] = Timestamps.toDb(at)
            it[DeviceActivationCodesTable.expiresAt] = Timestamps.toDb(expiresAt)
        }
    }

    /** Locks the code row so two tablets typing the same code cannot both redeem it. */
    fun findActivationCodeForUpdate(codeHash: String): ActivationCodeRecord? =
        DeviceActivationCodesTable
            .selectAll()
            .where { DeviceActivationCodesTable.codeHash eq codeHash }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let {
                ActivationCodeRecord(
                    id = it[DeviceActivationCodesTable.id],
                    createdBy = it[DeviceActivationCodesTable.createdBy],
                    branchId = it[DeviceActivationCodesTable.branchId],
                    expiresAt = Timestamps.fromDb(it[DeviceActivationCodesTable.expiresAt]),
                    usedAt = it[DeviceActivationCodesTable.usedAt]?.let(Timestamps::fromDb),
                )
            }

    fun markActivationCodeUsed(
        id: UUID,
        deviceId: UUID,
        at: Instant,
    ) {
        DeviceActivationCodesTable.update({ DeviceActivationCodesTable.id eq id }) {
            it[usedAt] = Timestamps.toDb(at)
            it[usedByDevice] = deviceId
        }
    }

    private fun ResultRow.toDevice() =
        DeviceRecord(
            id = this[DevicesTable.id],
            name = this[DevicesTable.name],
            platform = this[DevicesTable.platform],
            appVersion = this[DevicesTable.appVersion],
            lastBranchId = this[DevicesTable.lastBranchId],
            activatedBy = this[DevicesTable.activatedBy],
            activatedAt = Timestamps.fromDb(this[DevicesTable.activatedAt]),
            lastSeenAt = this[DevicesTable.lastSeenAt]?.let(Timestamps::fromDb),
            pendingCount = this[DevicesTable.pendingCount],
            revokedAt = this[DevicesTable.revokedAt]?.let(Timestamps::fromDb),
            pinLockedUntil = this[DevicesTable.pinLockedUntil]?.let(Timestamps::fromDb),
        )
}
