package id.primawash.api.auth

import java.time.Clock
import java.util.UUID

/**
 * Session revocation used by other features. Split from [AuthService] so that staff and device
 * management can end sessions without depending on the login flow (which itself depends on them).
 *
 * Every function runs inside the caller's transaction: revoking sessions is part of the action that
 * requires it (reset PIN, deactivate account, revoke device) and must roll back with it.
 */
class SessionService(
    private val repository: AuthRepository,
    private val clock: Clock,
) {
    /** Ends every session of a staff member and voids their unused staff proofs (PRD §8.3). */
    fun revokeAllForStaff(staffId: UUID): Int {
        val now = clock.instant()
        repository.expireUnusedProofsOfStaff(staffId, now)
        return repository.revokeSessionsOfStaff(staffId, now)
    }

    /** Ends every session on a device, e.g. when the owner revokes it. */
    fun revokeAllForDevice(deviceId: UUID): Int = repository.revokeSessionsOfDevice(deviceId, clock.instant())
}
