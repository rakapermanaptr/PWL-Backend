package id.primawash.api.plugins

import id.primawash.api.common.SecureTokens
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

val DEVICE_RATE_LIMIT = RateLimitName("per-device")
private const val REQUESTS_PER_MINUTE = 120

/**
 * General limit: 120 requests per minute per device (PRD §12.3). The key is the credential in
 * `Authorization` (one tablet, one token), falling back to the client address for unauthenticated
 * calls such as device activation. Counters live in memory — staging and production run a single
 * API instance; a second instance would need a shared store.
 */
fun Application.configureRateLimit() {
    install(RateLimit) {
        register(DEVICE_RATE_LIMIT) {
            rateLimiter(limit = REQUESTS_PER_MINUTE, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers[HttpHeaders.Authorization]?.let { SecureTokens.sha256Hex(it) }
                    ?: call.request.headers["X-Forwarded-For"]
                        ?.substringBefore(',')
                        ?.trim()
                    ?: call.request.local.remoteAddress
            }
        }
    }
}
