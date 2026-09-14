package id.primawash.api.auth

import id.primawash.api.branch.BranchDto
import id.primawash.api.staff.StaffDto
import kotlinx.serialization.Serializable

@Serializable
data class PinLoginRequest(
    val branchId: String? = null,
    val pin: String? = null,
)

@Serializable
data class SessionContextDto(
    val staff: StaffDto,
    val branch: BranchDto,
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresIn: Long,
    val context: SessionContextDto,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String? = null,
)

@Serializable
data class StaffPinRequest(
    val staffId: String? = null,
    val pin: String? = null,
)

@Serializable
data class VerifyPinResponse(
    val staff: StaffDto,
    val staffProof: String,
    val expiresIn: Long,
)

@Serializable
data class SwitchBranchRequest(
    val branchId: String? = null,
)

@Serializable
data class SwitchBranchResponse(
    val changed: Boolean,
    val accessToken: String?,
    val accessTokenExpiresIn: Long?,
    val refreshToken: String?,
    val refreshTokenExpiresIn: Long?,
    val context: SessionContextDto,
)

@Serializable
data class LogoutResponse(
    val lastBranchId: String,
)

@Serializable
data class LoginShiftDto(
    val open: Boolean,
    val openedByName: String?,
)

@Serializable
data class LoginBranchDto(
    val id: String,
    val code: String,
    val name: String,
    val address: String,
    val hours: String,
    val active: Boolean,
    val cashierNames: List<String>,
    val shift: LoginShiftDto,
)

@Serializable
data class LoginOptionsResponse(
    val lastBranchId: String?,
    val branches: List<LoginBranchDto>,
)
