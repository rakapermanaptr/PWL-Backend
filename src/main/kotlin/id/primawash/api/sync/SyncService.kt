package id.primawash.api.sync

import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.branch.BranchRecord
import id.primawash.api.branch.BranchService
import id.primawash.api.catalog.CatalogService
import id.primawash.api.catalog.LoyaltyRateRecord
import id.primawash.api.catalog.RewardRecord
import id.primawash.api.catalog.ServiceRecord
import id.primawash.api.common.Pagination
import id.primawash.api.common.ValidationException
import id.primawash.api.customer.CustomerRecord
import id.primawash.api.customer.CustomerService
import id.primawash.api.db.TransactionRunner
import id.primawash.api.order.OrderRecord
import id.primawash.api.order.OrderService
import id.primawash.api.shift.ShiftService
import id.primawash.api.shift.ShiftView
import id.primawash.api.staff.StaffRecord
import id.primawash.api.staff.StaffService
import java.util.UUID

/** Rows a tablet caches in Room, grouped per collection. */
data class SyncData(
    val branches: List<BranchRecord>,
    val staff: List<StaffRecord>,
    val services: List<ServiceRecord>,
    val rewards: List<RewardRecord>,
    val loyaltyRates: List<LoyaltyRateRecord>,
    val customers: List<CustomerRecord>,
    val orders: List<OrderRecord>,
    val shifts: List<ShiftView>,
)

data class Bootstrap(
    val data: SyncData,
    val cursor: String,
)

data class Changes(
    val data: SyncData,
    val nextCursor: String,
    val hasMore: Boolean,
)

/**
 * The tablet cache (PRD §8.10): a snapshot to start from, then the rows changed since a cursor. The client
 * upserts whatever arrives — a row may arrive twice, but a committed change never goes missing (V5).
 *
 * Scope follows the session: orders are the tablet's branch; a cashier's staff and shifts are their
 * branch's, an owner's are every branch's. Customers, branches and the catalog are global.
 */
class SyncService(
    private val tx: TransactionRunner,
    private val repository: SyncRepository,
    private val branches: BranchService,
    private val staff: StaffService,
    private val catalog: CatalogService,
    private val customers: CustomerService,
    private val orders: OrderService,
    private val shifts: ShiftService,
) {
    /**
     * `GET /sync/bootstrap`: branches, staff (public fields), the whole catalog (inactive rows included, so
     * the client can hide them), the current loyalty rate, customers, the branch's active orders (not yet
     * `SELESAI`, plus the last 7 days) and the latest shift per branch in scope. The cursor is taken before
     * anything is read, so a change committed during the snapshot is repeated by the next `changes` call.
     */
    suspend fun bootstrap(principal: StaffPrincipal): Bootstrap =
        tx {
            val watermark = repository.watermark()
            val scope = scope(principal)
            val data =
                SyncData(
                    branches = branches.findAll(),
                    staff = staff.findVisible(scope),
                    services = catalog.allServices(),
                    rewards = catalog.allRewards(),
                    loyaltyRates = listOfNotNull(catalog.rateNow()),
                    customers = customers.findAll(),
                    orders = orders.activeOrders(principal.branchId),
                    shifts = shifts.latestViews(scope?.let(::listOf)),
                )
            Bootstrap(data, SyncCursor.complete(watermark).encode())
        }

    /**
     * `GET /sync/changes?since=`: at most [PAGE_SIZE] changed rows across all collections, in feed order.
     * `hasMore` means call again at once with `nextCursor`; the round's watermark is the one read on its
     * first page, so rows that commit between pages are delivered by the next round.
     */
    suspend fun changes(
        principal: StaffPrincipal,
        since: String,
    ): Changes {
        val cursor = SyncCursor.decode(since)
        return tx {
            val now = repository.watermark()
            val roundWatermark = cursor.roundWatermark ?: now
            val scope = scope(principal)
            val keys = repository.changedKeys(cursor.since, cursor.after, principal.branchId, scope, PAGE_SIZE + 1)
            val page = keys.take(PAGE_SIZE)
            val hasMore = keys.size > PAGE_SIZE
            val next =
                if (hasMore) {
                    SyncCursor(since = cursor.since, roundWatermark = roundWatermark, after = page.last())
                } else {
                    SyncCursor.complete(roundWatermark)
                }
            Changes(load(page), next.encode(), hasMore)
        }
    }

    private fun load(keys: List<ChangeKey>): SyncData {
        val ids = keys.groupBy({ it.collection }) { it.id }

        fun of(collection: SyncCollection): Set<UUID> = ids[collection].orEmpty().toSet()

        val branchIds = of(SyncCollection.BRANCHES)
        return SyncData(
            branches = if (branchIds.isEmpty()) emptyList() else branches.findAll().filter { it.id in branchIds },
            staff = staff.findByIds(of(SyncCollection.STAFF)),
            services = catalog.findServices(of(SyncCollection.SERVICES)).values.toList(),
            rewards = catalog.findRewards(of(SyncCollection.REWARDS)),
            loyaltyRates = catalog.findRates(of(SyncCollection.LOYALTY_RATES)),
            customers = customers.findByIds(of(SyncCollection.CUSTOMERS)),
            orders = orders.findByIds(of(SyncCollection.ORDERS)),
            shifts = shifts.findViews(of(SyncCollection.SHIFTS)),
        )
    }

    private fun scope(principal: StaffPrincipal): UUID? = if (principal.isOwner) null else principal.branchId

    companion object {
        const val PAGE_SIZE = 500
    }
}

/**
 * Opaque to the client. A complete cursor is a watermark; a continuation also carries the round's lower
 * bound and the last key served, so the next page resumes exactly after it.
 */
internal data class SyncCursor(
    val since: String,
    val roundWatermark: String?,
    val after: ChangeKey?,
) {
    fun encode(): String =
        Pagination.encode(
            if (after == null) {
                "$VERSION|$since"
            } else {
                listOf(VERSION, since, roundWatermark, after.xid, after.collection.key, after.id).joinToString("|")
            },
        )

    companion object {
        private const val VERSION = "v1"
        private const val COMPLETE_PARTS = 2
        private const val CONTINUATION_PARTS = 6
        private const val XID_PART = 3
        private const val COLLECTION_PART = 4
        private const val ID_PART = 5
        private val XID = Regex("^[0-9]{1,20}$")

        fun complete(watermark: String) = SyncCursor(since = watermark, roundWatermark = null, after = null)

        fun decode(raw: String): SyncCursor {
            val parts = Pagination.decode(raw)?.split("|") ?: throw invalid()
            if (parts.size == COMPLETE_PARTS) {
                val (version, since) = parts
                if (version != VERSION || !since.matches(XID)) throw invalid()
                return complete(since)
            }
            if (parts.size != CONTINUATION_PARTS || parts.first() != VERSION) throw invalid()
            val (_, since, round) = parts
            val xid = parts[XID_PART]
            val collection = SyncCollection.ofKey(parts[COLLECTION_PART]) ?: throw invalid()
            val id = runCatching { UUID.fromString(parts[ID_PART]) }.getOrNull() ?: throw invalid()
            if (listOf(since, round, xid).any { !it.matches(XID) }) throw invalid()
            return SyncCursor(since, round, ChangeKey(xid, collection, id))
        }

        private fun invalid() = ValidationException("Cursor tidak valid — muat ulang data dari awal (bootstrap).")
    }
}
