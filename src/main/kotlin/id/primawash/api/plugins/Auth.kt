package id.primawash.api.plugins

import id.primawash.api.auth.AccessTokens
import id.primawash.api.auth.AuthService
import id.primawash.api.auth.DevicePrincipal
import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.UnauthenticatedException
import id.primawash.api.device.DeviceService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal

const val STAFF_AUTH = "staff"
const val DEVICE_AUTH = "device"
const val DEVICE_ID_HEADER = "X-Device-Id"

/**
 * Two credentials, both sent as `Authorization: Bearer …` (PRD §8.1 example):
 *  - [DEVICE_AUTH]: the activated tablet's opaque device token — login options, PIN login, refresh.
 *  - [STAFF_AUTH]: a staff access token (JWT) whose session is re-checked in the database.
 *
 * Failures are thrown as domain exceptions so `StatusPages` renders the standard error envelope with
 * an Indonesian message instead of a bare `401`.
 */
fun Application.configureAuth(
    authService: AuthService,
    deviceService: DeviceService,
) {
    install(Authentication) {
        register(
            BearerTokenProvider(STAFF_AUTH) { call, token ->
                authService.authenticate(token).also {
                    call.requireMatchingDevice(it.deviceId.toString(), sessionEnded())
                }
            },
        )
        register(
            BearerTokenProvider(DEVICE_AUTH) { call, token ->
                deviceService.authenticate(token).also {
                    call.requireMatchingDevice(it.deviceId.toString(), DeviceService.deviceUnauthorized())
                }
            },
        )
    }
}

private class BearerTokenProvider(
    name: String,
    private val resolve: suspend (ApplicationCall, String) -> Any,
) : AuthenticationProvider(Config(name)) {
    class Config(
        name: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val header = context.call.request.headers[HttpHeaders.Authorization]
        val token =
            header
                ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
                ?.substring(BEARER.length)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (token == null) {
            throw if (name == DEVICE_AUTH) DeviceService.deviceUnauthorized() else sessionEnded()
        }
        context.principal(resolve(context.call, token))
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

/** `X-Device-Id` is optional, but when a client sends it, it must be the device the token belongs to. */
private fun ApplicationCall.requireMatchingDevice(
    deviceId: String,
    failure: UnauthenticatedException,
) {
    val claimed = request.headers[DEVICE_ID_HEADER] ?: return
    if (!claimed.equals(deviceId, ignoreCase = true)) throw failure
}

private fun sessionEnded() = UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, AccessTokens.SESSION_ENDED)

fun ApplicationCall.staffPrincipal(): StaffPrincipal = principal<StaffPrincipal>() ?: throw sessionEnded()

fun ApplicationCall.devicePrincipal(): DevicePrincipal =
    principal<DevicePrincipal>() ?: throw DeviceService.deviceUnauthorized()
