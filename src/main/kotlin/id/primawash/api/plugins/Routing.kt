package id.primawash.api.plugins

import id.primawash.api.auth.authRoutes
import id.primawash.api.auth.publicAuthRoutes
import id.primawash.api.branch.branchRoutes
import id.primawash.api.catalog.catalogRoutes
import id.primawash.api.customer.customerRoutes
import id.primawash.api.device.deviceRoutes
import id.primawash.api.health.healthRoutes
import id.primawash.api.order.orderRoutes
import id.primawash.api.report.reportRoutes
import id.primawash.api.shift.shiftRoutes
import id.primawash.api.staff.staffRoutes
import id.primawash.api.sync.syncRoutes
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
 * catalog and customers. M2: orders, offline sync, shifts, the tablet cache and heartbeat. M3: the
 * owner's reports. M5 adds the WhatsApp endpoints (see README "Integrasi Meta / WhatsApp") — those
 * do not exist before their milestone.
 */
fun Application.configureRouting(
    dataSource: DataSource,
    settings: ApiSettings,
) {
    configureAuth(get())

    routing {
        healthRoutes(dataSource, settings.appVersion)

        rateLimit(DEVICE_RATE_LIMIT) {
            route(API_BASE_PATH) {
                install(AppVersionGate) { minimum = settings.minAppVersion }

                // The login screen: no token of any kind — pick a branch, type a PIN.
                publicAuthRoutes(get())

                authenticate(STAFF_AUTH) {
                    authRoutes(get())
                    deviceRoutes(get())
                    branchRoutes(get(), get(), get())
                    staffRoutes(get())
                    catalogRoutes(get())
                    customerRoutes(get())
                    orderRoutes(get(), get())
                    shiftRoutes(get())
                    reportRoutes(get())
                    syncRoutes(get())
                }
            }
        }
    }
}
