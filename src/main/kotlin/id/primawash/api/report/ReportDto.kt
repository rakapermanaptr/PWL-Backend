package id.primawash.api.report

import kotlinx.serialization.Serializable

/** An audit entry as the owner's log shows it (PRD §8.9); shared with `GET /audit`. */
@Serializable
data class AuditEntryDto(
    val id: Long,
    /** `null` = applies to every branch; such entries appear under any branch filter. */
    val branchId: String?,
    val staffId: String?,
    val actorName: String,
    val actionType: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val createdAt: String,
)

@Serializable
data class DashboardShiftDto(
    val id: String,
    val open: Boolean,
    val openedByName: String,
    val openedAt: String,
    val closedAt: String?,
)

@Serializable
data class DashboardBranchRowDto(
    val branchId: String,
    val code: String,
    val name: String,
    val active: Boolean,
    val revenue: Long,
    val txCount: Int,
    val readyForPickup: Int,
    val dailyTarget: Long,
    /** `revenue ÷ dailyTarget`, e.g. `1.12` for 112% of target; `null` when no target is set. */
    val targetRatio: Double?,
    val latestShift: DashboardShiftDto?,
)

@Serializable
data class ChartBranchRevenueDto(
    val branchId: String,
    val revenue: Long,
)

@Serializable
data class ChartDayDto(
    val date: String,
    val total: Long,
    val branches: List<ChartBranchRevenueDto>,
)

@Serializable
data class TopServiceDto(
    val name: String,
    val revenue: Long,
    /** Share of the period's item revenue, `0..1`. */
    val share: Double,
)

/**
 * Everything the owner's report page shows (PRD §9.4). Rates are `null` when there is nothing to
 * measure — an empty branch reports "unknown", never 0%.
 */
@Serializable
data class DashboardResponse(
    val date: String,
    /** `null` = every branch. */
    val branchId: String?,
    val periodDays: Int,
    val periodFrom: String,
    val periodTo: String,
    val auditRangeDays: Int,
    val revenueToday: Long,
    val txToday: Int,
    val revenueYesterday: Long,
    val readyForPickup: Int,
    val staleReady: Int,
    val optInRate: Double?,
    /** Delivered ÷ (delivered + failed) "siap diambil" messages; `null` until the M5 worker sends. */
    val deliveryRate: Double?,
    val redemptionRate: Double?,
    val pendingSync: Int,
    val branchRows: List<DashboardBranchRowDto>,
    val chart: List<ChartDayDto>,
    val topServices: List<TopServiceDto>,
    val audit: List<AuditEntryDto>,
    /** The window holds more entries than `audit` carries — the rest are behind `GET /audit`. */
    val auditTruncated: Boolean,
)
