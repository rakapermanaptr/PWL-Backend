package id.primawash.api.common

import id.primawash.api.db.AuditLogTable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Clock
import java.util.UUID

/**
 * `audit_log.action_type` — PRD Lampiran C, plus the M1 additions recorded in
 * `docs/prd-gaps-m1.md` (marked below) that still need to be copied into the PRD.
 */
enum class AuditActionType {
    LOGIN,
    LOGIN_FAILED,
    PIN_LOCKED,
    LOGOUT,
    STAFF_SWITCHED,
    DEVICE_BRANCH_SWITCHED,
    DEVICE_ACTIVATED,
    DEVICE_REVOKED,
    ORDER_CREATED,
    ORDER_SYNCED,
    ORDER_STATUS_CHANGED,
    REWARD_REDEEMED,
    SHIFT_OPENED,
    SHIFT_CLOSED,
    CASH_IN,
    CASH_OUT,
    CUSTOMER_REGISTERED,
    CUSTOMER_OPTED_OUT,
    CUSTOMERS_IMPORTED,
    SERVICE_ADDED,
    SERVICE_PRICE_CHANGED,
    SERVICE_TOGGLED,
    LOYALTY_RATE_CHANGED,
    REWARD_ADDED,
    REWARD_TOGGLED,
    STAFF_ADDED,
    STAFF_PIN_RESET,
    STAFF_TOGGLED,
    BRANCH_TARGET_CHANGED,
    BRANCH_TOGGLED,
    WA_RESENT,
    WA_MANUAL_FOLLOW_UP,

    /** M1 addition — an owner issued a device activation code (not in Lampiran C yet). */
    DEVICE_ACTIVATION_CODE_CREATED,

    /** M1 addition — name or WhatsApp opt-in changed through `PATCH /customers/{id}`. */
    CUSTOMER_UPDATED,
}

/**
 * Who performed an action. `actorName` uses the client's `Staff.auditName` format,
 * "Siti N. (kasir)" / "Raka (owner)"; `staffId` is stored alongside it (trap T10).
 */
data class AuditActor(
    val staffId: UUID?,
    val actorName: String,
    val deviceId: UUID?,
) {
    companion object {
        fun staff(
            staffId: UUID,
            shortName: String,
            isOwner: Boolean,
            deviceId: UUID?,
        ) = AuditActor(staffId, auditName(shortName, isOwner), deviceId)

        fun auditName(
            shortName: String,
            isOwner: Boolean,
        ): String = "$shortName (${if (isOwner) "owner" else "kasir"})"
    }
}

data class AuditEntry(
    /** `null` = applies to all branches; such rows appear in every branch-filtered audit query. */
    val branchId: UUID?,
    val actor: AuditActor,
    val type: AuditActionType,
    /** Indonesian sentence shown in the owner's audit log, in the client's existing wording. */
    val action: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * Appends to `audit_log`. Must be called inside the Service transaction of the action it records —
 * never from a background job "after the fact" (CLAUDE.md "Audit Rules"): if the action rolls back,
 * its audit row rolls back with it. The table is append-only at the database level.
 */
class AuditWriter(
    private val clock: Clock,
) {
    fun write(entry: AuditEntry) {
        AuditLogTable.insert {
            it[branchId] = entry.branchId
            it[staffId] = entry.actor.staffId
            it[actorName] = entry.actor.actorName
            it[actionType] = entry.type.name
            it[action] = entry.action
            it[entityType] = entry.entityType
            it[entityId] = entry.entityId
            it[metadata] = entry.metadata.toString()
            it[deviceId] = entry.actor.deviceId
            it[createdAt] = Timestamps.toDb(clock.instant())
        }
    }
}
