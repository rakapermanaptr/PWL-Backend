package id.primawash.api.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * JSON is camelCase; database columns are snake_case. The mapping is always explicit (CLAUDE.md).
 *
 * Nulls are written out (`"nextCursor": null`, `"customerId": null`) as in the PRD examples, so a
 * client can tell "no value" from "field not sent by this server version".
 */
val ApiJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(ApiJson)
    }
}

/** For endpoints whose body is optional: an empty body is `null`, a malformed one is still a 400. */
suspend inline fun <reified T> ApplicationCall.receiveOptional(): T? {
    val text = receiveText()
    if (text.isBlank()) return null
    return try {
        ApiJson.decodeFromString<T>(text)
    } catch (error: SerializationException) {
        throw BadRequestException("Body tidak valid", error)
    } catch (error: IllegalArgumentException) {
        throw BadRequestException("Body tidak valid", error)
    }
}
