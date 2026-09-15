package id.primawash.api.catalog

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.Money
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.Quantity
import id.primawash.api.db.TransactionRunner
import id.primawash.api.db.translatingUniqueViolation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ServiceChange(
    val service: ServiceRecord,
    val changed: Boolean,
)

data class PriceListSave(
    val services: List<ServiceRecord>,
    val changedCount: Int,
)

data class RateChange(
    val rate: LoyaltyRateRecord,
    val changed: Boolean,
)

data class RewardChange(
    val reward: RewardRecord,
    val changed: Boolean,
)

/**
 * The global price list, loyalty rate and reward catalog (PRD §8.4). Every change applies to all
 * branches, so every audit row here has `branch_id = null`.
 */
@Suppress("TooManyFunctions")
class CatalogService(
    private val tx: TransactionRunner,
    private val repository: CatalogRepository,
    private val audit: AuditWriter,
    private val clock: Clock,
) {
    suspend fun listServices(includeInactive: Boolean): List<ServiceRecord> =
        tx { repository.findServices(includeInactive) }

    /**
     * `POST /services` (`AddServiceUseCase`): name required → price > 0 → name unique regardless of
     * case. `step` follows the unit — 0.5 for `kg`, 1 otherwise — and is never taken from the client.
     */
    suspend fun addService(
        category: ServiceCategory,
        name: String?,
        price: Long?,
        unit: String,
        actor: AuditActor,
    ): ServiceRecord {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isEmpty()) throw BusinessRuleException(ErrorCodes.NAME_REQUIRED, "Nama layanan wajib diisi.")
        if (price == null ||
            price <= 0
        ) {
            throw BusinessRuleException(ErrorCodes.PRICE_REQUIRED, "Harga per unit belum diisi.")
        }
        return translatingUniqueViolation(SERVICE_NAME_INDEX, ::serviceNameTaken) {
            tx {
                if (repository.serviceNameExists(cleanName)) throw serviceNameTaken()
                val id = UUID.randomUUID()
                repository.insertService(id, category, cleanName, price, unit, Quantity.stepFor(unit))
                audit.write(
                    AuditEntry(
                        branchId = null,
                        actor = actor,
                        type = AuditActionType.SERVICE_ADDED,
                        action = "Tambah layanan $cleanName — ${Money.formatRupiah(price)}/$unit (${category.label})",
                        entityType = SERVICE_ENTITY,
                        entityId = id.toString(),
                    ),
                )
                requireNotNull(repository.findService(id))
            }
        }
    }

    /**
     * `PATCH /services/prices` (`SavePriceListUseCase`): every price > 0, then every id must exist.
     * One transaction; each real change writes a `service_price_history` row and an audit row, then one
     * summary audit row. Existing orders are untouched — their prices are snapshots in `order_items`.
     * A save that changes nothing writes nothing.
     */
    suspend fun savePrices(
        prices: Map<UUID, Long>,
        actor: AuditActor,
    ): PriceListSave {
        if (prices.values.any { it <= 0 }) {
            throw BusinessRuleException(ErrorCodes.PRICE_REQUIRED, "Harga layanan tidak boleh kosong.")
        }
        return tx {
            val now = clock.instant()
            val current =
                prices.keys.sorted().associateWith { id ->
                    repository.findService(id, forUpdate = true) ?: throw serviceNotFound()
                }
            val changes = current.filter { (id, service) -> service.price != prices.getValue(id) }
            changes.forEach { (id, service) ->
                val newPrice = prices.getValue(id)
                repository.updateServicePrice(id, service.price, newPrice, requireNotNull(actor.staffId), now)
                audit.write(
                    AuditEntry(
                        branchId = null,
                        actor = actor,
                        type = AuditActionType.SERVICE_PRICE_CHANGED,
                        action =
                            "Ubah harga ${service.name}: ${Money.formatRupiah(service.price)} → " +
                                "${Money.formatRupiah(newPrice)} (semua cabang)",
                        entityType = SERVICE_ENTITY,
                        entityId = id.toString(),
                        metadata =
                            buildJsonObject {
                                put("old", service.price)
                                put("new", newPrice)
                            },
                    ),
                )
            }
            val services = repository.findServices(includeInactive = true)
            if (changes.isNotEmpty()) {
                audit.write(
                    AuditEntry(
                        branchId = null,
                        actor = actor,
                        type = AuditActionType.SERVICE_PRICE_CHANGED,
                        action = "Simpan price list — ${services.count { it.active }} layanan aktif di semua cabang",
                        entityType = SERVICE_ENTITY,
                        metadata = buildJsonObject { put("changed", changes.size) },
                    ),
                )
            }
            PriceListSave(services, changes.size)
        }
    }

    /** `PATCH /services/{id}` (`ToggleServiceUseCase`): an inactive service disappears from the POS. */
    suspend fun setServiceActive(
        id: UUID,
        active: Boolean,
        actor: AuditActor,
    ): ServiceChange =
        tx {
            val service = repository.findService(id, forUpdate = true) ?: throw serviceNotFound()
            if (service.active == active) return@tx ServiceChange(service, changed = false)
            repository.updateServiceActive(id, active)
            audit.write(
                AuditEntry(
                    branchId = null,
                    actor = actor,
                    type = AuditActionType.SERVICE_TOGGLED,
                    action =
                        "${if (active) "Aktifkan" else "Nonaktifkan"} layanan ${service.name} " +
                            "(${service.category.label}) — berlaku semua cabang",
                    entityType = SERVICE_ENTITY,
                    entityId = id.toString(),
                    metadata = buildJsonObject { put("active", active) },
                ),
            )
            ServiceChange(service.copy(active = active), changed = true)
        }

    /** `GET /loyalty/rate`: the rate in effect right now. */
    suspend fun currentRate(): LoyaltyRateRecord =
        tx { repository.rateEffectiveAt(clock.instant()) } ?: throw NotFoundException("Rate poin belum diatur.")

    /**
     * `PUT /loyalty/rate` (`SaveLoyaltyRateUseCase`): Rp ≥ 1.000 per step, then ≥ 1 point per step.
     * Stored as a new `loyalty_rates` row effective now, so it only applies to transactions captured
     * from now on (P0 #6); past and offline-captured transactions keep the rate of their `capturedAt`.
     */
    suspend fun saveRate(
        rupiahPerStep: Long?,
        pointsPerStep: Long?,
        actor: AuditActor,
    ): RateChange {
        if (rupiahPerStep == null || rupiahPerStep < MIN_RUPIAH_PER_STEP) {
            throw BusinessRuleException(ErrorCodes.RATE_INVALID, "Nominal belanja minimal Rp1.000.")
        }
        if (pointsPerStep == null || pointsPerStep < 1) {
            throw BusinessRuleException(ErrorCodes.RATE_INVALID, "Poin didapat minimal 1.")
        }
        return tx {
            val now = clock.instant()
            val current = repository.rateEffectiveAt(now)
            if (current != null && current.rupiahPerStep == rupiahPerStep && current.pointsPerStep == pointsPerStep) {
                return@tx RateChange(current, changed = false)
            }
            val id = repository.insertRate(rupiahPerStep, pointsPerStep, now, requireNotNull(actor.staffId))
            audit.write(
                AuditEntry(
                    branchId = null,
                    actor = actor,
                    type = AuditActionType.LOYALTY_RATE_CHANGED,
                    action =
                        "Simpan pengaturan loyalty — ${Money.formatRupiah(rupiahPerStep)} = " +
                            "${Money.grouped(pointsPerStep)} poin, berlaku semua cabang",
                    entityType = "loyalty_rate",
                    entityId = id.toString(),
                    metadata =
                        buildJsonObject {
                            current?.let {
                                put("oldRupiahPerStep", it.rupiahPerStep)
                                put("oldPointsPerStep", it.pointsPerStep)
                            }
                            put("rupiahPerStep", rupiahPerStep)
                            put("pointsPerStep", pointsPerStep)
                        },
                ),
            )
            RateChange(requireNotNull(repository.rateEffectiveAt(now)), changed = true)
        }
    }

    suspend fun listRewards(includeInactive: Boolean): List<RewardRecord> =
        tx { repository.findRewards(includeInactive) }

    /**
     * `POST /rewards` (`AddRewardUseCase`): name required → cost and value > 0. `minSubtotal` is the
     * optional minimum spend of trap T9, enforced when the reward is redeemed (M2). The note defaults
     * to "Setara Rp{value}".
     */
    suspend fun addReward(
        name: String?,
        cost: Long?,
        value: Long?,
        minSubtotal: Long?,
        actor: AuditActor,
    ): RewardRecord {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isEmpty()) throw BusinessRuleException(ErrorCodes.NAME_REQUIRED, "Nama reward wajib diisi.")
        val points = cost?.takeIf { it > 0 }
        val discount = value?.takeIf { it > 0 }
        if (points == null || discount == null) {
            throw BusinessRuleException(ErrorCodes.REWARD_INVALID, "Poin dan nilai diskon wajib diisi.")
        }
        val minimum = minSubtotal?.takeIf { it > 0 }
        return tx {
            val id = UUID.randomUUID()
            repository.insertReward(id, cleanName, points, discount, "Setara ${Money.formatRupiah(discount)}", minimum)
            audit.write(
                AuditEntry(
                    branchId = null,
                    actor = actor,
                    type = AuditActionType.REWARD_ADDED,
                    action =
                        "Tambah reward $cleanName — ${Money.grouped(
                            points,
                        )} poin, nilai ${Money.formatRupiah(discount)}" +
                            minimum?.let { " · minimum belanja ${Money.formatRupiah(it)}" }.orEmpty(),
                    entityType = REWARD_ENTITY,
                    entityId = id.toString(),
                ),
            )
            requireNotNull(repository.findReward(id))
        }
    }

    /** `PATCH /rewards/{id}` (`ToggleRewardUseCase`). */
    suspend fun setRewardActive(
        id: UUID,
        active: Boolean,
        actor: AuditActor,
    ): RewardChange =
        tx {
            val reward =
                repository.findReward(id, forUpdate = true) ?: throw NotFoundException("Reward tidak ditemukan.")
            if (reward.active == active) return@tx RewardChange(reward, changed = false)
            repository.updateRewardActive(id, active)
            audit.write(
                AuditEntry(
                    branchId = null,
                    actor = actor,
                    type = AuditActionType.REWARD_TOGGLED,
                    action =
                        "${if (active) "Aktifkan" else "Nonaktifkan"} reward ${reward.name} " +
                            "(${Money.grouped(reward.cost)} poin) — berlaku semua cabang",
                    entityType = REWARD_ENTITY,
                    entityId = id.toString(),
                    metadata = buildJsonObject { put("active", active) },
                ),
            )
            RewardChange(reward.copy(active = active), changed = true)
        }

    // ---- Used by OrderService and SyncService, inside their transaction ---------------------------

    fun findServices(ids: Collection<UUID>): Map<UUID, ServiceRecord> =
        repository.findServicesByIds(ids).associateBy { it.id }

    /** What [service] cost at [at] — the price an offline transaction should have used (PRD §11.2). */
    fun priceAt(
        service: ServiceRecord,
        at: Instant,
    ): Long = repository.priceChangedAfter(service.id, at) ?: service.price

    /**
     * The loyalty rate of a transaction captured at [at] (P0 #6): the rate row effective then, never the
     * current one. A capture older than every rate row uses the first rate.
     */
    fun rateAt(at: Instant): LoyaltyRateRecord =
        repository.rateEffectiveAt(at) ?: repository.earliestRate()
            ?: throw NotFoundException("Rate poin belum diatur.")

    fun lockReward(id: UUID): RewardRecord? = repository.findReward(id, forUpdate = true)

    fun recordRewardUse(id: UUID) = repository.incrementRewardUsedCount(id)

    fun allServices(): List<ServiceRecord> = repository.findServices(includeInactive = true)

    fun allRewards(): List<RewardRecord> = repository.findRewards(includeInactive = true)

    fun rateNow(): LoyaltyRateRecord? = repository.rateEffectiveAt(clock.instant())

    fun findRewards(ids: Collection<UUID>): List<RewardRecord> = repository.findRewardsByIds(ids)

    fun findRates(ids: Collection<UUID>): List<LoyaltyRateRecord> = repository.findRatesByIds(ids)

    companion object {
        private const val SERVICE_ENTITY = "service"
        private const val REWARD_ENTITY = "reward"
        private const val SERVICE_NAME_INDEX = "services_name_key"
        private const val MIN_RUPIAH_PER_STEP = 1_000L

        private fun serviceNotFound() = NotFoundException("Layanan tidak ditemukan.")

        private fun serviceNameTaken() =
            BusinessRuleException(ErrorCodes.SERVICE_NAME_TAKEN, "Layanan dengan nama ini sudah ada di price list.")
    }
}
