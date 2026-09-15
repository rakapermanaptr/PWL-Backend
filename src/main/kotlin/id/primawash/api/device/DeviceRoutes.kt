package id.primawash.api.device

import id.primawash.api.common.Requests
import id.primawash.api.common.toApi
import id.primawash.api.plugins.staffPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Owner device management (PRD §8.1) and the tablet heartbeat (§8.10). Staff-authenticated. */
fun Route.deviceRoutes(service: DeviceService) {
    route("/devices") {
        get {
            call.staffPrincipal().requireOwner()
            call.respond(DeviceListResponse(service.list().map { it.toDto() }))
        }

        post("/heartbeat") {
            val principal = call.staffPrincipal()
            val body = call.receive<HeartbeatRequest>()
            val pending = Requests.required(body.pendingCount, "pendingCount")
            if (pending < 0) throw Requests.invalid("pendingCount")
            val at = service.heartbeat(principal.deviceId, pending, body.appVersion?.take(MAX_APP_VERSION_LENGTH))
            call.respond(HeartbeatResponse(at.toApi()))
        }

        delete("/{id}") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val result = service.revoke(id, principal.auditActor)
            call.respond(DeviceChangeResponse(result.device.toDto(), result.changed))
        }
    }
}

private const val MAX_APP_VERSION_LENGTH = 32

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
