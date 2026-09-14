package id.primawash.api.device

import id.primawash.api.common.Requests
import id.primawash.api.common.toApi
import id.primawash.api.plugins.receiveOptional
import id.primawash.api.plugins.staffPrincipal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** `POST /devices/activate` — public: the tablet has no credential yet (PRD §8.1 "Pub"). */
fun Route.deviceActivationRoutes(service: DeviceService) {
    post("/devices/activate") {
        val body = call.receive<ActivateDeviceRequest>()
        val activated = service.activate(body.code, body.name, body.appVersion)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.Created, ActivateDeviceResponse(activated.deviceToken, activated.device.toDto()))
    }
}

/** Owner device management (PRD §8.1). Mounted inside the staff-authenticated tree. */
fun Route.deviceRoutes(service: DeviceService) {
    route("/devices") {
        post("/activation-codes") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val branchId =
                Requests.uuidOrNull(
                    call.receiveOptional<CreateActivationCodeRequest>()?.branchId,
                    "branchId",
                )
            val issued = service.createActivationCode(branchId, principal.auditActor)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.Created,
                ActivationCodeResponse(issued.code, issued.branchId?.toString(), issued.expiresAt.toApi()),
            )
        }

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
        activatedAt = activatedAt.toApi(),
        lastSeenAt = lastSeenAt?.toApi(),
        pendingCount = pendingCount,
        revokedAt = revokedAt?.toApi(),
    )
