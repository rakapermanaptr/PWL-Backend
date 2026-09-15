package id.primawash.api.staff

import id.primawash.api.auth.PinHasher
import id.primawash.api.auth.SessionService
import id.primawash.api.branch.BranchService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.SecureTokens
import id.primawash.api.db.TransactionRunner
import id.primawash.api.db.isUniqueViolation
import id.primawash.api.db.translatingUniqueViolation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

data class StaffChange(
    val staff: StaffRecord,
    val changed: Boolean,
)

data class PinReset(
    val staff: StaffRecord,
    /** Returned to the owner exactly once — never cached, never logged. */
    val pin: String,
)

/** Staff accounts (PRD §8.3) and the account-side PIN bookkeeping used by authentication. */
@Suppress("TooManyFunctions")
class StaffService(
    private val tx: TransactionRunner,
    private val repository: StaffRepository,
    private val branches: BranchService,
    private val sessions: SessionService,
    private val audit: AuditWriter,
    private val pinHasher: PinHasher,
    private val tokens: SecureTokens,
) {
    /** Owner view of every account, optionally filtered (PRD §8.3 `GET /staff`). */
    suspend fun list(
        branchId: UUID?,
        active: Boolean?,
    ): List<StaffRecord> = tx { repository.findAll(branchId, active) }

    /** Cashier view: active accounts that may work at [branchId] — the PIN dialog candidates. */
    suspend fun candidatesFor(branchId: UUID): List<StaffRecord> = tx { repository.findActiveWorkingAt(branchId) }

    /** Short names of active cashiers per branch, for the login screen and the Branch page. */
    suspend fun activeCashierNames(): Map<UUID, List<String>> = tx { activeCashierNamesInTx() }

    fun activeCashierNamesInTx(): Map<UUID, List<String>> =
        repository
            .findAll(branchId = null, active = true)
            .filter { it.role == Role.KASIR && it.branchId != null }
            .groupBy({ requireNotNull(it.branchId) }, { it.shortName })

    /**
     * `POST /staff` (PRD §8.3, `AddStaffUseCase`). Checked in the documented order: name required →
     * PIN 4–6 digits → PIN not used by another active account (T2) → a cashier needs a branch. An
     * owner is always stored with `branchId = null`. `shortName` is derived here, never taken from
     * the client.
     */
    suspend fun create(
        name: String?,
        pin: String?,
        role: Role,
        branchId: UUID?,
        actor: AuditActor,
    ): StaffRecord {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isEmpty()) throw BusinessRuleException(ErrorCodes.NAME_REQUIRED, "Nama staff wajib diisi.")
        if (pin == null || !PinHasher.isWellFormed(pin)) {
            throw BusinessRuleException(ErrorCodes.PIN_FORMAT, "PIN harus 4–6 digit.")
        }
        val lookup = pinHasher.lookup(pin)
        val placement = if (role == Role.OWNER) null else branchId
        return translatingUniqueViolation(PIN_INDEX, ::pinTaken) {
            tx {
                if (repository.existsActiveWithPinLookup(lookup, excluding = null)) throw pinTaken()
                if (role == Role.KASIR && placement == null) {
                    throw BusinessRuleException(
                        ErrorCodes.CASHIER_NEEDS_BRANCH,
                        "Kasir harus ditempatkan di satu cabang.",
                    )
                }
                val branch = placement?.let { branches.find(it) ?: throw BranchService.branchNotFound() }
                val id = UUID.randomUUID()
                repository.insert(id, cleanName, shortNameOf(cleanName), role, placement, lookup, pinHasher.hash(pin))
                val where = branch?.let { "Cabang ${it.name}" } ?: "semua cabang"
                audit.write(
                    AuditEntry(
                        branchId = placement,
                        actor = actor,
                        type = AuditActionType.STAFF_ADDED,
                        action = "Tambah akun staff $cleanName (${role.auditLabel}, $where) · PIN dibuat",
                        entityType = ENTITY,
                        entityId = id.toString(),
                        metadata = buildJsonObject { put("role", role.name) },
                    ),
                )
                requireNotNull(repository.findById(id))
            }
        }
    }

    /**
     * `POST /staff/{id}/reset-pin` (PRD §8.3): a random 4-digit PIN that collides with no account at
     * all — active or not, so a later reactivation cannot conflict either. The old PIN stops working
     * immediately and every session of the account is revoked, in the same transaction.
     */
    suspend fun resetPin(
        id: UUID,
        actor: AuditActor,
    ): PinReset {
        repeat(RESET_ATTEMPTS - 1) {
            try {
                return resetPinOnce(id, actor)
            } catch (error: Exception) {
                // Another account took the same PIN between our check and our write — draw again.
                if (!isUniqueViolation(error, PIN_INDEX)) throw error
            }
        }
        return resetPinOnce(id, actor)
    }

    private suspend fun resetPinOnce(
        id: UUID,
        actor: AuditActor,
    ): PinReset =
        tx {
            val staff = repository.findById(id, forUpdate = true) ?: throw staffNotFound()
            val used = repository.allPinLookups()
            val pin =
                generateSequence { tokens.nextInt(GENERATED_PIN_MIN, GENERATED_PIN_UNTIL).toString() }
                    .take(GENERATED_PIN_UNTIL - GENERATED_PIN_MIN)
                    .firstOrNull { pinHasher.lookup(it) !in used }
                    ?: error("Semua PIN 4 digit sudah terpakai")
            repository.updatePin(id, pinHasher.lookup(pin), pinHasher.hash(pin))
            sessions.revokeAllForStaff(id)
            audit.write(
                AuditEntry(
                    branchId = staff.branchId,
                    actor = actor,
                    type = AuditActionType.STAFF_PIN_RESET,
                    action = "Reset PIN ${staff.name} — PIN lama dicabut",
                    entityType = ENTITY,
                    entityId = id.toString(),
                ),
            )
            PinReset(requireNotNull(repository.findById(id)), pin)
        }

    /**
     * `PATCH /staff/{id}` `active` (PRD §8.3, `ToggleStaffActiveUseCase`).
     *
     * Deactivating: never your own account (`STAFF_SELF_DEACTIVATE`), never the last active owner
     * (`LAST_OWNER`), and every session of the account is revoked. Reactivating: refused with
     * `409 PIN_CONFLICT` when another active account now uses the same PIN (T2, Q8). Setting the
     * current value is a no-op without an audit row.
     */
    suspend fun setActive(
        id: UUID,
        active: Boolean,
        callerStaffId: UUID,
        actor: AuditActor,
    ): StaffChange =
        try {
            tx {
                val staff = repository.findById(id, forUpdate = true) ?: throw staffNotFound()
                if (staff.active == active) return@tx StaffChange(staff, changed = false)

                if (!active) {
                    if (staff.id == callerStaffId) {
                        throw BusinessRuleException(
                            ErrorCodes.STAFF_SELF_DEACTIVATE,
                            "Tidak bisa menonaktifkan akun yang sedang dipakai.",
                        )
                    }
                    if (staff.isOwner && repository.lockActiveOwners().none { it.id != staff.id }) {
                        throw BusinessRuleException(ErrorCodes.LAST_OWNER, "Minimal harus ada satu owner aktif.")
                    }
                } else if (repository.existsActiveWithPinLookup(staff.pinLookup, excluding = staff.id)) {
                    throw pinConflict(staff)
                }

                repository.updateActive(id, active)
                if (!active) sessions.revokeAllForStaff(id)
                audit.write(
                    AuditEntry(
                        branchId = staff.branchId,
                        actor = actor,
                        type = AuditActionType.STAFF_TOGGLED,
                        action = "${if (active) "Aktifkan" else "Nonaktifkan"} akun ${staff.name}",
                        entityType = ENTITY,
                        entityId = id.toString(),
                        metadata = buildJsonObject { put("active", active) },
                    ),
                )
                StaffChange(staff.copy(active = active), changed = true)
            }
        } catch (error: Exception) {
            if (active && isUniqueViolation(error, PIN_INDEX)) {
                throw pinConflict(tx { repository.findById(id) } ?: throw staffNotFound())
            }
            throw error
        }

    // ---- Used by AuthService, inside its transaction -------------------------------------------

    fun find(
        id: UUID,
        forUpdate: Boolean = false,
    ): StaffRecord? = repository.findById(id, forUpdate)

    /** Staff visible to a tablet's cache (PRD §8.10): everyone for an owner, else those who may work at [branchId]. */
    fun findVisible(branchId: UUID?): List<StaffRecord> =
        if (branchId ==
            null
        ) {
            repository.findAll(branchId = null, active = null)
        } else {
            repository.findAllWorkingAt(branchId)
        }

    fun findByIds(ids: Collection<UUID>): List<StaffRecord> = repository.findByIds(ids)

    /** Accounts whose PIN lookup matches, active first. Several only when inactive accounts share it. */
    fun findByPin(pin: String): List<StaffRecord> = repository.findByPinLookup(pinHasher.lookup(pin))

    /** Argon2id verification of [pin] against the account's stored hash (PRD §12.2). */
    fun verifyPin(
        pin: String,
        member: StaffRecord,
    ): Boolean = pinHasher.verify(pin, member.pinHash)

    fun firstActiveOwner(): StaffRecord? =
        repository.findAll(branchId = null, active = true).firstOrNull { it.role == Role.OWNER }

    fun recordSuccessfulLogin(
        id: UUID,
        at: Instant,
    ) = repository.recordLogin(id, at)

    fun recordPinFailures(
        id: UUID,
        failedCount: Int,
        lockedUntil: Instant?,
    ) = repository.updatePinFailures(id, failedCount, lockedUntil)

    companion object {
        private const val ENTITY = "staff"
        private const val PIN_INDEX = "staff_pin_lookup_active_key"
        private const val RESET_ATTEMPTS = 3
        private const val GENERATED_PIN_MIN = 1000
        private const val GENERATED_PIN_UNTIL = 10_000

        /** "Siti Nurhaliza" → "Siti N.", "Raka" → "Raka" (client `AddStaffUseCase`). */
        fun shortNameOf(name: String): String {
            val parts = name.trim().split(" ").filter { it.isNotBlank() }
            return if (parts.size > 1) "${parts[0]} ${parts[1].first()}." else parts.first()
        }

        fun staffNotFound() = NotFoundException("Akun tidak ditemukan.")

        private fun pinTaken() =
            BusinessRuleException(ErrorCodes.PIN_TAKEN, "PIN ini sudah dipakai staff lain — pilih kombinasi lain.")

        private fun pinConflict(staff: StaffRecord) =
            ConflictException(
                ErrorCodes.PIN_CONFLICT,
                "PIN ${staff.name} sudah dipakai staff lain — reset PIN setelah akun diaktifkan.",
            )
    }
}
