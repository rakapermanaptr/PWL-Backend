package id.primawash.api.wa

import id.primawash.api.common.Timestamps
import id.primawash.api.db.WaMessagesTable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.util.UUID

/** Exposed queries for the WhatsApp outbox. Only inserts `QUEUED` rows — nothing here sends. */
class WaRepository {
    @Suppress("LongParameterList")
    fun insertQueued(
        branchId: UUID,
        orderId: UUID?,
        customerId: UUID,
        template: WaTemplate,
        toPhone: String,
        params: JsonObject,
        at: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        WaMessagesTable.insert {
            it[WaMessagesTable.id] = id
            it[WaMessagesTable.branchId] = branchId
            it[WaMessagesTable.orderId] = orderId
            it[WaMessagesTable.customerId] = customerId
            it[WaMessagesTable.template] = template.name
            it[WaMessagesTable.toPhone] = toPhone
            it[WaMessagesTable.params] = params.toString()
            it[status] = QUEUED
            it[nextAttemptAt] = Timestamps.toDb(at)
            it[queuedAt] = Timestamps.toDb(at)
        }
        return id
    }

    /** Whether a message of [template] was already queued for the customer at or after [since], in any status. */
    fun existsSince(
        customerId: UUID,
        template: WaTemplate,
        since: Instant,
    ): Boolean =
        WaMessagesTable
            .select(WaMessagesTable.id)
            .where {
                (WaMessagesTable.customerId eq customerId) and
                    (WaMessagesTable.template eq template.name) and
                    (WaMessagesTable.queuedAt greaterEq Timestamps.toDb(since))
            }.limit(1)
            .any()

    private companion object {
        const val QUEUED = "QUEUED"
    }
}
