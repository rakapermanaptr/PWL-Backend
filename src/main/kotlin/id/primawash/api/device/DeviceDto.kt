package id.primawash.api.device

import kotlinx.serialization.Serializable

@Serializable
data class CreateActivationCodeRequest(
    val branchId: String? = null,
)

@Serializable
data class ActivationCodeResponse(
    /** `ABCD-EFGH`; shown once, only its keyed hash is stored. */
    val code: String,
    val branchId: String?,
    val expiresAt: String,
)

@Serializable
data class ActivateDeviceRequest(
    val code: String? = null,
    val name: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val lastBranchId: String?,
    val activatedAt: String,
    val lastSeenAt: String?,
    val pendingCount: Int,
    val revokedAt: String?,
)

@Serializable
data class ActivateDeviceResponse(
    /** Sent as `Authorization: Bearer <deviceToken>` on device endpoints; returned once. */
    val deviceToken: String,
    val device: DeviceDto,
)

@Serializable
data class DeviceListResponse(
    val items: List<DeviceDto>,
)

@Serializable
data class DeviceChangeResponse(
    val device: DeviceDto,
    val changed: Boolean,
)
