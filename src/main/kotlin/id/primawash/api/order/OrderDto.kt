package id.primawash.api.order

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class OrderItemDto(
    val serviceId: String,
    val name: String,
    val qty: Double,
    val unit: String,
    val unitPrice: Long,
    val subtotal: Long,
)

@Serializable
data class OrderDto(
    val id: String,
    val number: String,
    val branchId: String,
    val businessDate: String,
    val customerId: String?,
    val customerName: String,
    val customerPhone: String,
    val items: List<OrderItemDto>,
    val note: String,
    val subtotal: Long,
    val discount: Long,
    val total: Long,
    val rewardId: String?,
    val rewardName: String?,
    val redeemedPoints: Long,
    val earnedPoints: Long,
    val payment: String,
    val status: String,
    val waStatus: String,
    val capturedAt: String,
    val createdAt: String,
    val statusChangedAt: String,
    val shiftId: String,
    val staffId: String,
    val source: String,
    val flags: List<String>,
    val version: Int,
)

@Serializable
data class CartItemRequest(
    val serviceId: String? = null,
    /** A JSON number; parsed as a decimal so 4.5 kg never passes through floating point. */
    val qty: JsonPrimitive? = null,
    val unitPrice: Long? = null,
)

@Serializable
data class PlaceOrderRequest(
    val clientTxId: String? = null,
    val customerId: String? = null,
    val items: List<CartItemRequest>? = null,
    val rewardId: String? = null,
    val payment: String? = null,
    val note: String? = null,
    val expectedTotal: Long? = null,
)

@Serializable
data class CustomerBalanceDto(
    val id: String,
    val points: Long,
    val visits: Int,
)

@Serializable
data class PlaceOrderResponse(
    val order: OrderDto,
    val customer: CustomerBalanceDto?,
)

@Serializable
data class OrderCountsDto(
    @SerialName("ALL") val all: Long,
    @SerialName("DITERIMA") val diterima: Long,
    @SerialName("PROSES") val proses: Long,
    @SerialName("SIAP") val siap: Long,
    @SerialName("SELESAI") val selesai: Long,
)

@Serializable
data class OrderPageResponse(
    val items: List<OrderDto>,
    val nextCursor: String?,
    val counts: OrderCountsDto,
)

@Serializable
data class OrderEventDto(
    val id: Long,
    val type: String,
    val fromStatus: String?,
    val toStatus: String?,
    val staffId: String?,
    val deviceId: String?,
    val createdAt: String,
    val payload: JsonObject,
)

@Serializable
data class OrderDetailResponse(
    val order: OrderDto,
    val events: List<OrderEventDto>,
)

@Serializable
data class AdvanceOrderRequest(
    val fromStatus: String? = null,
)

@Serializable
data class AdvanceOrderResponse(
    val result: String,
    val order: OrderDto,
    val notificationQueued: Boolean,
)

@Serializable
data class NextNumberResponse(
    val number: String,
    val businessDate: String,
)

@Serializable
data class SyncItemRequest(
    val serviceId: String? = null,
    val name: String? = null,
    val qty: JsonPrimitive? = null,
    val unit: String? = null,
    val unitPrice: Long? = null,
)

@Serializable
data class NewCustomerRequest(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val optIn: Boolean? = null,
)

@Serializable
data class SyncTransactionRequest(
    val clientTxId: String? = null,
    val capturedAt: String? = null,
    val shiftId: String? = null,
    val staffId: String? = null,
    val customerId: String? = null,
    val newCustomer: NewCustomerRequest? = null,
    val items: List<SyncItemRequest>? = null,
    val rewardId: String? = null,
    val payment: String? = null,
    val note: String? = null,
)

@Serializable
data class SyncOrdersRequest(
    val transactions: List<SyncTransactionRequest>? = null,
)

@Serializable
data class SyncErrorDto(
    val code: String,
    val message: String,
)

@Serializable
data class SyncResultDto(
    val clientTxId: String?,
    val status: String,
    val order: OrderDto?,
    val error: SyncErrorDto?,
    /** For a customer registered offline: the tablet's id and the server id it maps to. */
    val customerIdMapping: CustomerIdMappingDto?,
)

@Serializable
data class CustomerIdMappingDto(
    val clientId: String,
    val serverId: String,
)

@Serializable
data class SyncSummaryDto(
    val created: Int,
    val duplicates: Int,
    val rejected: Int,
    val total: Long,
    val points: Long,
)

@Serializable
data class SyncOrdersResponse(
    val results: List<SyncResultDto>,
    val summary: SyncSummaryDto,
)
