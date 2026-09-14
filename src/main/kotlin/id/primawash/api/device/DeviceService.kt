package id.primawash.api.device

import id.primawash.api.auth.DevicePrincipal
import id.primawash.api.auth.PinHasher
import id.primawash.api.auth.SessionService
import id.primawash.api.branch.BranchService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.SecureTokens
import id.primawash.api.common.UnauthenticatedException
import id.primawash.api.db.TransactionRunner
import id.primawash.api.staff.Role
import id.primawash.api.staff.StaffService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class IssuedActivationCode(
    /** Shown to the owner once, formatted `ABCD-EFGH`. Only its keyed hash is stored. */
    val code: String,
    val branchId: UUID?,
    val expiresAt: Instant,
)

data class ActivatedDevice(
    /** Returned once; the server keeps only its SHA-256. */
    val deviceToken: String,
    val device: DeviceRecord,
)

data class DeviceChange(
    val device: DeviceRecord,
    val changed: Boolean,
)

/**
 * Tablet registration (PRD §8.1, §12.1 step 1). A PIN only works from an activated device, so a
 * leaked PIN is useless outside the shop's tablets.
 */
@Suppress("TooManyFunctions")
class DeviceService(
    private val tx: TransactionRunner,
    private val repository: DeviceRepository,
    private val branches: BranchService,
    private val staff: StaffService,
    private val sessions: SessionService,
    private val audit: AuditWriter,
    private val pinHasher: PinHasher,
    private val tokens: SecureTokens,
    private val clock: Clock,
) {
    /** Resolves a device token (`Authorization: Bearer dt_…`) to a non-revoked device. */
    suspend fun authenticate(token: String): DevicePrincipal {
        val device =
            tx { repository.findByTokenHash(SecureTokens.sha256Hex(token)) }
                ?.takeIf { it.revokedAt == null }
                ?: throw deviceUnauthorized()
        return DevicePrincipal(device.id, device.name, device.lastBranchId)
    }

    /**
     * `POST /devices/activation-codes` (owner): an 8-character code valid for 24 hours, optionally
     * pre-assigning the branch the tablet starts on.
     */
    suspend fun createActivationCode(
        branchId: UUID?,
        actor: AuditActor,
    ): IssuedActivationCode =
        tx {
            val branch = branchId?.let { branches.find(it) ?: throw BranchService.branchNotFound() }
            issueCode(requireNotNull(actor.staffId), branch?.id, branch?.name, actor)
        }

    /**
     * Server-side issuance for the very first tablet, when no device exists yet for an owner to log
     * in on (PRD §12.1: "atau lewat CLI server untuk perangkat pertama"). Attributed to the first
     * active owner account.
     */
    suspend fun createActivationCodeFromServer(branchCode: String?): IssuedActivationCode =
        tx {
            val owner =
                staff.firstActiveOwner() ?: error("Tidak ada owner aktif — jalankan seed atau buat akun owner dulu.")
            val branch =
                branchCode?.let { code ->
                    branches.findAll().firstOrNull { it.code == code.uppercase() }
                        ?: error("Kode cabang $code tidak dikenal.")
                }
            val actor = AuditActor.staff(owner.id, owner.shortName, isOwner = true, deviceId = null)
            issueCode(owner.id, branch?.id, branch?.name, actor)
        }

    private fun issueCode(
        createdBy: UUID,
        branchId: UUID?,
        branchName: String?,
        actor: AuditActor,
    ): IssuedActivationCode {
        val now = clock.instant()
        val code = tokens.newCode(CODE_LENGTH, CODE_ALPHABET)
        val expiresAt = now.plus(CODE_TTL)
        val forBranch = branchName?.let { " untuk Cabang $it" }.orEmpty()
        repository.insertActivationCode(pinHasher.keyedLookup(CODE_PURPOSE, code), createdBy, branchId, now, expiresAt)
        audit.write(
            AuditEntry(
                branchId = branchId,
                actor = actor,
                type = AuditActionType.DEVICE_ACTIVATION_CODE_CREATED,
                action = "Buat kode aktivasi perangkat$forBranch · berlaku 24 jam",
                entityType = "device_activation_code",
            ),
        )
        return IssuedActivationCode(code.chunked(CODE_GROUP).joinToString("-"), branchId, expiresAt)
    }

    /**
     * `POST /devices/activate` (public): exchanges a valid, unused, unexpired code for a device token.
     * The code is spent in the same transaction that creates the device, under a row lock.
     */
    suspend fun activate(
        rawCode: String?,
        name: String?,
        appVersion: String?,
    ): ActivatedDevice {
        val code = rawCode.orEmpty().uppercase().filter { it.isLetterOrDigit() }
        if (code.length != CODE_LENGTH) throw activationCodeInvalid()
        return tx {
            val now = clock.instant()
            val activation =
                repository
                    .findActivationCodeForUpdate(pinHasher.keyedLookup(CODE_PURPOSE, code))
                    ?.takeIf { it.usedAt == null && it.expiresAt.isAfter(now) }
                    ?: throw activationCodeInvalid()
            val branch = activation.branchId?.let { branches.find(it) }
            val deviceName =
                name?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() }
                    ?: branch?.let { "Tablet Cabang ${it.name}" }
                    ?: "Tablet"
            val token = tokens.newToken(TOKEN_PREFIX)
            val deviceId = UUID.randomUUID()
            repository.insert(
                id = deviceId,
                name = deviceName,
                appVersion = appVersion?.trim()?.take(MAX_VERSION_LENGTH).orEmpty(),
                branchId = branch?.id,
                tokenHash = SecureTokens.sha256Hex(token),
                activatedBy = activation.createdBy,
                at = now,
            )
            repository.markActivationCodeUsed(activation.id, deviceId, now)
            val creator = requireNotNull(staff.find(activation.createdBy))
            audit.write(
                AuditEntry(
                    branchId = branch?.id,
                    actor = AuditActor.staff(creator.id, creator.shortName, creator.role == Role.OWNER, deviceId),
                    type = AuditActionType.DEVICE_ACTIVATED,
                    action = "Aktivasi perangkat $deviceName${branch?.let { " untuk Cabang ${it.name}" }.orEmpty()}",
                    entityType = ENTITY,
                    entityId = deviceId.toString(),
                ),
            )
            ActivatedDevice(token, requireNotNull(repository.findById(deviceId)))
        }
    }

    suspend fun list(): List<DeviceRecord> = tx { repository.findAll() }

    /**
     * `DELETE /devices/{id}` (owner): the device token stops working and every session on the tablet
     * ends in the same transaction. Revoking an already revoked device changes nothing.
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
                    action = "Cabut perangkat ${device.name}",
                    entityType = ENTITY,
                    entityId = id.toString(),
                ),
            )
            DeviceChange(device.copy(revokedAt = now), changed = true)
        }

    // ---- Used by AuthService, inside its transaction -------------------------------------------

    /** Locks the device row: PIN attempts from one tablet are evaluated one at a time. */
    fun lockForPinAttempt(id: UUID): DeviceRecord? = repository.findById(id, forUpdate = true)

    fun find(id: UUID): DeviceRecord? = repository.findById(id)

    fun setPinLock(
        id: UUID,
        lockedUntil: Instant?,
    ) = repository.updatePinLock(id, lockedUntil)

    fun recordBranch(
        id: UUID,
        branchId: UUID,
    ) = repository.updateLastBranch(id, branchId, clock.instant())

    companion object {
        private const val ENTITY = "device"
        private const val TOKEN_PREFIX = "dt_"
        private const val CODE_PURPOSE = "device-activation"
        private const val CODE_LENGTH = 8
        private const val CODE_GROUP = 4
        private const val MAX_NAME_LENGTH = 60
        private const val MAX_VERSION_LENGTH = 32

        /** No 0/O, 1/I/L: the code is read off one screen and typed into another. */
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val CODE_TTL: Duration = Duration.ofHours(24)

        fun deviceUnauthorized() =
            UnauthenticatedException(
                ErrorCodes.DEVICE_UNAUTHORIZED,
                "Perangkat belum diaktivasi atau sudah dicabut owner — aktivasi ulang perangkat ini.",
            )

        private fun activationCodeInvalid() =
            BusinessRuleException(
                ErrorCodes.ACTIVATION_CODE_INVALID,
                "Kode aktivasi tidak dikenali atau sudah kedaluwarsa — minta kode baru ke owner.",
            )
    }
}
