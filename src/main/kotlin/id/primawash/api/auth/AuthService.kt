package id.primawash.api.auth

import id.primawash.api.branch.BranchRecord
import id.primawash.api.branch.BranchService
import id.primawash.api.branch.ShiftSummary
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.DomainException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.ForbiddenException
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.PinLockedException
import id.primawash.api.common.SecureTokens
import id.primawash.api.common.UnauthenticatedException
import id.primawash.api.db.TransactionRunner
import id.primawash.api.device.DeviceRecord
import id.primawash.api.device.DeviceService
import id.primawash.api.staff.StaffRecord
import id.primawash.api.staff.StaffService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class SessionContext(
    val staff: StaffRecord,
    val branch: BranchRecord,
)

data class IssuedSession(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresIn: Long,
    val context: SessionContext,
)

data class VerifiedStaff(
    val staff: StaffRecord,
    val staffProof: String,
    val expiresIn: Long,
)

data class BranchSwitch(
    /** Null when the device was already on the requested branch — no new token, no audit. */
    val session: IssuedSession?,
    val context: SessionContext,
)

data class LoginOption(
    val branch: BranchRecord,
    val cashierNames: List<String>,
    val latestShift: ShiftSummary?,
)

data class LoginOptions(
    val lastBranchId: UUID?,
    val branches: List<LoginOption>,
)

/**
 * Staff authentication on an activated device (PRD §8.1, §12). PIN checks, per-device and
 * per-account lockout, session issuance, rotation and revocation.
 *
 * A rejected PIN must still leave a trace: the failure is audited and counted in the same
 * transaction that decides the rejection, that transaction commits, and only then is the error
 * thrown. Throwing inside the transaction would roll the audit row back and make brute force free.
 */
@Suppress("TooManyFunctions")
class AuthService(
    private val tx: TransactionRunner,
    private val repository: AuthRepository,
    private val staff: StaffService,
    private val branches: BranchService,
    private val devices: DeviceService,
    private val audit: AuditWriter,
    private val accessTokens: AccessTokens,
    private val tokens: SecureTokens,
    private val clock: Clock,
) {
    /**
     * Resolves an access token to a live session. Signature and expiry first, then the session row:
     * revoked or expired sessions, deactivated accounts and revoked devices are all `401`, so a PIN
     * reset or a deactivation takes effect on the very next request (PRD §16 #13).
     */
    suspend fun authenticate(token: String): StaffPrincipal {
        val claims = accessTokens.verify(token)
        return tx {
            val now = clock.instant()
            val session = repository.findSession(claims.sessionId)?.takeIf { it.isLive(now) } ?: throw sessionEnded()
            val member = staff.find(session.staffId)?.takeIf { it.active } ?: throw sessionEnded()
            val device =
                devices.find(session.deviceId)?.takeIf { it.revokedAt == null }
                    ?: throw DeviceService.deviceUnauthorized()
            val branch = session.branchId?.let { branches.find(it) } ?: throw sessionEnded()
            if (session.staffId != claims.staffId || device.id != claims.deviceId || branch.id != claims.branchId) {
                throw sessionEnded()
            }
            StaffPrincipal(
                session.id,
                member.id,
                member.name,
                member.shortName,
                member.role,
                branch.id,
                branch.name,
                device.id,
            )
        }
    }

    /** `GET /login-options`: branches, active cashier short names and shift state — never PIN data. */
    suspend fun loginOptions(device: DevicePrincipal): LoginOptions =
        tx {
            val names = staff.activeCashierNamesInTx()
            val shifts = branches.latestShifts()
            LoginOptions(
                lastBranchId = devices.find(device.deviceId)?.lastBranchId,
                branches = branches.findAll().map { LoginOption(it, names[it.id].orEmpty(), shifts[it.id]) },
            )
        }

    /**
     * `POST /auth/pin-login` (PRD §8.1, order of `LoginWithPinUseCase`): branch exists → branch active →
     * PIN format → device not locked → PIN matches an account → account not locked → account active →
     * a cashier logs in at their own branch only. Every rejection from the PIN check onward is audited
     * as `LOGIN_FAILED` and counts toward the per-device limit (5 in 5 minutes → locked 5 minutes).
     *
     * Success: `last_login_at`, a new session (any other session on this tablet ends — one device, one
     * staff), `devices.last_branch_id`, and a `LOGIN` audit row, all in one transaction.
     */
    suspend fun pinLogin(
        device: DevicePrincipal,
        branchId: UUID?,
        pin: String?,
    ): IssuedSession =
        decide {
            val branch =
                branchId?.let { branches.find(it) }
                    ?: return@decide reject(
                        BusinessRuleException(
                            ErrorCodes.BRANCH_REQUIRED,
                            "Pilih cabang perangkat dulu.",
                            clearPin(false),
                        ),
                    )
            if (!branch.active) {
                return@decide reject(
                    BusinessRuleException(
                        ErrorCodes.BRANCH_INACTIVE,
                        "Cabang ${branch.name} sedang nonaktif — pilih cabang lain atau hubungi owner.",
                        clearPin(false),
                    ),
                )
            }
            if (pin == null || !PinHasher.isWellFormed(pin)) {
                return@decide reject(
                    BusinessRuleException(ErrorCodes.PIN_FORMAT, "PIN minimal 4 digit.", clearPin(false)),
                )
            }
            val now = clock.instant()
            val tablet = requireNotNull(devices.lockForPinAttempt(device.deviceId))
            tablet.pinLockedUntil?.takeIf { it.isAfter(now) }?.let { return@decide reject(pinLocked(now, it)) }

            val candidates = staff.findByPin(pin).filter { staff.verifyPin(pin, it) }
            val matched = candidates.firstOrNull { it.active } ?: candidates.firstOrNull()
            matched?.pinLockedUntil?.takeIf { it.isAfter(now) }?.let { return@decide reject(pinLocked(now, it)) }

            val failure =
                when {
                    matched == null ->
                        BusinessRuleException(
                            ErrorCodes.PIN_UNKNOWN,
                            "PIN tidak dikenali. Coba lagi atau minta owner reset PIN.",
                            clearPin(true),
                        )
                    !matched.active ->
                        BusinessRuleException(
                            ErrorCodes.STAFF_INACTIVE,
                            "Akun ${matched.name} nonaktif — PIN lama sudah diblokir.",
                            clearPin(true),
                        )
                    !matched.canWorkAt(branch.id) -> wrongBranch(matched, branch, clearPin = true)
                    else -> null
                }
            if (failure != null) {
                recordFailedAttempt(tablet, branch, matched, failure, now)
                return@decide reject(failure)
            }

            val member = requireNotNull(matched)
            staff.recordSuccessfulLogin(member.id, now)
            val issued = startSession(tablet.id, member, branch, now)
            devices.recordBranch(tablet.id, branch.id)
            audit.write(
                AuditEntry(
                    branchId = branch.id,
                    actor = AuditActor.staff(member.id, member.shortName, member.isOwner, tablet.id),
                    type = AuditActionType.LOGIN,
                    action = "Login PIN sebagai ${roleInBranch(member, branch)} di perangkat Cabang ${branch.name}",
                    entityType = SESSION_ENTITY,
                    entityId = issued.sessionId.toString(),
                ),
            )
            accept(issued.session)
        }

    /**
     * `POST /auth/refresh`: rotates the refresh token (the old one stops working) and issues a new
     * access token. The session keeps its original 18-hour expiry — rotation never extends it.
     */
    suspend fun refresh(
        device: DevicePrincipal,
        refreshToken: String?,
    ): IssuedSession =
        tx {
            val now = clock.instant()
            val session =
                refreshToken
                    ?.takeIf { it.startsWith(REFRESH_PREFIX) }
                    ?.let { repository.findSessionByRefreshHash(SecureTokens.sha256Hex(it)) }
                    ?.takeIf { it.isLive(now) && it.deviceId == device.deviceId }
                    ?: throw sessionEnded()
            val member = staff.find(session.staffId)?.takeIf { it.active } ?: throw sessionEnded()
            val branch = session.branchId?.let { branches.find(it) } ?: throw sessionEnded()
            val newRefresh = tokens.newToken(REFRESH_PREFIX)
            repository.rotateRefreshToken(session.id, SecureTokens.sha256Hex(newRefresh), now)
            IssuedSession(
                accessToken = accessToken(session.id, member, branch, device.deviceId),
                accessTokenExpiresIn = AccessTokens.TTL.seconds,
                refreshToken = newRefresh,
                refreshTokenExpiresIn = Duration.between(now, session.expiresAt).seconds,
                context = SessionContext(member, branch),
            )
        }

    /**
     * `POST /auth/verify-pin` (PRD §8.1, `VerifyStaffPinUseCase`): proves a specific staff member is
     * present, e.g. before opening a shift. Order: staff chosen → account active → may work at the
     * token branch → PIN format → device not locked → account not locked → PIN correct. A wrong PIN
     * counts toward both the device window and the account lock (5 consecutive → 15 minutes).
     *
     * Returns a single-use `staffProof` valid for 5 minutes; only its hash is stored.
     */
    suspend fun verifyPin(
        principal: StaffPrincipal,
        staffId: UUID?,
        pin: String?,
    ): VerifiedStaff =
        decide {
            val verified = verifyStaffInTx(principal, staffId, pin)
            if (verified is Decision.Rejected) return@decide verified
            val member = (verified as Decision.Accepted).value
            val now = clock.instant()
            val proof = tokens.newToken(PROOF_PREFIX)
            repository.insertStaffProof(
                tokenHash = SecureTokens.sha256Hex(proof),
                staffId = member.id,
                deviceId = principal.deviceId,
                branchId = principal.branchId,
                createdAt = now,
                expiresAt = now.plus(PROOF_TTL),
            )
            accept(VerifiedStaff(member, proof, PROOF_TTL.seconds))
        }

    /**
     * `POST /auth/switch-staff` (PRD §8.1, `SwitchActiveStaffUseCase`): the same checks as verify-pin,
     * then the tablet is handed over — the current session ends and a new one starts for the verified
     * staff member at the same branch. Audited as `STAFF_SWITCHED` by the incoming staff member.
     */
    suspend fun switchStaff(
        principal: StaffPrincipal,
        staffId: UUID?,
        pin: String?,
    ): IssuedSession =
        decide {
            val verified = verifyStaffInTx(principal, staffId, pin)
            if (verified is Decision.Rejected) return@decide verified
            val member = (verified as Decision.Accepted).value
            val branch = requireNotNull(branches.find(principal.branchId))
            val now = clock.instant()
            repository.revokeSession(principal.sessionId, now)
            val issued = startSession(principal.deviceId, member, branch, now)
            audit.write(
                AuditEntry(
                    branchId = branch.id,
                    actor = AuditActor.staff(member.id, member.shortName, member.isOwner, principal.deviceId),
                    type = AuditActionType.STAFF_SWITCHED,
                    action = "Login PIN sebagai ${roleInBranch(member, branch)}",
                    entityType = SESSION_ENTITY,
                    entityId = issued.sessionId.toString(),
                    metadata = buildJsonObject { put("previousStaffId", principal.staffId.toString()) },
                ),
            )
            accept(issued.session)
        }

    /**
     * `POST /auth/switch-branch` (PRD §8.1, `SwitchBranchUseCase`): owner only (a cashier's tablet is
     * bound to their branch) → branch exists → branch active. Moving to a different branch ends the
     * current session, starts one on the new branch and records `DEVICE_BRANCH_SWITCHED`.
     */
    suspend fun switchBranch(
        principal: StaffPrincipal,
        branchId: UUID,
    ): BranchSwitch =
        tx {
            if (!principal.isOwner) {
                throw ForbiddenException(
                    ErrorCodes.OWNER_ONLY,
                    "Perangkat kasir terikat ke Cabang ${principal.branchName}. " +
                        "Hanya owner/admin yang bisa memindahkan perangkat ke cabang lain.",
                )
            }
            val target = branches.find(branchId) ?: throw NotFoundException("Cabang tidak ditemukan.")
            if (!target.active) {
                throw BusinessRuleException(
                    ErrorCodes.BRANCH_INACTIVE,
                    "Cabang ${target.name} nonaktif — aktifkan dulu di halaman Cabang sebelum memindahkan perangkat.",
                )
            }
            val member = requireNotNull(staff.find(principal.staffId))
            if (target.id == principal.branchId) return@tx BranchSwitch(null, SessionContext(member, target))

            val now = clock.instant()
            repository.revokeSession(principal.sessionId, now)
            val issued = startSession(principal.deviceId, member, target, now)
            devices.recordBranch(principal.deviceId, target.id)
            audit.write(
                AuditEntry(
                    branchId = target.id,
                    actor = principal.auditActor,
                    type = AuditActionType.DEVICE_BRANCH_SWITCHED,
                    action = "Pindah konteks perangkat ke Cabang ${target.name}",
                    entityType = "device",
                    entityId = principal.deviceId.toString(),
                    metadata = buildJsonObject { put("fromBranchId", principal.branchId.toString()) },
                ),
            )
            BranchSwitch(issued.session, issued.session.context)
        }

    /** `POST /auth/logout` (`LogoutUseCase`): ends the session and returns the branch to preselect. */
    suspend fun logout(principal: StaffPrincipal): UUID =
        tx {
            repository.revokeSession(principal.sessionId, clock.instant())
            audit.write(
                AuditEntry(
                    branchId = principal.branchId,
                    actor = principal.auditActor,
                    type = AuditActionType.LOGOUT,
                    action = "Logout dari perangkat Cabang ${principal.branchName}",
                    entityType = SESSION_ENTITY,
                    entityId = principal.sessionId.toString(),
                ),
            )
            principal.branchId
        }

    /** `GET /me` (`ObserveSessionContextUseCase`). */
    suspend fun context(principal: StaffPrincipal): SessionContext =
        tx {
            SessionContext(
                requireNotNull(staff.find(principal.staffId)),
                requireNotNull(branches.find(principal.branchId)),
            )
        }

    // ---- PIN verification for a chosen staff member ----------------------------------------------

    private fun verifyStaffInTx(
        principal: StaffPrincipal,
        staffId: UUID?,
        pin: String?,
    ): Decision<StaffRecord> {
        val member = staffId?.let { staff.find(it, forUpdate = true) }
        val branch = requireNotNull(branches.find(principal.branchId))
        val invalid =
            when {
                member == null -> BusinessRuleException(ErrorCodes.STAFF_REQUIRED, "Pilih staff dulu.")
                !member.active ->
                    BusinessRuleException(
                        ErrorCodes.STAFF_INACTIVE,
                        "Akun ${member.name} nonaktif — PIN lama tidak bisa dipakai.",
                    )
                !member.canWorkAt(branch.id) -> wrongBranch(member, branch, clearPin = null)
                pin == null || !PinHasher.isWellFormed(pin) ->
                    BusinessRuleException(ErrorCodes.PIN_FORMAT, "PIN minimal 4 digit.")
                else -> null
            }
        if (invalid != null || member == null || pin == null) return reject(requireNotNull(invalid))

        val now = clock.instant()
        val tablet = requireNotNull(devices.lockForPinAttempt(principal.deviceId))
        val lockedUntil =
            listOfNotNull(
                tablet.pinLockedUntil,
                member.pinLockedUntil,
            ).filter { it.isAfter(now) }.maxOrNull()
        if (lockedUntil != null) return reject(pinLocked(now, lockedUntil))

        if (!staff.verifyPin(pin, member)) {
            val failure = BusinessRuleException(ErrorCodes.PIN_WRONG, "PIN salah untuk ${member.name}. Coba lagi.")
            recordFailedAttempt(tablet, branch, member, failure, now)
            recordAccountFailure(member, branch, principal.deviceId, now)
            return reject(failure)
        }
        staff.recordSuccessfulLogin(member.id, now)
        return Decision.Accepted(member)
    }

    // ---- Lockout bookkeeping ---------------------------------------------------------------------

    /**
     * Audits a failed PIN attempt (without the PIN) and applies the per-device limit: the fifth
     * failure within [DEVICE_WINDOW] locks the tablet for [DEVICE_LOCK]. The attempt that triggers the
     * lock still gets its own error; the next one gets `423`.
     */
    private fun recordFailedAttempt(
        tablet: DeviceRecord,
        branch: BranchRecord,
        member: StaffRecord?,
        failure: DomainException,
        now: Instant,
    ) {
        val actor =
            member?.let { AuditActor.staff(it.id, it.shortName, it.isOwner, tablet.id) }
                ?: AuditActor(staffId = null, actorName = "Perangkat ${tablet.name}", deviceId = tablet.id)
        audit.write(
            AuditEntry(
                branchId = branch.id,
                actor = actor,
                type = AuditActionType.LOGIN_FAILED,
                action = "PIN ditolak di perangkat Cabang ${branch.name} — ${failure.code}",
                entityType = "device",
                entityId = tablet.id.toString(),
                metadata = buildJsonObject { put("reason", failure.code) },
            ),
        )
        val failures = repository.countFailedPinAttempts(tablet.id, since = now.minus(DEVICE_WINDOW))
        if (failures >= MAX_FAILED_ATTEMPTS) {
            devices.setPinLock(tablet.id, now.plus(DEVICE_LOCK))
            audit.write(
                AuditEntry(
                    branchId = branch.id,
                    actor = AuditActor(staffId = null, actorName = "Perangkat ${tablet.name}", deviceId = tablet.id),
                    type = AuditActionType.PIN_LOCKED,
                    action = "Perangkat ${tablet.name} dikunci 5 menit — $MAX_FAILED_ATTEMPTS PIN salah dalam 5 menit",
                    entityType = "device",
                    entityId = tablet.id.toString(),
                ),
            )
        }
    }

    /** Per-account limit for a chosen staff member: 5 consecutive wrong PINs → locked 15 minutes. */
    private fun recordAccountFailure(
        member: StaffRecord,
        branch: BranchRecord,
        deviceId: UUID,
        now: Instant,
    ) {
        val failures = member.pinFailedCount + 1
        if (failures < MAX_FAILED_ATTEMPTS) {
            staff.recordPinFailures(member.id, failures, lockedUntil = null)
            return
        }
        staff.recordPinFailures(member.id, failedCount = 0, lockedUntil = now.plus(ACCOUNT_LOCK))
        audit.write(
            AuditEntry(
                branchId = branch.id,
                actor = AuditActor.staff(member.id, member.shortName, member.isOwner, deviceId),
                type = AuditActionType.PIN_LOCKED,
                action = "Akun ${member.name} dikunci 15 menit — $MAX_FAILED_ATTEMPTS PIN salah berturut-turut",
                entityType = "staff",
                entityId = member.id.toString(),
            ),
        )
    }

    // ---- Sessions --------------------------------------------------------------------------------

    private data class StartedSession(
        val sessionId: UUID,
        val session: IssuedSession,
    )

    private fun startSession(
        deviceId: UUID,
        member: StaffRecord,
        branch: BranchRecord,
        now: Instant,
    ): StartedSession {
        repository.revokeSessionsOfDevice(deviceId, now)
        val refresh = tokens.newToken(REFRESH_PREFIX)
        val sessionId =
            repository.insertSession(
                deviceId,
                member.id,
                branch.id,
                SecureTokens.sha256Hex(refresh),
                now,
                now.plus(SESSION_TTL),
            )
        val refreshed = member.copy(lastLoginAt = now, pinFailedCount = 0, pinLockedUntil = null)
        return StartedSession(
            sessionId,
            IssuedSession(
                accessToken = accessToken(sessionId, member, branch, deviceId),
                accessTokenExpiresIn = AccessTokens.TTL.seconds,
                refreshToken = refresh,
                refreshTokenExpiresIn = SESSION_TTL.seconds,
                context = SessionContext(refreshed, branch),
            ),
        )
    }

    private fun accessToken(
        sessionId: UUID,
        member: StaffRecord,
        branch: BranchRecord,
        deviceId: UUID,
    ) = accessTokens.issue(AccessClaims(member.id, sessionId, deviceId, branch.id, member.role.name))

    private fun SessionRecord.isLive(now: Instant) = revokedAt == null && expiresAt.isAfter(now)

    // ---- Commit-then-throw -----------------------------------------------------------------------

    private sealed interface Decision<out T> {
        data class Accepted<T>(
            val value: T,
        ) : Decision<T>

        data class Rejected(
            val error: DomainException,
        ) : Decision<Nothing>
    }

    private fun <T> accept(value: T): Decision<T> = Decision.Accepted(value)

    private fun reject(error: DomainException): Decision<Nothing> = Decision.Rejected(error)

    /** Runs [block] in one transaction, commits it — rejections included — and only then throws. */
    private suspend fun <T> decide(block: () -> Decision<T>): T =
        when (val decision = tx(block)) {
            is Decision.Accepted -> decision.value
            is Decision.Rejected -> throw decision.error
        }

    private fun pinLocked(
        now: Instant,
        until: Instant,
    ): PinLockedException {
        val millis = Duration.between(now, until).toMillis()
        val seconds = ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1)
        val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return PinLockedException("Terlalu banyak PIN salah. Coba lagi dalam $minutes menit.", seconds, clearPin(true))
    }

    private fun wrongBranch(
        member: StaffRecord,
        branch: BranchRecord,
        clearPin: Boolean?,
    ): BusinessRuleException {
        val home = member.branchId?.let { branches.find(it)?.name }.orEmpty()
        return BusinessRuleException(
            ErrorCodes.STAFF_WRONG_BRANCH,
            "${member.name} terdaftar di Cabang $home — tidak bisa login di perangkat Cabang ${branch.name}.",
            clearPin?.let(::clearPin),
        )
    }

    private fun roleInBranch(
        member: StaffRecord,
        branch: BranchRecord,
    ) = if (member.isOwner) "owner/admin" else "kasir ${branch.name}"

    private fun clearPin(value: Boolean) = buildJsonObject { put("clearPin", value) }

    private fun sessionEnded() = UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, AccessTokens.SESSION_ENDED)

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        val DEVICE_WINDOW: Duration = Duration.ofMinutes(5)
        val DEVICE_LOCK: Duration = Duration.ofMinutes(5)
        val ACCOUNT_LOCK: Duration = Duration.ofMinutes(15)

        /** One operating day (PRD §12.1). */
        val SESSION_TTL: Duration = Duration.ofHours(18)
        val PROOF_TTL: Duration = Duration.ofMinutes(5)
        private const val REFRESH_PREFIX = "rt_"
        private const val PROOF_PREFIX = "sp_"
        private const val SESSION_ENTITY = "session"
        private const val SECONDS_PER_MINUTE = 60
        private const val MILLIS_PER_SECOND = 1_000
    }
}
