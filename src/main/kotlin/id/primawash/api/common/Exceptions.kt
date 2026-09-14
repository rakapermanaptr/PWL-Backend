package id.primawash.api.common

import kotlinx.serialization.json.JsonObject

/**
 * Base class for every failure that carries a business error code and a cashier-ready Indonesian
 * message. Deliberately free of Ktor types: these are thrown from `Service` classes, and
 * `StatusPages` is the only place that turns them into HTTP responses.
 */
sealed class DomainException(
    val code: String,
    override val message: String,
    val details: JsonObject? = null,
) : RuntimeException(message)

/** Business rule violation → `422` (PRD Lampiran B). */
class BusinessRuleException(
    code: String,
    message: String,
    details: JsonObject? = null,
) : DomainException(code, message, details)

/** Conflicting state — status, version, duplicate, idempotency → `409`. */
class ConflictException(
    code: String,
    message: String,
    details: JsonObject? = null,
) : DomainException(code, message, details)

/** Malformed body/parameters → `400 VALIDATION_ERROR`. */
class ValidationException(
    message: String,
    details: JsonObject? = null,
) : DomainException(ErrorCodes.VALIDATION_ERROR, message, details)

/** Missing resource → `404`. */
class NotFoundException(
    message: String,
    details: JsonObject? = null,
) : DomainException(ErrorCodes.NOT_FOUND, message, details)

/** Missing or expired credentials → `401`. */
class UnauthenticatedException(
    code: String = ErrorCodes.UNAUTHENTICATED,
    message: String,
) : DomainException(code, message)

/** Role or branch scope violation → `403`. Authorization comes from the token, never the body. */
class ForbiddenException(
    code: String = ErrorCodes.FORBIDDEN,
    message: String,
    details: JsonObject? = null,
) : DomainException(code, message, details)

/** Too many wrong PINs → `423` with `Retry-After` (PRD §12.2). */
class PinLockedException(
    message: String,
    val retryAfterSeconds: Long,
    details: JsonObject? = null,
) : DomainException(ErrorCodes.PIN_LOCKED, message, details)

/** Client older than `MIN_APP_VERSION` → `426`. */
class AppUpdateRequiredException(
    message: String,
    details: JsonObject? = null,
) : DomainException(ErrorCodes.APP_UPDATE_REQUIRED, message, details)
