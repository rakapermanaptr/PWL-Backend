package id.primawash.api.shift

import id.primawash.api.auth.TokenResponse
import kotlinx.serialization.Serializable

/** PRD Lampiran A `CashEntryKind`. */
enum class CashEntryKind {
    OPENING,
    CASH_IN,
    CASH_OUT,
    SALE,
}

@Serializable
data class RecapDto(
    val expected: Long,
    val actual: Long,
    /** `actual − expected`; negative means cash is short. */
    val diff: Long,
)

@Serializable
data class ShiftDto(
    val id: String,
    val branchId: String,
    val open: Boolean,
    val openedByStaffId: String,
    val openedByName: String,
    val openedAt: String,
    val closedAt: String?,
    val closedByStaffId: String?,
    val openingCash: Long,
    val cashSales: Long,
    val transferSales: Long,
    val txCount: Int,
    val pointsIssued: Long,
    val countedCash: Long,
    /** Opening cash + cash sales + cash in − cash out (§9.3), computed now — also for a closed shift. */
    val expectedCash: Long,
    /** Frozen when the shift closed; `null` while it is open. */
    val recap: RecapDto?,
    val version: Int,
)

@Serializable
data class CashEntryDto(
    val id: String,
    val shiftId: String,
    val kind: String,
    val label: String,
    val note: String,
    val amount: Long,
    val orderId: String?,
    val staffId: String,
    val createdAt: String,
)

@Serializable
data class CurrentShiftResponse(
    val shift: ShiftDto?,
    val entries: List<CashEntryDto>,
)

@Serializable
data class ShiftListResponse(
    val items: List<ShiftDto>,
)

@Serializable
data class ShiftPageResponse(
    val items: List<ShiftDto>,
    val nextCursor: String?,
)

@Serializable
data class OpenShiftRequest(
    val openingCash: Long? = null,
    val staffProof: String? = null,
)

@Serializable
data class OpenShiftResponse(
    val shift: ShiftDto,
    /** New session for the staff member who opened the shift; `null` when that is the caller already. */
    val session: TokenResponse?,
)

@Serializable
data class CashEntryRequest(
    val direction: String? = null,
    val label: String? = null,
    val amount: Long? = null,
)

@Serializable
data class CashEntryResponse(
    val entry: CashEntryDto,
    val shift: ShiftDto,
)

@Serializable
data class CountedCashRequest(
    val countedCash: Long? = null,
)

@Serializable
data class ShiftResponse(
    val shift: ShiftDto,
)

@Serializable
data class CloseShiftResponse(
    val shift: ShiftDto,
    val recap: RecapDto,
)
