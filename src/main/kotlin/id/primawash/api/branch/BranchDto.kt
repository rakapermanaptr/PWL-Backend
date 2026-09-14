package id.primawash.api.branch

import kotlinx.serialization.Serializable

@Serializable
data class BranchDto(
    val id: String,
    val code: String,
    val name: String,
    val address: String,
    val phone: String,
    val hours: String,
    val dailyTarget: Long,
    val active: Boolean,
)

@Serializable
data class BranchListResponse(
    val items: List<BranchDto>,
)

@Serializable
data class ShiftSummaryDto(
    val id: String,
    val open: Boolean,
    val openedByName: String,
    val openedAt: String,
    val closedAt: String?,
)

@Serializable
data class BranchOverviewDto(
    val branch: BranchDto,
    val revenue: Long,
    val txCount: Int,
    /** Revenue ÷ daily target, 0–n; the Branch page draws the progress bar from it. */
    val targetRatio: Double,
    val latestShift: ShiftSummaryDto?,
    val activeCashiers: List<String>,
)

@Serializable
data class BranchOverviewResponse(
    val date: String,
    val items: List<BranchOverviewDto>,
)

@Serializable
data class UpdateBranchRequest(
    val dailyTarget: Long? = null,
    val active: Boolean? = null,
)

@Serializable
data class BranchChangeResponse(
    val branch: BranchDto,
    val changed: Boolean,
)
