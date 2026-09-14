package id.primawash.api.catalog

import kotlinx.serialization.Serializable

/** PRD Lampiran A; the label is what audit sentences show ("Kiloan Reguler"). */
enum class ServiceCategory(
    val label: String,
) {
    KILOAN_REGULER("Kiloan Reguler"),
    KILOAN_EXPRESS("Kiloan Express"),
    SATUAN("Satuan"),
    DRY_CLEAN("Dry Clean"),
}

/** Service units of PRD Lampiran A; `kg` steps by 0.5, the others by 1. */
val SERVICE_UNITS: Set<String> = setOf("kg", "pcs", "pasang", "m²")

@Serializable
data class ServiceDto(
    val id: String,
    val category: String,
    val name: String,
    val price: Long,
    val unit: String,
    val step: Double,
    val active: Boolean,
)

@Serializable
data class ServiceListResponse(
    val items: List<ServiceDto>,
)

@Serializable
data class CreateServiceRequest(
    val category: String? = null,
    val name: String? = null,
    val price: Long? = null,
    val unit: String? = null,
)

@Serializable
data class ServiceResponse(
    val service: ServiceDto,
)

@Serializable
data class ServiceChangeResponse(
    val service: ServiceDto,
    val changed: Boolean,
)

@Serializable
data class SavePricesRequest(
    val prices: Map<String, Long>? = null,
)

@Serializable
data class SavePricesResponse(
    val items: List<ServiceDto>,
    val changedCount: Int,
)

@Serializable
data class ToggleActiveRequest(
    val active: Boolean? = null,
)

@Serializable
data class LoyaltyRateDto(
    val id: String,
    val rupiahPerStep: Long,
    val pointsPerStep: Long,
    val effectiveFrom: String,
)

@Serializable
data class SaveRateRequest(
    val rupiahPerStep: Long? = null,
    val pointsPerStep: Long? = null,
)

@Serializable
data class RateChangeResponse(
    val rate: LoyaltyRateDto,
    val changed: Boolean,
)

@Serializable
data class RewardDto(
    val id: String,
    val name: String,
    val cost: Long,
    val value: Long,
    val note: String,
    val minSubtotal: Long? = null,
    val usedCount: Long,
    val active: Boolean,
)

@Serializable
data class RewardListResponse(
    val items: List<RewardDto>,
)

@Serializable
data class CreateRewardRequest(
    val name: String? = null,
    val cost: Long? = null,
    val value: Long? = null,
    val minSubtotal: Long? = null,
)

@Serializable
data class RewardResponse(
    val reward: RewardDto,
)

@Serializable
data class RewardChangeResponse(
    val reward: RewardDto,
    val changed: Boolean,
)
