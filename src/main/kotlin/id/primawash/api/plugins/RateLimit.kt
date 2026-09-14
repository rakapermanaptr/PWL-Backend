package id.primawash.api.plugins

import id.primawash.api.common.SecureTokens
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

val DEVICE_RATE_LIMIT = RateLimitName("per-device")
val PIN_LOGIN_RATE_LIMIT = RateLimitName("pin-login-per-address")
private const val REQUESTS_PER_MINUTE = 120

/**
 * PIN login attempts per network address in [PIN_LOGIN_WINDOW_MINUTES]. PIN login is public (no device
 * activation), and the per-device PIN lock can be dodged by inventing new installation ids; this cap
 * cannot. A shop's tablets share one address, so it is set well above what a busy counter needs.
 */
const val PIN_LOGINS_PER_ADDRESS = 20
private const val PIN_LOGIN_WINDOW_MINUTES = 5

/**
 * General limit: 120 requests per minute per device (PRD §12.3), keyed on the access token, else the
 * installation id, else the client address. Counters live in memory — staging and production run a
 * single API instance; a second instance would need a shared store.
 */
fun Application.configureRateLimit() {
    install(RateLimit) {
        register(DEVICE_RATE_LIMIT) {
            rateLimiter(limit = REQUESTS_PER_MINUTE, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers[HttpHeaders.Authorization]?.let { SecureTokens.sha256Hex(it) }
                    ?: call.request.headers[DEVICE_ID_HEADER]
                    ?: call.clientAddress()
            }
        }
        register(PIN_LOGIN_RATE_LIMIT) {
            rateLimiter(limit = PIN_LOGINS_PER_ADDRESS, refillPeriod = PIN_LOGIN_WINDOW_MINUTES.minutes)
            requestKey { call -> call.clientAddress() }
        }
    }
}

/**
 * The caller's address behind the platform load balancer. DigitalOcean App Platform sets
 * `do-connecting-ip`; otherwise the last `X-Forwarded-For` hop (added by the proxy, not the client),
 * otherwise the socket address.
 */
fun ApplicationCall.clientAddress(): String =
    request.headers["do-connecting-ip"]
        ?: request.headers["X-Forwarded-For"]
            ?.substringAfterLast(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress
