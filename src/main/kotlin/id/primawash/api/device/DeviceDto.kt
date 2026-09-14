package id.primawash.api.device

import kotlinx.serialization.Serializable

@Serializable
data class DeviceDto(
    /** The installation id the app sends as `X-Device-Id`. */
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val lastBranchId: String?,
    val firstSeenAt: String,
    val lastSeenAt: String?,
    val pendingCount: Int,
    val revokedAt: String?,
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
