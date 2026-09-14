package id.primawash.api.customer

import id.primawash.api.common.Pagination
import id.primawash.api.common.Requests
import id.primawash.api.common.toApi
import id.primawash.api.plugins.staffPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** PRD §8.5. Customers are global, so cashiers and owners see the same list. */
fun Route.customerRoutes(service: CustomerService) {
    route("/customers") {
        get {
            call.staffPrincipal()
            val params = call.request.queryParameters
            val page = service.search(params["q"], Pagination.limit(params["limit"]), params["cursor"])
            call.respond(CustomerPageResponse(page.items.map { it.toDto() }, page.nextCursor))
        }

        post {
            val principal = call.staffPrincipal()
            val body = call.receive<RegisterCustomerRequest>()
            val clientId = Requests.uuidOrNull(body.id, "id")
            val created =
                service.register(
                    clientId = clientId,
                    name = body.name,
                    phone = body.phone,
                    optIn = body.optIn ?: false,
                    branchId = principal.branchId,
                    actor = principal.auditActor,
                )
            call.respond(HttpStatusCode.Created, CustomerResponse(created.toDto()))
        }

        get("/{id}") {
            call.staffPrincipal()
            call.respond(CustomerResponse(service.get(Requests.uuid(call.parameters["id"], "id")).toDto()))
        }

        patch("/{id}") {
            val principal = call.staffPrincipal()
            val id = Requests.uuid(call.parameters["id"], "id")
            val body = call.receive<UpdateCustomerRequest>()
            val result = service.update(id, body.name, body.optIn, principal.branchId, principal.auditActor)
            call.respond(CustomerChangeResponse(result.customer.toDto(), result.changed))
        }

        get("/{id}/points") {
            call.staffPrincipal()
            val id = Requests.uuid(call.parameters["id"], "id")
            val params = call.request.queryParameters
            val page = service.ledger(id, Pagination.limit(params["limit"]), params["cursor"])
            call.respond(
                PointsPageResponse(
                    items =
                        page.items.map {
                            PointsEntryDto(
                                it.id,
                                it.type,
                                it.delta,
                                it.balanceAfter,
                                it.orderId?.toString(),
                                it.staffId?.toString(),
                                it.createdAt.toApi(),
                            )
                        },
                    nextCursor = page.nextCursor,
                ),
            )
        }
    }
}

fun CustomerRecord.toDto() = CustomerDto(id.toString(), name, phone, points, optIn, visits, homeBranchId.toString())
