package id.primawash.api.sync

import id.primawash.api.branch.BranchDto
import id.primawash.api.catalog.LoyaltyRateDto
import id.primawash.api.catalog.RewardDto
import id.primawash.api.catalog.ServiceDto
import id.primawash.api.customer.CustomerDto
import id.primawash.api.order.OrderDto
import id.primawash.api.shift.ShiftDto
import kotlinx.serialization.Serializable

/** What a tablet may cache of a staff account — never PIN data, never login times. */
@Serializable
data class SyncStaffDto(
    val id: String,
    val name: String,
    val shortName: String,
    val role: String,
    val branchId: String?,
    val active: Boolean,
)

@Serializable
data class SyncDataDto(
    val branches: List<BranchDto>,
    val staff: List<SyncStaffDto>,
    val services: List<ServiceDto>,
    val rewards: List<RewardDto>,
    val loyaltyRates: List<LoyaltyRateDto>,
    val customers: List<CustomerDto>,
    val orders: List<OrderDto>,
    val shifts: List<ShiftDto>,
)

@Serializable
data class BootstrapResponse(
    val data: SyncDataDto,
    /** Pass as `since` to the first `GET /sync/changes`. */
    val cursor: String,
)

@Serializable
data class ChangesResponse(
    val changes: SyncDataDto,
    val nextCursor: String,
    /** True: call again right away with `nextCursor` — this page was full. */
    val hasMore: Boolean,
)
