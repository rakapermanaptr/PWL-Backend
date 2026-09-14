package id.primawash.api.auth

import id.primawash.api.common.AuditActor
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.ForbiddenException
import id.primawash.api.staff.Role
import java.util.UUID

/**
 * A staff session, resolved from the access token and re-checked against the database on every
 * request: a reset PIN, a deactivated account or a revoked device ends access immediately rather
 * than when the 60-minute token runs out (PRD §16 #13).
 *
 * Role and branch come from here — never from a request body (CLAUDE.md "Security Rules").
 */
data class StaffPrincipal(
    val sessionId: UUID,
    val staffId: UUID,
    val staffName: String,
    val shortName: String,
    val role: Role,
    val branchId: UUID,
    val branchName: String,
    val deviceId: UUID,
) {
    val isOwner: Boolean get() = role == Role.OWNER

    val auditActor: AuditActor get() = AuditActor.staff(staffId, shortName, isOwner, deviceId)

    /** Owner-only endpoints (PRD §4.1). */
    fun requireOwner() {
        if (!isOwner) throw ForbiddenException(ErrorCodes.FORBIDDEN, OWNER_ONLY_MESSAGE)
    }

    /**
     * The branch a query is about. A cashier is always scoped to the token branch: passing another
     * branch is `403 BRANCH_SCOPE`, not a filter hint. An owner may name any branch, or none.
     */
    fun scopedBranch(requested: UUID?): UUID? {
        if (isOwner) return requested
        if (requested != null && requested != branchId) {
            throw ForbiddenException(ErrorCodes.BRANCH_SCOPE, "Kasir hanya bisa mengakses data Cabang $branchName.")
        }
        return branchId
    }

    companion object {
        const val OWNER_ONLY_MESSAGE = "Fitur ini hanya untuk owner/admin."
    }
}
