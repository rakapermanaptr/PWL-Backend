package id.primawash.api.auth

import id.primawash.api.branch.toDto
import id.primawash.api.common.Requests
import id.primawash.api.plugins.devicePrincipal
import id.primawash.api.plugins.staffPrincipal
import id.primawash.api.staff.toDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/**
 * Endpoints authenticated by the device token (PRD §8.1 "D"). Bodies here carry PINs and tokens:
 * nothing under `/auth` is logged beyond method, path and status.
 */
fun Route.deviceAuthRoutes(service: AuthService) {
    get("/login-options") {
        val options = service.loginOptions(call.devicePrincipal())
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

    post("/auth/pin-login") {
        val device = call.devicePrincipal()
        val body = call.receive<PinLoginRequest>()
        // A malformed branch id is the same answer as an unknown one: "Pilih cabang perangkat dulu."
        val branchId = body.branchId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        call.respondSession(service.pinLogin(device, branchId, body.pin))
    }

    post("/auth/refresh") {
        val device = call.devicePrincipal()
        call.respondSession(service.refresh(device, call.receive<RefreshRequest>().refreshToken))
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

/** An unknown or malformed staff id both mean "no staff chosen" (`VerifyStaffPinUseCase`). */
private fun String?.toUuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun ApplicationCall.respondSession(session: IssuedSession) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(
        TokenResponse(
            accessToken = session.accessToken,
            accessTokenExpiresIn = session.accessTokenExpiresIn,
            refreshToken = session.refreshToken,
            refreshTokenExpiresIn = session.refreshTokenExpiresIn,
            context = session.context.toDto(),
        ),
    )
}

private fun SessionContext.toDto() = SessionContextDto(staff.toDto(), branch.toDto())
