package id.primawash.api.device

import id.primawash.api.auth.SessionService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.UnauthenticatedException
import id.primawash.api.db.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DeviceChange(
    val device: DeviceRecord,
    val changed: Boolean,
)

/**
 * Tablets, identified by the installation id the app generates on first launch and sends as
 * `X-Device-Id`. There is no activation step (owner decision, 14 September 2026 — see
 * `docs/prd-gaps-m1.md`): a device is recorded on its first PIN attempt. The row still carries what
 * a device must: the per-device PIN lock, one session per tablet, and the owner's ability to block a
 * lost tablet.
 */
class DeviceService(
    private val tx: TransactionRunner,
    private val repository: DeviceRepository,
    private val sessions: SessionService,
    private val audit: AuditWriter,
    private val clock: Clock,
) {
    /** `GET /devices` (owner): tablets that have logged in at least once, newest first. */
    suspend fun list(): List<DeviceRecord> = tx { repository.findLoggedIn() }

    /** The branch this installation last logged in at, for the login screen's preselection. */
    suspend fun lastBranchOf(deviceId: UUID?): UUID? = deviceId?.let { tx { repository.findById(it)?.lastBranchId } }

    /**
     * `DELETE /devices/{id}` (owner): blocks the tablet — it can no longer log in or refresh, and every
     * session on it ends in the same transaction. Blocking an already blocked tablet changes nothing.
     */
    suspend fun revoke(
        id: UUID,
        actor: AuditActor,
    ): DeviceChange =
        tx {
            val device =
                repository.findById(id, forUpdate = true) ?: throw NotFoundException("Perangkat tidak ditemukan.")
            if (device.revokedAt != null) return@tx DeviceChange(device, changed = false)
            val now = clock.instant()
            repository.revoke(id, now)
            sessions.revokeAllForDevice(id)
            audit.write(
                AuditEntry(
                    branchId = device.lastBranchId,
                    actor = actor,
                    type = AuditActionType.DEVICE_REVOKED,
                    action = "Blokir perangkat ${device.name}",
                    entityType = ENTITY,
                    entityId = id.toString(),
                ),
            )
            DeviceChange(device.copy(revokedAt = now), changed = true)
        }

    // ---- Used by AuthService, inside its transaction -------------------------------------------

    /**
     * Records the installation if it is new and locks its row, so PIN attempts from one tablet are
     * evaluated one at a time. Returns null for a blocked tablet.
     */
    fun registerForPinAttempt(
        id: UUID,
        appVersion: String?,
    ): DeviceRecord? {
        repository.insertIfAbsent(id, appVersion.orEmpty(), clock.instant())
        return requireNotNull(repository.findById(id, forUpdate = true)).takeIf { it.revokedAt == null }
    }

    fun find(id: UUID): DeviceRecord? = repository.findById(id)

    fun setPinLock(
        id: UUID,
        lockedUntil: Instant?,
    ) = repository.updatePinLock(id, lockedUntil)

    /** A successful login names the tablet after its branch and remembers the branch for next time. */
    fun recordLogin(
        device: DeviceRecord,
        branchId: UUID,
        branchName: String,
        appVersion: String?,
    ) = repository.recordLogin(
        id = device.id,
        branchId = branchId,
        name = if (device.name == DEFAULT_NAME) "Tablet Cabang $branchName" else device.name,
        appVersion = appVersion,
        at = clock.instant(),
    )

    companion object {
        private const val ENTITY = "device"
        private const val DEFAULT_NAME = "Tablet"

        fun deviceBlocked() =
            UnauthenticatedException(
                ErrorCodes.DEVICE_UNAUTHORIZED,
                "Tablet ini sudah diblokir owner — hubungi owner untuk memakai tablet lain.",
            )
    }
}
