package id.primawash.api.plugins

import id.primawash.api.auth.authRoutes
import id.primawash.api.auth.deviceAuthRoutes
import id.primawash.api.branch.branchRoutes
import id.primawash.api.catalog.catalogRoutes
import id.primawash.api.customer.customerRoutes
import id.primawash.api.device.deviceActivationRoutes
import id.primawash.api.device.deviceRoutes
import id.primawash.api.health.healthRoutes
import id.primawash.api.staff.staffRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get
import javax.sql.DataSource

const val API_BASE_PATH = "/api/v1"

/**
 * Feature routes are mounted here as each milestone lands. M1: devices, auth, branches, staff, the
 * catalog and customers. M2 adds orders, shifts and sync; M3 reports; M5 the WhatsApp endpoints
 * (see README "Integrasi Meta / WhatsApp") — none of those exist before their milestone.
 */
fun Application.configureRouting(
    dataSource: DataSource,
    settings: ApiSettings,
) {
    configureAuth(get(), get())

    routing {
        healthRoutes(dataSource, settings.appVersion)

        rateLimit(DEVICE_RATE_LIMIT) {
            route(API_BASE_PATH) {
                install(AppVersionGate) { minimum = settings.minAppVersion }

                deviceActivationRoutes(get())

                authenticate(DEVICE_AUTH) {
                    deviceAuthRoutes(get())
                }

                authenticate(STAFF_AUTH) {
                    authRoutes(get())
                    deviceRoutes(get())
                    branchRoutes(get(), get(), get())
                    staffRoutes(get())
                    catalogRoutes(get())
                    customerRoutes(get())
                }
            }
        }
    }
}
