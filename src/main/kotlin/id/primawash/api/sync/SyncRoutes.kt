package id.primawash.api.sync

import id.primawash.api.branch.toDto
import id.primawash.api.catalog.toDto
import id.primawash.api.common.Requests
import id.primawash.api.customer.toDto
import id.primawash.api.order.toDto
import id.primawash.api.plugins.staffPrincipal
import id.primawash.api.shift.toDto
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** PRD §8.10 — the tablet's Room cache. */
fun Route.syncRoutes(service: SyncService) {
    route("/sync") {
        get("/bootstrap") {
            val bootstrap = service.bootstrap(call.staffPrincipal())
            call.respond(BootstrapResponse(bootstrap.data.toDto(), bootstrap.cursor))
        }

        get("/changes") {
            val principal = call.staffPrincipal()
            val since = Requests.required(call.request.queryParameters["since"]?.takeIf { it.isNotBlank() }, "since")
            val changes = service.changes(principal, since)
            call.respond(ChangesResponse(changes.data.toDto(), changes.nextCursor, changes.hasMore))
        }
    }
}

private fun SyncData.toDto() =
    SyncDataDto(
        branches = branches.map { it.toDto() },
        staff =
            staff.map {
                SyncStaffDto(it.id.toString(), it.name, it.shortName, it.role.name, it.branchId?.toString(), it.active)
            },
        services = services.map { it.toDto() },
        rewards = rewards.map { it.toDto() },
        loyaltyRates = loyaltyRates.map { it.toDto() },
        customers = customers.map { it.toDto() },
        orders = orders.map { it.toDto() },
        shifts = shifts.map { it.toDto() },
    )
