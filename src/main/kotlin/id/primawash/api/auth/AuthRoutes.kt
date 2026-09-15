package id.primawash.api.auth

import id.primawash.api.branch.toDto
import id.primawash.api.common.Requests
import id.primawash.api.plugins.APP_VERSION_HEADER
import id.primawash.api.plugins.DEVICE_ID_HEADER
import id.primawash.api.plugins.PIN_LOGIN_RATE_LIMIT
import id.primawash.api.plugins.staffPrincipal
import id.primawash.api.staff.toDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/**
 * The login screen's endpoints. Public — there is no device activation step (owner decision, see
 * `docs/prd-gaps-m1.md`); the app identifies the tablet with the installation id it generates on first
 * launch, sent as `X-Device-Id`. Bodies here carry PINs and tokens: nothing under `/auth` is logged
 * beyond method, path and status.
 */
fun Route.publicAuthRoutes(service: AuthService) {
    get("/login-options") {
        val deviceId = Requests.uuidOrNull(call.request.headers[DEVICE_ID_HEADER], DEVICE_ID_HEADER)
        val options = service.loginOptions(deviceId)
        call.respond(
            LoginOptionsResponse(
                lastBranchId = options.lastBranchId?.toString(),
                branches =
                    options.branches.map { option ->
                        LoginBranchDto(
                            id = option.branch.id.toString(),
                            code = option.branch.code,
                            name = option.branch.name,
                            address = option.branch.address,
                            hours = option.branch.hours,
                            active = option.branch.active,
                            cashierNames = option.cashierNames,
                            shift =
                                LoginShiftDto(
                                    option.latestShift?.open == true,
                                    option.latestShift?.takeIf { it.open }?.openedByName,
                                ),
                        )
                    },
            ),
        )
    }

    // Attempts per network address are capped: the per-tablet lock alone can be dodged with new ids.
    rateLimit(PIN_LOGIN_RATE_LIMIT) {
        post("/auth/pin-login") {
            val deviceId = call.requiredDeviceId()
            val body = call.receive<PinLoginRequest>()
            // A malformed branch id is the same answer as an unknown one: "Pilih cabang perangkat dulu."
            val branchId = body.branchId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val appVersion = call.request.headers[APP_VERSION_HEADER]?.take(MAX_APP_VERSION_LENGTH)
            call.respondSession(service.pinLogin(deviceId, appVersion, branchId, body.pin))
        }
    }

    post("/auth/refresh") {
        val deviceId = call.requiredDeviceId()
        call.respondSession(service.refresh(deviceId, call.receive<RefreshRequest>().refreshToken))
    }
}

/** Endpoints authenticated by a staff access token (PRD §8.1 "K/O"). */
fun Route.authRoutes(service: AuthService) {
    post("/auth/verify-pin") {
        val principal = call.staffPrincipal()
        val body = call.receive<StaffPinRequest>()
        val verified = service.verifyPin(principal, body.staffId.toUuidOrNull(), body.pin)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(VerifyPinResponse(verified.staff.toDto(), verified.staffProof, verified.expiresIn))
    }

    post("/auth/switch-staff") {
        val principal = call.staffPrincipal()
        val body = call.receive<StaffPinRequest>()
        call.respondSession(service.switchStaff(principal, body.staffId.toUuidOrNull(), body.pin))
    }

    post("/auth/switch-branch") {
        val principal = call.staffPrincipal()
        val branchId = Requests.uuid(call.receive<SwitchBranchRequest>().branchId, "branchId")
        val result = service.switchBranch(principal, branchId)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            SwitchBranchResponse(
                changed = result.session != null,
                accessToken = result.session?.accessToken,
                accessTokenExpiresIn = result.session?.accessTokenExpiresIn,
                refreshToken = result.session?.refreshToken,
                refreshTokenExpiresIn = result.session?.refreshTokenExpiresIn,
                context = result.context.toDto(),
            ),
        )
    }

    post("/auth/logout") {
        call.respond(LogoutResponse(service.logout(call.staffPrincipal()).toString()))
    }

    get("/me") {
        call.respond(service.context(call.staffPrincipal()).toDto())
    }
}

private const val MAX_APP_VERSION_LENGTH = 32

private fun ApplicationCall.requiredDeviceId(): UUID =
    Requests.uuid(request.headers[DEVICE_ID_HEADER], DEVICE_ID_HEADER)

/** An unknown or malformed staff id both mean "no staff chosen" (`VerifyStaffPinUseCase`). */
private fun String?.toUuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun ApplicationCall.respondSession(session: IssuedSession) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(session.toDto())
}

fun IssuedSession.toDto() =
    TokenResponse(
        accessToken = accessToken,
        accessTokenExpiresIn = accessTokenExpiresIn,
        refreshToken = refreshToken,
        refreshTokenExpiresIn = refreshTokenExpiresIn,
        context = context.toDto(),
    )

private fun SessionContext.toDto() = SessionContextDto(staff.toDto(), branch.toDto())
