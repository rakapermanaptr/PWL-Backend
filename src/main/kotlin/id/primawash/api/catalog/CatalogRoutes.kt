package id.primawash.api.catalog

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
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** PRD §8.4. Cashiers read; only owners change the price list, loyalty rate and rewards. */
fun Route.catalogRoutes(service: CatalogService) {
    route("/services") {
        get {
            val principal = call.staffPrincipal()
            // A cashier's POS only ever sees active services, whatever the query says.
            val includeInactive =
                principal.isOwner &&
                    Requests.boolean(call.request.queryParameters["includeInactive"], "includeInactive") == true
            call.respond(ServiceListResponse(service.listServices(includeInactive).map { it.toDto() }))
        }

        post {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val body = call.receive<CreateServiceRequest>()
            val category = body.category?.let { raw -> ServiceCategory.entries.firstOrNull { it.name == raw } }
            val unit = body.unit?.takeIf { it in SERVICE_UNITS }
            if (category == null || unit == null) {
                throw Requests.invalid(
                    listOfNotNull(
                        "category".takeIf { category == null },
                        "unit".takeIf {
                            unit ==
                                null
                        },
                    ),
                )
            }
            val created = service.addService(category, body.name, body.price, unit, principal.auditActor)
            call.respond(HttpStatusCode.Created, ServiceResponse(created.toDto()))
        }

        patch("/prices") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val prices = Requests.required(call.receive<SavePricesRequest>().prices, "prices")
            val parsed = prices.mapKeys { (id, _) -> Requests.uuid(id, "prices") }
            val result = service.savePrices(parsed, principal.auditActor)
            call.respond(SavePricesResponse(result.services.map { it.toDto() }, result.changedCount))
        }

        patch("/{id}") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val active = Requests.required(call.receive<ToggleActiveRequest>().active, "active")
            val result = service.setServiceActive(id, active, principal.auditActor)
            call.respond(ServiceChangeResponse(result.service.toDto(), result.changed))
        }
    }

    route("/loyalty/rate") {
        get {
            call.staffPrincipal()
            call.respond(service.currentRate().toDto())
        }

        put {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val body = call.receive<SaveRateRequest>()
            val result = service.saveRate(body.rupiahPerStep, body.pointsPerStep, principal.auditActor)
            call.respond(RateChangeResponse(result.rate.toDto(), result.changed))
        }
    }

    route("/rewards") {
        get {
            val principal = call.staffPrincipal()
            val includeInactive =
                principal.isOwner &&
                    Requests.boolean(call.request.queryParameters["includeInactive"], "includeInactive") == true
            call.respond(RewardListResponse(service.listRewards(includeInactive).map { it.toDto() }))
        }

        post {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val body = call.receive<CreateRewardRequest>()
            val created = service.addReward(body.name, body.cost, body.value, body.minSubtotal, principal.auditActor)
            call.respond(HttpStatusCode.Created, RewardResponse(created.toDto()))
        }

        patch("/{id}") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val active = Requests.required(call.receive<ToggleActiveRequest>().active, "active")
            val result = service.setRewardActive(id, active, principal.auditActor)
            call.respond(RewardChangeResponse(result.reward.toDto(), result.changed))
        }
    }
}

fun ServiceRecord.toDto() = ServiceDto(id.toString(), category.name, name, price, unit, step.toDouble(), active)

fun RewardRecord.toDto() = RewardDto(id.toString(), name, cost, value, note, minSubtotal, usedCount, active)

private fun LoyaltyRateRecord.toDto() =
    LoyaltyRateDto(id.toString(), rupiahPerStep, pointsPerStep, effectiveFrom.toApi())
