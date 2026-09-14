package id.primawash.api.device

import id.primawash.api.common.Requests
import id.primawash.api.common.toApi
import id.primawash.api.plugins.staffPrincipal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** Owner device management (PRD §8.1). Mounted inside the staff-authenticated tree. */
fun Route.deviceRoutes(service: DeviceService) {
    route("/devices") {
        get {
            call.staffPrincipal().requireOwner()
            call.respond(DeviceListResponse(service.list().map { it.toDto() }))
        }

        delete("/{id}") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val result = service.revoke(id, principal.auditActor)
            call.respond(DeviceChangeResponse(result.device.toDto(), result.changed))
        }
    }
}

private fun DeviceRecord.toDto() =
    DeviceDto(
        id = id.toString(),
        name = name,
        platform = platform,
        appVersion = appVersion,
        lastBranchId = lastBranchId?.toString(),
        firstSeenAt = activatedAt.toApi(),
        lastSeenAt = lastSeenAt?.toApi(),
        pendingCount = pendingCount,
        revokedAt = revokedAt?.toApi(),
    )
