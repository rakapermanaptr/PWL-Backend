package id.primawash.api.customer

import id.primawash.api.common.Timestamps
import id.primawash.api.db.CustomersTable
import id.primawash.api.db.PointsLedgerTable
import id.primawash.api.db.WaMessagesTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class CustomerRecord(
    val id: UUID,
    val name: String,
    val phone: String,
    val phoneDigits: String,
    val points: Long,
    val optIn: Boolean,
    val visits: Int,
    val homeBranchId: UUID,
    /** When the current WhatsApp consent was given — `OPT_IN_CONFIRM` is sent once per consent. */
    val optInAt: Instant? = null,
)

data class PointsEntryRecord(
    val id: Long,
    val type: String,
    val delta: Long,
    val balanceAfter: Long,
    val orderId: UUID?,
    val staffId: UUID?,
    val createdAt: Instant,
)

/** Exposed queries for `customers`, their `points_ledger`, and opt-out of queued WhatsApp messages. */
class CustomerRepository {
    /**
     * Without [query]: most visits first. With [query]: name contains it (case-insensitive) or, when it
     * has digits, the phone digits contain them — the same match as the client's `customerMatches`.
     */
    fun search(
        query: String?,
        limit: Int,
        offset: Long,
    ): List<CustomerRecord> {
        val text = query?.trim()?.lowercase().orEmpty()
        val digits = text.filter { it.isDigit() }
        return CustomersTable
            .selectAll()
            .let { base ->
                if (text.isEmpty()) {
                    base
                } else {
                    base.where {
                        val byName: Op<Boolean> = CustomersTable.name.lowerCase() like "%${escapeLike(text)}%"
                        if (digits.isEmpty()) byName else byName or (CustomersTable.phoneDigits like "%$digits%")
                    }
                }
            }.orderBy(
                CustomersTable.visits to SortOrder.DESC,
                CustomersTable.name to SortOrder.ASC,
                CustomersTable.id to SortOrder.ASC,
            ).limit(limit)
            .offset(offset)
            .map { it.toCustomer() }
    }

    fun findById(
        id: UUID,
        forUpdate: Boolean = false,
    ): CustomerRecord? =
        CustomersTable
            .selectAll()
            .where { CustomersTable.id eq id }
            .let { if (forUpdate) it.forUpdate(ForUpdateOption.ForUpdate) else it }
            .firstOrNull()
            ?.toCustomer()

    fun findByIds(ids: Collection<UUID>): List<CustomerRecord> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            CustomersTable.selectAll().where { CustomersTable.id inList ids }.map { it.toCustomer() }
        }

    fun findAll(): List<CustomerRecord> =
        CustomersTable.selectAll().orderBy(CustomersTable.name to SortOrder.ASC).map { it.toCustomer() }

    fun findByPhoneDigits(digits: String): CustomerRecord? =
        CustomersTable
            .selectAll()
            .where { CustomersTable.phoneDigits eq digits }
            .firstOrNull()
            ?.toCustomer()

    @Suppress("LongParameterList")
    fun insert(
        id: UUID,
        name: String,
        phone: String,
        phoneDigits: String,
        optIn: Boolean,
        homeBranchId: UUID,
        createdBy: UUID,
        at: Instant,
    ) {
        CustomersTable.insert {
            it[CustomersTable.id] = id
            it[CustomersTable.name] = name
            it[CustomersTable.phone] = phone
            it[CustomersTable.phoneDigits] = phoneDigits
            it[pointsBalance] = 0
            it[CustomersTable.optIn] = optIn
            it[optInAt] = if (optIn) Timestamps.toDb(at) else null
            it[visits] = 0
            it[CustomersTable.homeBranchId] = homeBranchId
            it[createdByStaffId] = createdBy
            it[createdAt] = Timestamps.toDb(at)
        }
    }

    fun updateName(
        id: UUID,
        name: String,
    ) {
        CustomersTable.update({ CustomersTable.id eq id }) { it[CustomersTable.name] = name }
    }

    /** Consent evidence (P0 #5): the moment of opting in or out is kept alongside the flag. */
    fun updateOptIn(
        id: UUID,
        optIn: Boolean,
        at: Instant,
    ) {
        CustomersTable.update({ CustomersTable.id eq id }) {
            it[CustomersTable.optIn] = optIn
            if (optIn) it[optInAt] = Timestamps.toDb(at) else it[optOutAt] = Timestamps.toDb(at)
        }
    }

    /**
     * One points movement: a ledger row plus the cached balance, in the caller's transaction. The ledger
     * is the source of truth; `points_balance` mirrors its running sum (PRD §7.2).
     */
    @Suppress("LongParameterList")
    fun insertLedger(
        customerId: UUID,
        orderId: UUID?,
        type: String,
        delta: Long,
        balanceAfter: Long,
        staffId: UUID,
        at: Instant,
    ) {
        PointsLedgerTable.insert {
            it[PointsLedgerTable.customerId] = customerId
            it[PointsLedgerTable.orderId] = orderId
            it[PointsLedgerTable.type] = type
            it[PointsLedgerTable.delta] = delta
            it[PointsLedgerTable.balanceAfter] = balanceAfter
            it[PointsLedgerTable.staffId] = staffId
            it[createdAt] = Timestamps.toDb(at)
        }
    }

    fun updateBalanceAndAddVisit(
        id: UUID,
        balance: Long,
    ) {
        CustomersTable.update({ CustomersTable.id eq id }) {
            it[pointsBalance] = balance
            it[visits] = visits + 1
        }
    }

    /** Messages still waiting in the outbox are never sent to a customer who withdrew consent. */
    fun cancelQueuedWhatsApp(
        customerId: UUID,
        reason: String,
    ): Int =
        WaMessagesTable.update(
            { (WaMessagesTable.customerId eq customerId) and (WaMessagesTable.status eq "QUEUED") },
        ) {
            it[status] = "CANCELLED"
            it[errorReason] = reason
        }

    /** Newest first, keyed on the ledger id so new entries never shift a page the client already has. */
    fun ledger(
        customerId: UUID,
        limit: Int,
        beforeId: Long?,
    ): List<PointsEntryRecord> =
        PointsLedgerTable
            .selectAll()
            .where {
                val base = PointsLedgerTable.customerId eq customerId
                if (beforeId == null) base else base and (PointsLedgerTable.id less beforeId)
            }.orderBy(PointsLedgerTable.id to SortOrder.DESC)
            .limit(limit)
            .map {
                PointsEntryRecord(
                    id = it[PointsLedgerTable.id],
                    type = it[PointsLedgerTable.type],
                    delta = it[PointsLedgerTable.delta],
                    balanceAfter = it[PointsLedgerTable.balanceAfter],
                    orderId = it[PointsLedgerTable.orderId],
                    staffId = it[PointsLedgerTable.staffId],
                    createdAt = Timestamps.fromDb(it[PointsLedgerTable.createdAt]),
                )
            }

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun ResultRow.toCustomer() =
        CustomerRecord(
            id = this[CustomersTable.id],
            name = this[CustomersTable.name],
            phone = this[CustomersTable.phone],
            phoneDigits = this[CustomersTable.phoneDigits],
            points = this[CustomersTable.pointsBalance],
            optIn = this[CustomersTable.optIn],
            visits = this[CustomersTable.visits],
            homeBranchId = this[CustomersTable.homeBranchId],
            optInAt = this[CustomersTable.optInAt]?.let(Timestamps::fromDb),
        )
}
