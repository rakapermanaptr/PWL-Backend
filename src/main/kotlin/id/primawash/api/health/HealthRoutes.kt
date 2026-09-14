package id.primawash.api.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val database: String,
)

private const val PING_TIMEOUT_SECONDS = 2

/**
 * Liveness and readiness for the staging/production deployment — the M0 gate is "staging hidup".
 * Unauthenticated on purpose: the load balancer and uptime monitor call these.
 */
fun Route.healthRoutes(
    dataSource: DataSource,
    appVersion: String,
) {
    val logger = LoggerFactory.getLogger("Health")

    get("/health") {
        call.respond(HealthResponse(status = "UP", version = appVersion))
    }

    get("/health/ready") {
        val databaseUp =
            runCatching {
                dataSource.connection.use { it.isValid(PING_TIMEOUT_SECONDS) }
            }.onFailure { logger.warn("Readiness check gagal: {}", it.message) }.getOrDefault(false)

        if (databaseUp) {
            call.respond(ReadinessResponse(status = "READY", database = "UP"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, ReadinessResponse("NOT_READY", "DOWN"))
        }
    }
}
