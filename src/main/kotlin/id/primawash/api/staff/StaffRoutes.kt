package id.primawash.api.staff

import id.primawash.api.common.Requests
import id.primawash.api.common.toApi
import id.primawash.api.plugins.staffPrincipal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * PRD §8.3. Request and response bodies here carry PINs, so nothing under `/staff` is ever logged
 * beyond method, path and status (see Monitoring).
 */
fun Route.staffRoutes(service: StaffService) {
    route("/staff") {
        get {
            val principal = call.staffPrincipal()
            val branchId = Requests.uuidOrNull(call.request.queryParameters["branchId"], "branchId")
            if (principal.isOwner) {
                val active = Requests.boolean(call.request.queryParameters["active"], "active")
                call.respond(StaffListResponse(service.list(branchId, active).map { it.toDto() }))
            } else {
                val scoped = requireNotNull(principal.scopedBranch(branchId))
                call.respond(StaffCandidateListResponse(service.candidatesFor(scoped).map { it.toCandidateDto() }))
            }
        }

        post {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val body = call.receive<CreateStaffRequest>()
            val role =
                body.role?.let { raw -> Role.entries.firstOrNull { it.name == raw } } ?: throw Requests.invalid("role")
            val branchId = Requests.uuidOrNull(body.branchId, "branchId")
            val created = service.create(body.name, body.pin, role, branchId, principal.auditActor)
            call.respond(HttpStatusCode.Created, StaffResponse(created.toDto()))
        }

        post("/{id}/reset-pin") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val reset = service.resetPin(id, principal.auditActor)
            // The new PIN is shown once: no cache may keep it.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ResetPinResponse(reset.staff.toDto(), reset.pin))
        }

        patch("/{id}") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val active = Requests.required(call.receive<UpdateStaffRequest>().active, "active")
            val result = service.setActive(id, active, principal.staffId, principal.auditActor)
            call.respond(StaffChangeResponse(result.staff.toDto(), result.changed))
        }
    }
}

fun StaffRecord.toDto() =
    StaffDto(
        id = id.toString(),
        name = name,
        shortName = shortName,
        role = role.name,
        branchId = branchId?.toString(),
        active = active,
        lastLoginAt = lastLoginAt?.toApi(),
    )

private fun StaffRecord.toCandidateDto() = StaffCandidateDto(id.toString(), name, shortName, role.name)
