package id.primawash.api.auth

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.Timestamps
import id.primawash.api.db.AuditLogTable
import id.primawash.api.db.SessionsTable
import id.primawash.api.db.StaffProofsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class SessionRecord(
    val id: UUID,
    val deviceId: UUID,
    val staffId: UUID,
    val branchId: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class StaffProofRecord(
    val id: UUID,
    val staffId: UUID,
    val deviceId: UUID,
    val branchId: UUID,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

/** Exposed queries for `sessions`, `staff_proofs`, and the failed-PIN window read from `audit_log`. */
class AuthRepository {
    fun insertSession(
        deviceId: UUID,
        staffId: UUID,
        branchId: UUID,
        refreshTokenHash: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        SessionsTable.insert {
            it[SessionsTable.id] = id
            it[SessionsTable.deviceId] = deviceId
            it[SessionsTable.staffId] = staffId
            it[SessionsTable.branchId] = branchId
            it[SessionsTable.refreshTokenHash] = refreshTokenHash
            it[SessionsTable.createdAt] = Timestamps.toDb(createdAt)
            it[SessionsTable.expiresAt] = Timestamps.toDb(expiresAt)
        }
        return id
    }

    fun findSession(id: UUID): SessionRecord? =
        SessionsTable
            .selectAll()
            .where { SessionsTable.id eq id }
            .firstOrNull()
            ?.toSession()

    fun findSessionByRefreshHash(hash: String): SessionRecord? =
        SessionsTable
            .selectAll()
            .where { SessionsTable.refreshTokenHash eq hash }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.toSession()

    fun rotateRefreshToken(
        id: UUID,
        newHash: String,
        at: Instant,
    ) {
        SessionsTable.update({ SessionsTable.id eq id }) {
            it[refreshTokenHash] = newHash
            it[refreshedAt] = Timestamps.toDb(at)
        }
    }

    fun revokeSession(
        id: UUID,
        at: Instant,
    ) {
        SessionsTable.update({ (SessionsTable.id eq id) and SessionsTable.revokedAt.isNull() }) {
            it[revokedAt] = Timestamps.toDb(at)
        }
    }

    fun revokeSessionsOfStaff(
        staffId: UUID,
        at: Instant,
    ): Int =
        SessionsTable.update({ (SessionsTable.staffId eq staffId) and SessionsTable.revokedAt.isNull() }) {
            it[revokedAt] = Timestamps.toDb(at)
        }

    fun revokeSessionsOfDevice(
        deviceId: UUID,
        at: Instant,
        except: UUID? = null,
    ): Int =
        SessionsTable.update({
            val base = (SessionsTable.deviceId eq deviceId) and SessionsTable.revokedAt.isNull()
            if (except == null) base else base and (SessionsTable.id neq except)
        }) {
            it[revokedAt] = Timestamps.toDb(at)
        }

    fun insertStaffProof(
        tokenHash: String,
        staffId: UUID,
        deviceId: UUID,
        branchId: UUID,
        createdAt: Instant,
        expiresAt: Instant,
    ) {
        StaffProofsTable.insert {
            it[id] = UUID.randomUUID()
            it[StaffProofsTable.tokenHash] = tokenHash
            it[StaffProofsTable.staffId] = staffId
            it[StaffProofsTable.deviceId] = deviceId
            it[StaffProofsTable.branchId] = branchId
            it[StaffProofsTable.createdAt] = Timestamps.toDb(createdAt)
            it[StaffProofsTable.expiresAt] = Timestamps.toDb(expiresAt)
        }
    }

    /** Locks the proof row, so one proof can be spent by one request only. */
    fun findStaffProofForUpdate(tokenHash: String): StaffProofRecord? =
        StaffProofsTable
            .selectAll()
            .where { StaffProofsTable.tokenHash eq tokenHash }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let {
                StaffProofRecord(
                    id = it[StaffProofsTable.id],
                    staffId = it[StaffProofsTable.staffId],
                    deviceId = it[StaffProofsTable.deviceId],
                    branchId = it[StaffProofsTable.branchId],
                    expiresAt = Timestamps.fromDb(it[StaffProofsTable.expiresAt]),
                    usedAt = it[StaffProofsTable.usedAt]?.let(Timestamps::fromDb),
                )
            }

    fun markStaffProofUsed(
        id: UUID,
        at: Instant,
    ) {
        StaffProofsTable.update({ (StaffProofsTable.id eq id) and StaffProofsTable.usedAt.isNull() }) {
            it[usedAt] = Timestamps.toDb(at)
        }
    }

    /** An unused proof is worthless once the PIN it proved has been reset or the account disabled. */
    fun expireUnusedProofsOfStaff(
        staffId: UUID,
        at: Instant,
    ) {
        StaffProofsTable.update({ (StaffProofsTable.staffId eq staffId) and StaffProofsTable.usedAt.isNull() }) {
            it[usedAt] = Timestamps.toDb(at)
        }
    }

    /** Failed PIN attempts recorded for [deviceId] since [since] (PRD §12.2 per-device window). */
    fun countFailedPinAttempts(
        deviceId: UUID,
        since: Instant,
    ): Long =
        AuditLogTable
            .selectAll()
            .where {
                (AuditLogTable.deviceId eq deviceId) and
                    (AuditLogTable.actionType eq AuditActionType.LOGIN_FAILED.name) and
                    (AuditLogTable.createdAt greaterEq Timestamps.toDb(since))
            }.count()

    private fun ResultRow.toSession() =
        SessionRecord(
            id = this[SessionsTable.id],
            deviceId = this[SessionsTable.deviceId],
            staffId = this[SessionsTable.staffId],
            branchId = this[SessionsTable.branchId],
            createdAt = Timestamps.fromDb(this[SessionsTable.createdAt]),
            expiresAt = Timestamps.fromDb(this[SessionsTable.expiresAt]),
            revokedAt = this[SessionsTable.revokedAt]?.let(Timestamps::fromDb),
        )
}
