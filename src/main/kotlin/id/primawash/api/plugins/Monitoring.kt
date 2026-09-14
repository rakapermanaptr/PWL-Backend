package id.primawash.api.plugins

import id.primawash.api.common.RequestId
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

const val REQUEST_ID_HEADER = "X-Request-Id"

private val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * Structured request logging with a `requestId` (PRD §13 "Observability").
 *
 * Paths under `/auth` and `/staff` carry PINs, so their query strings are never logged and their
 * bodies are never logged at all — no body-logging plugin is installed anywhere in this codebase.
 */
fun Application.configureMonitoring() {
    install(CallId) {
        // Satu pemanggilan: header ini dibaca dari request dan dikirim balik di response.
        header(REQUEST_ID_HEADER)
        generate { RequestId.next() }
        // Nilai dari klien hanya diterima bila bentuknya aman — id request masuk ke log, dan
        // string bebas dari luar tidak boleh bisa menyuntik baris log.
        verify { it.matches(SAFE_REQUEST_ID) }
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        format { call ->
            val path = call.request.path()
            val status = call.response.status()?.value ?: "-"
            "${call.callId} ${call.request.httpMethod.value} $path -> $status"
        }
    }
}

/** True for paths whose request/response content may contain a PIN or a raw token. */
fun isRedactedPath(path: String): Boolean = path.contains("/auth") || path.contains("/staff")
