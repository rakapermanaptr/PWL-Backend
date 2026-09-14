package id.primawash.api.plugins

import id.primawash.api.health.healthRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import javax.sql.DataSource

const val API_BASE_PATH = "/api/v1"

/**
 * Feature routes are mounted here as each milestone lands. M0 ships the health endpoints only;
 * the `/api/v1` tree fills up in M1 (auth & master data) and M2 (transactions).
 */
fun Application.configureRouting(
    dataSource: DataSource,
    appVersion: String,
) {
    routing {
        healthRoutes(dataSource, appVersion)

        route(API_BASE_PATH) {
            // M1: auth, branches, staff, catalog, customers
            // M2: orders, shifts, sync
            // M3: reports
            // M5: wa (see README "Integrasi Meta / WhatsApp")
        }
    }
}
