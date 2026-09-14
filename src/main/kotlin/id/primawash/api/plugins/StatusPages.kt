package id.primawash.api.plugins

import id.primawash.api.common.AppUpdateRequiredException
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.common.DomainException
import id.primawash.api.common.ErrorBody
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.ErrorEnvelope
import id.primawash.api.common.ForbiddenException
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.PinLockedException
import id.primawash.api.common.UnauthenticatedException
import id.primawash.api.common.ValidationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private const val INTERNAL_ERROR_MESSAGE = "Terjadi gangguan di server. Coba lagi sebentar lagi."
private const val ROUTE_NOT_FOUND_MESSAGE = "Alamat yang diminta tidak ada di server."
private const val RATE_LIMITED_MESSAGE =
    "Terlalu banyak permintaan dari perangkat ini — tunggu sebentar lalu coba lagi."

/**
 * The single place where an error becomes an HTTP response (PRD §6.2). Routes never build error
 * responses by hand, and a business rule never becomes a `5xx` — the client treats `5xx` as
 * retryable.
 */
fun Application.configureStatusPages() {
    val logger = LoggerFactory.getLogger("StatusPages")

    install(StatusPages) {
        exception<DomainException> { call, cause ->
            if (cause is PinLockedException) {
                call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            }
            call.respondError(cause.statusCode(), cause.code, cause.message, cause)
        }

        exception<BadRequestException> { call, cause ->
            // Malformed JSON or a wrong field type — never echo the body back, it may hold a PIN.
            logger.info("{} body tidak valid di {}", call.callId, call.request.path())
            call.respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.VALIDATION_ERROR,
                "Data yang dikirim tidak lengkap atau formatnya salah.",
                null,
            )
        }

        exception<Throwable> { call, cause ->
            val path = call.request.path()
            logger.error("{} gagal di {}: {}", call.callId, path, cause.toString(), cause)
            call.respondError(
                HttpStatusCode.InternalServerError,
                ErrorCodes.INTERNAL_ERROR,
                INTERNAL_ERROR_MESSAGE,
                null,
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, ROUTE_NOT_FOUND_MESSAGE, null)
        }

        // Raised by the RateLimit plugin, which has already set `Retry-After`.
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respondError(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED, RATE_LIMITED_MESSAGE, null)
        }
    }
}

private fun DomainException.statusCode(): HttpStatusCode =
    when (this) {
        is BusinessRuleException -> HttpStatusCode.UnprocessableEntity
        is ConflictException -> HttpStatusCode.Conflict
        is ValidationException -> HttpStatusCode.BadRequest
        is NotFoundException -> HttpStatusCode.NotFound
        is UnauthenticatedException -> HttpStatusCode.Unauthorized
        is ForbiddenException -> HttpStatusCode.Forbidden
        is PinLockedException -> HttpStatusCode.Locked
        is AppUpdateRequiredException -> HttpStatusCode.UpgradeRequired
    }

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    cause: DomainException?,
) {
    respond(
        status,
        ErrorEnvelope(
            ErrorBody(
                code = code,
                message = message,
                details = cause?.details,
                requestId = callId,
            ),
        ),
    )
}
