package id.primawash.api.staff

import kotlinx.serialization.Serializable

enum class Role(
    /** Word used inside audit sentences: "(kasir, Cabang Familia Urban)". */
    val auditLabel: String,
) {
    KASIR("kasir"),
    OWNER("owner"),
}

/** A staff account as the owner and the session context see it — never with PIN material. */
@Serializable
data class StaffDto(
    val id: String,
    val name: String,
    val shortName: String,
    val role: String,
    val branchId: String?,
    val active: Boolean,
    val lastLoginAt: String?,
)

/** What a cashier may see of the accounts that can work at their branch (PRD §8.3). */
@Serializable
data class StaffCandidateDto(
    val id: String,
    val name: String,
    val shortName: String,
    val role: String,
)

@Serializable
data class StaffListResponse(
    val items: List<StaffDto>,
)

@Serializable
data class StaffCandidateListResponse(
    val items: List<StaffCandidateDto>,
)

@Serializable
data class CreateStaffRequest(
    val name: String? = null,
    val pin: String? = null,
    val role: String? = null,
    val branchId: String? = null,
)

@Serializable
data class StaffResponse(
    val staff: StaffDto,
)

@Serializable
data class UpdateStaffRequest(
    val active: Boolean? = null,
)

@Serializable
data class StaffChangeResponse(
    val staff: StaffDto,
    val changed: Boolean,
)

@Serializable
data class ResetPinResponse(
    val staff: StaffDto,
    val pin: String,
)
