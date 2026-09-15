package id.primawash.api.catalog

import id.primawash.api.common.Timestamps
import id.primawash.api.db.LoyaltyRatesTable
import id.primawash.api.db.RewardsTable
import id.primawash.api.db.ServicePriceHistoryTable
import id.primawash.api.db.ServicesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ServiceRecord(
    val id: UUID,
    val category: ServiceCategory,
    val name: String,
    val price: Long,
    val unit: String,
    val step: BigDecimal,
    val active: Boolean,
)

data class LoyaltyRateRecord(
    val id: UUID,
    val rupiahPerStep: Long,
    val pointsPerStep: Long,
    val effectiveFrom: Instant,
)

data class RewardRecord(
    val id: UUID,
    val name: String,
    val cost: Long,
    val value: Long,
    val note: String,
    val minSubtotal: Long?,
    val usedCount: Long,
    val active: Boolean,
)

/** Exposed queries for the global price list, loyalty rate history and reward catalog. */
@Suppress("TooManyFunctions")
class CatalogRepository {
    // ---- Services ------------------------------------------------------------------------------

    fun findServices(includeInactive: Boolean): List<ServiceRecord> =
        ServicesTable
            .selectAll()
            .let { query -> if (includeInactive) query else query.where { ServicesTable.active eq true } }
            .orderBy(ServicesTable.sortOrder to SortOrder.ASC, ServicesTable.name to SortOrder.ASC)
            .map { it.toService() }

    fun findService(
        id: UUID,
        forUpdate: Boolean = false,
    ): ServiceRecord? =
        ServicesTable
            .selectAll()
            .where { ServicesTable.id eq id }
            .lockedIf(forUpdate)
            .firstOrNull()
            ?.toService()

    fun serviceNameExists(name: String): Boolean =
        ServicesTable
            .select(ServicesTable.id)
            .where { ServicesTable.name.lowerCase() eq name.lowercase() }
            .limit(1)
            .any()

    fun insertService(
        id: UUID,
        category: ServiceCategory,
        name: String,
        price: Long,
        unit: String,
        step: BigDecimal,
    ) {
        val nextSort = nextSortOrder(ServicesTable.sortOrder) { ServicesTable.select(it) }
        ServicesTable.insert {
            it[ServicesTable.id] = id
            it[ServicesTable.category] = category.name
            it[ServicesTable.name] = name
            it[ServicesTable.price] = price
            it[ServicesTable.unit] = unit
            it[ServicesTable.step] = step
            it[active] = true
            it[sortOrder] = nextSort
        }
    }

    fun updateServicePrice(
        id: UUID,
        oldPrice: Long,
        newPrice: Long,
        changedBy: UUID,
        at: Instant,
    ) {
        ServicesTable.update({ ServicesTable.id eq id }) { it[price] = newPrice }
        ServicePriceHistoryTable.insert {
            it[serviceId] = id
            it[ServicePriceHistoryTable.oldPrice] = oldPrice
            it[ServicePriceHistoryTable.newPrice] = newPrice
            it[ServicePriceHistoryTable.changedBy] = changedBy
            it[changedAt] = Timestamps.toDb(at)
        }
    }

    fun updateServiceActive(
        id: UUID,
        active: Boolean,
    ) {
        ServicesTable.update({ ServicesTable.id eq id }) { it[ServicesTable.active] = active }
    }

    fun findServicesByIds(ids: Collection<UUID>): List<ServiceRecord> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            ServicesTable
                .selectAll()
                .where {
                    ServicesTable.id inList ids
                }.map { it.toService() }
        }

    /**
     * The price of [serviceId] at [at], rebuilt from `service_price_history`: the old price of the first
     * change made after [at], or null when the price has not changed since — then the current price holds.
     */
    fun priceChangedAfter(
        serviceId: UUID,
        at: Instant,
    ): Long? =
        ServicePriceHistoryTable
            .select(ServicePriceHistoryTable.oldPrice)
            .where {
                (ServicePriceHistoryTable.serviceId eq serviceId) and
                    (ServicePriceHistoryTable.changedAt greater Timestamps.toDb(at))
            }.orderBy(ServicePriceHistoryTable.changedAt to SortOrder.ASC, ServicePriceHistoryTable.id to SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.get(ServicePriceHistoryTable.oldPrice)

    // ---- Loyalty rate --------------------------------------------------------------------------

    /** The rate effective at [at]: the newest row with `effective_from <= at` — history, not "latest". */
    fun rateEffectiveAt(at: Instant): LoyaltyRateRecord? =
        LoyaltyRatesTable
            .selectAll()
            .where { LoyaltyRatesTable.effectiveFrom lessEq Timestamps.toDb(at) }
            .orderBy(LoyaltyRatesTable.effectiveFrom to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toRate()

    /** The first rate ever saved — for a transaction captured before any rate row existed. */
    fun earliestRate(): LoyaltyRateRecord? =
        LoyaltyRatesTable
            .selectAll()
            .orderBy(LoyaltyRatesTable.effectiveFrom to SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.toRate()

    fun findRatesByIds(ids: Collection<UUID>): List<LoyaltyRateRecord> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            LoyaltyRatesTable.selectAll().where { LoyaltyRatesTable.id inList ids }.map { it.toRate() }
        }

    /** Rates are only ever inserted; the table rejects UPDATE and DELETE. */
    fun insertRate(
        rupiahPerStep: Long,
        pointsPerStep: Long,
        effectiveFrom: Instant,
        createdBy: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        LoyaltyRatesTable.insert {
            it[LoyaltyRatesTable.id] = id
            it[LoyaltyRatesTable.rupiahPerStep] = rupiahPerStep
            it[LoyaltyRatesTable.pointsPerStep] = pointsPerStep
            it[LoyaltyRatesTable.effectiveFrom] = Timestamps.toDb(effectiveFrom)
            it[LoyaltyRatesTable.createdBy] = createdBy
            it[createdAt] = Timestamps.toDb(effectiveFrom)
        }
        return id
    }

    // ---- Rewards -------------------------------------------------------------------------------

    fun findRewards(includeInactive: Boolean): List<RewardRecord> =
        RewardsTable
            .selectAll()
            .let { query -> if (includeInactive) query else query.where { RewardsTable.active eq true } }
            .orderBy(RewardsTable.sortOrder to SortOrder.ASC, RewardsTable.name to SortOrder.ASC)
            .map { it.toReward() }

    fun findReward(
        id: UUID,
        forUpdate: Boolean = false,
    ): RewardRecord? =
        RewardsTable
            .selectAll()
            .where { RewardsTable.id eq id }
            .lockedIf(forUpdate)
            .firstOrNull()
            ?.toReward()

    fun findRewardsByIds(ids: Collection<UUID>): List<RewardRecord> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            RewardsTable.selectAll().where { RewardsTable.id inList ids }.map {
                it
                    .toReward()
            }
        }

    fun incrementRewardUsedCount(id: UUID) {
        RewardsTable.update({ RewardsTable.id eq id }) { it[usedCount] = usedCount + 1 }
    }

    fun insertReward(
        id: UUID,
        name: String,
        cost: Long,
        value: Long,
        note: String,
        minSubtotal: Long?,
    ) {
        val nextSort = nextSortOrder(RewardsTable.sortOrder) { RewardsTable.select(it) }
        RewardsTable.insert {
            it[RewardsTable.id] = id
            it[RewardsTable.name] = name
            it[costPoints] = cost
            it[valueRupiah] = value
            it[RewardsTable.note] = note
            it[RewardsTable.minSubtotal] = minSubtotal
            it[usedCount] = 0
            it[active] = true
            it[sortOrder] = nextSort
        }
    }

    fun updateRewardActive(
        id: UUID,
        active: Boolean,
    ) {
        RewardsTable.update({ RewardsTable.id eq id }) { it[RewardsTable.active] = active }
    }

    // ---- Mapping -------------------------------------------------------------------------------

    private fun Query.lockedIf(forUpdate: Boolean): Query =
        if (forUpdate) forUpdate(ForUpdateOption.ForUpdate) else this

    private fun nextSortOrder(
        column: org.jetbrains.exposed.v1.core.Column<Int>,
        select: (org.jetbrains.exposed.v1.core.Expression<Int?>) -> Query,
    ): Int {
        val max = column.max()
        return (select(max).firstOrNull()?.get(max) ?: 0) + 1
    }

    private fun ResultRow.toService() =
        ServiceRecord(
            id = this[ServicesTable.id],
            category = ServiceCategory.valueOf(this[ServicesTable.category]),
            name = this[ServicesTable.name],
            price = this[ServicesTable.price],
            unit = this[ServicesTable.unit],
            step = this[ServicesTable.step],
            active = this[ServicesTable.active],
        )

    private fun ResultRow.toRate() =
        LoyaltyRateRecord(
            id = this[LoyaltyRatesTable.id],
            rupiahPerStep = this[LoyaltyRatesTable.rupiahPerStep],
            pointsPerStep = this[LoyaltyRatesTable.pointsPerStep],
            effectiveFrom = Timestamps.fromDb(this[LoyaltyRatesTable.effectiveFrom]),
        )

    private fun ResultRow.toReward() =
        RewardRecord(
            id = this[RewardsTable.id],
            name = this[RewardsTable.name],
            cost = this[RewardsTable.costPoints],
            value = this[RewardsTable.valueRupiah],
            note = this[RewardsTable.note],
            minSubtotal = this[RewardsTable.minSubtotal],
            usedCount = this[RewardsTable.usedCount],
            active = this[RewardsTable.active],
        )
}
