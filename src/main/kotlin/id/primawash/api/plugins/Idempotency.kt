package id.primawash.api.plugins

import id.primawash.api.common.IdempotencyRequest
import id.primawash.api.common.Idempotent
import id.primawash.api.common.Requests
import id.primawash.api.common.StoredResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** A request body together with the idempotency identity of the call that carried it. */
data class IdempotentBody<T>(
    val body: T,
    val idempotency: IdempotencyRequest,
)

/**
 * Reads the body of a mutation that requires `Idempotency-Key` (PRD §6.1). A missing or malformed key is
 * `400 VALIDATION_ERROR`. The key is scoped to the tablet of the session and to this exact method and path,
 * and the body is hashed after parsing, so formatting differences never count as a different request.
 */
suspend inline fun <reified T> ApplicationCall.receiveIdempotent(): IdempotentBody<T> {
    val principal = staffPrincipal()
    val key = Requests.uuid(request.headers[IDEMPOTENCY_KEY_HEADER], IDEMPOTENCY_KEY_HEADER)
    val text = receiveText()
    val element: JsonElement =
        try {
            if (text.isBlank()) JsonObject(emptyMap()) else ApiJson.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw BadRequestException("Body tidak valid", error)
        }
    val body =
        try {
            ApiJson.decodeFromJsonElement<T>(element)
        } catch (error: SerializationException) {
            throw BadRequestException("Body tidak valid", error)
        } catch (error: IllegalArgumentException) {
            throw BadRequestException("Body tidak valid", error)
        }
    val endpoint = "${request.httpMethod.value} ${request.path()}"
    return IdempotentBody(
        body,
        IdempotencyRequest(key, principal.deviceId, endpoint, IdempotencyRequest.hashOf(element)),
    )
}

/** The response to store for replay: the same status and JSON the first call answered with. */
inline fun <reified R> storedResponse(
    status: HttpStatusCode,
    body: R,
): StoredResponse = StoredResponse(status.value, ApiJson.encodeToJsonElement(body))

/** Answers a fresh result with [render], or replays the stored response of the original call verbatim. */
suspend inline fun <T, reified R : Any> ApplicationCall.respondIdempotent(
    result: Idempotent<T>,
    status: HttpStatusCode,
    render: (T) -> R,
) {
    when (result) {
        is Idempotent.Fresh -> respond(status, render(result.value))
        is Idempotent.Replayed -> respond(HttpStatusCode.fromValue(result.response.status), result.response.body)
    }
}
