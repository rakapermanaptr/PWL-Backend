package id.primawash.api.plugins

import id.primawash.api.auth.AccessTokens
import id.primawash.api.auth.AuthService
import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.UnauthenticatedException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal

const val STAFF_AUTH = "staff"
const val DEVICE_ID_HEADER = "X-Device-Id"

/**
 * One credential: the staff access token (JWT) from PIN login, sent as `Authorization: Bearer …`,
 * whose session is re-checked in the database on every request. The login screen itself is public
 * (no device activation — see `docs/prd-gaps-m1.md`).
 *
 * Failures are thrown as domain exceptions so `StatusPages` renders the standard error envelope with
 * an Indonesian message instead of a bare `401`.
 */
fun Application.configureAuth(authService: AuthService) {
    install(Authentication) {
        register(StaffTokenProvider(authService))
    }
}

private class StaffTokenProvider(
    private val authService: AuthService,
) : AuthenticationProvider(Config(STAFF_AUTH)) {
    class Config(
        name: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val token =
            call.request.headers[HttpHeaders.Authorization]
                ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
                ?.substring(BEARER.length)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw sessionEnded()
        val principal = authService.authenticate(token)
        // X-Device-Id is optional here, but when a client sends it, it must be the token's tablet.
        call.request.headers[DEVICE_ID_HEADER]?.let { claimed ->
            if (!claimed.equals(principal.deviceId.toString(), ignoreCase = true)) throw sessionEnded()
        }
        context.principal(principal)
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

private fun sessionEnded() = UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, AccessTokens.SESSION_ENDED)

fun ApplicationCall.staffPrincipal(): StaffPrincipal = principal<StaffPrincipal>() ?: throw sessionEnded()
