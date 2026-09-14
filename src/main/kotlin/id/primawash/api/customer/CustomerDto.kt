package id.primawash.api.customer

import kotlinx.serialization.Serializable

@Serializable
data class CustomerDto(
    val id: String,
    val name: String,
    val phone: String,
    val points: Long,
    val optIn: Boolean,
    val visits: Int,
    val homeBranchId: String,
)

@Serializable
data class CustomerPageResponse(
    val items: List<CustomerDto>,
    val nextCursor: String?,
)

@Serializable
data class RegisterCustomerRequest(
    /** Optional client-generated UUID, so a customer registered offline keeps one identity. */
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val optIn: Boolean? = null,
)

@Serializable
data class CustomerResponse(
    val customer: CustomerDto,
)

@Serializable
data class UpdateCustomerRequest(
    val name: String? = null,
    val optIn: Boolean? = null,
)

@Serializable
data class CustomerChangeResponse(
    val customer: CustomerDto,
    val changed: Boolean,
)

@Serializable
data class PointsEntryDto(
    val id: Long,
    val type: String,
    val delta: Long,
    val balanceAfter: Long,
    val orderId: String? = null,
    val staffId: String? = null,
    val createdAt: String,
)

@Serializable
data class PointsPageResponse(
    val items: List<PointsEntryDto>,
    val nextCursor: String?,
)
