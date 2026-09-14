package id.primawash.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.UnauthenticatedException
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

data class AccessClaims(
    val staffId: UUID,
    val sessionId: UUID,
    val deviceId: UUID,
    val branchId: UUID,
    val role: String,
)

/**
 * JWT access tokens (PRD §12.1): HS256, 60 minutes, claims `sub`, `role`, `branchId`, `deviceId`,
 * `sid`. The token alone is never trusted for long-lived state — the session row is checked on every
 * request — but its signature and expiry are checked first so a forged token never reaches the
 * database.
 */
class AccessTokens(
    secret: String,
    private val clock: Clock,
) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier =
        (JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE) as com.auth0.jwt.JWTVerifier.BaseVerification)
            .build(clock)

    fun issue(claims: AccessClaims): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(claims.staffId.toString())
            .withClaim(CLAIM_ROLE, claims.role)
            .withClaim(CLAIM_BRANCH, claims.branchId.toString())
            .withClaim(CLAIM_DEVICE, claims.deviceId.toString())
            .withClaim(CLAIM_SESSION, claims.sessionId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(TTL)))
            .sign(algorithm)
    }

    fun verify(token: String): AccessClaims {
        val decoded =
            try {
                verifier.verify(token)
            } catch (_: TokenExpiredException) {
                throw UnauthenticatedException(ErrorCodes.TOKEN_EXPIRED, SESSION_ENDED)
            } catch (_: JWTVerificationException) {
                throw UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, SESSION_ENDED)
            }
        return runCatching {
            AccessClaims(
                staffId = UUID.fromString(decoded.subject),
                sessionId = UUID.fromString(decoded.getClaim(CLAIM_SESSION).asString()),
                deviceId = UUID.fromString(decoded.getClaim(CLAIM_DEVICE).asString()),
                branchId = UUID.fromString(decoded.getClaim(CLAIM_BRANCH).asString()),
                role = requireNotNull(decoded.getClaim(CLAIM_ROLE).asString()),
            )
        }.getOrElse { throw UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, SESSION_ENDED) }
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(60)
        const val SESSION_ENDED = "Sesi berakhir — silakan login ulang dengan PIN."
        private const val ISSUER = "pwl-cashier"
        private const val AUDIENCE = "pwl-cashier-tablet"
        private const val CLAIM_ROLE = "role"
        private const val CLAIM_BRANCH = "branchId"
        private const val CLAIM_DEVICE = "deviceId"
        private const val CLAIM_SESSION = "sid"
    }
}
