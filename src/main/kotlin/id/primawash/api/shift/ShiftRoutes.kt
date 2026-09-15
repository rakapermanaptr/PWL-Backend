package id.primawash.api.shift

import id.primawash.api.auth.toDto
import id.primawash.api.common.Pagination
import id.primawash.api.common.Requests
import id.primawash.api.common.toApi
import id.primawash.api.plugins.receiveIdempotent
import id.primawash.api.plugins.respondIdempotent
import id.primawash.api.plugins.staffPrincipal
import id.primawash.api.plugins.storedResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** PRD §8.7. A cashier sees and changes the shifts of their own branch only. */
fun Route.shiftRoutes(service: ShiftService) {
    route("/shifts") {
        get {
            call.staffPrincipal().requireOwner()
            val params = call.request.queryParameters
            val page =
                service.history(
                    branchId = Requests.uuidOrNull(params["branchId"], "branchId"),
                    from = Requests.date(params["from"], "from"),
                    to = Requests.date(params["to"], "to"),
                    limit = Pagination.limit(params["limit"]),
                    cursor = params["cursor"],
                )
            call.respond(ShiftPageResponse(page.items.map { it.toDto() }, page.nextCursor))
        }

        get("/current") {
            val principal = call.staffPrincipal()
            val requested = Requests.uuidOrNull(call.request.queryParameters["branchId"], "branchId")
            val current = service.current(principal.scopedBranch(requested) ?: principal.branchId)
            call.respond(CurrentShiftResponse(current.shift?.toDto(), current.entries.map { it.toDto() }))
        }

        get("/latest") {
            val principal = call.staffPrincipal()
            val scope = if (principal.isOwner) null else listOf(principal.branchId)
            call.respond(ShiftListResponse(service.latest(scope).map { it.toDto() }))
        }

        post {
            val principal = call.staffPrincipal()
            val (body, idempotency) = call.receiveIdempotent<OpenShiftRequest>()
            val result =
                service.open(principal, body.openingCash, body.staffProof, idempotency) {
                    // Stored for replay without the session: a stored response never holds a token.
                    storedResponse(HttpStatusCode.Created, OpenShiftResponse(it.shift.toDto(), session = null))
                }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondIdempotent(result, HttpStatusCode.Created) {
                OpenShiftResponse(it.shift.toDto(), it.session?.toDto())
            }
        }

        post("/{id}/cash-entries") {
            val principal = call.staffPrincipal()
            val id = Requests.uuid(call.parameters["id"], "id")
            val (body, idempotency) = call.receiveIdempotent<CashEntryRequest>()
            val cashIn =
                when (body.direction) {
                    "IN" -> true
                    "OUT" -> false
                    else -> throw Requests.invalid("direction")
                }
            val result =
                service.recordCashEntry(principal, id, cashIn, body.label, body.amount, idempotency) {
                    storedResponse(HttpStatusCode.Created, it.toDto())
                }
            call.respondIdempotent(result, HttpStatusCode.Created) { it.toDto() }
        }

        put("/{id}/counted-cash") {
            val principal = call.staffPrincipal()
            val id = Requests.uuid(call.parameters["id"], "id")
            val counted = Requests.required(call.receive<CountedCashRequest>().countedCash, "countedCash")
            call.respond(ShiftResponse(service.updateCountedCash(principal, id, counted).toDto()))
        }

        post("/{id}/close") {
            val principal = call.staffPrincipal()
            val id = Requests.uuid(call.parameters["id"], "id")
            val (body, idempotency) = call.receiveIdempotent<CountedCashRequest>()
            val counted = Requests.required(body.countedCash, "countedCash")
            val result =
                service.close(principal, id, counted, idempotency) { storedResponse(HttpStatusCode.OK, it.toDto()) }
            call.respondIdempotent(result, HttpStatusCode.OK) { it.toDto() }
        }
    }
}

fun ShiftView.toDto() =
    ShiftDto(
        id = shift.id.toString(),
        branchId = shift.branchId.toString(),
        open = shift.open,
        openedByStaffId = shift.openedByStaffId.toString(),
        openedByName = shift.openedByName,
        openedAt = shift.openedAt.toApi(),
        closedAt = shift.closedAt?.toApi(),
        closedByStaffId = shift.closedByStaffId?.toString(),
        openingCash = shift.openingCash,
        cashSales = shift.cashSales,
        transferSales = shift.transferSales,
        txCount = shift.txCount,
        pointsIssued = shift.pointsIssued,
        countedCash = shift.countedCash,
        expectedCash = expectedCash,
        recap =
            shift.recapExpected?.let { expected ->
                val actual = requireNotNull(shift.recapActual)
                RecapDto(expected, actual, actual - expected)
            },
        version = shift.version,
    )

fun CashEntryRecord.toDto() =
    CashEntryDto(
        id = id.toString(),
        shiftId = shiftId.toString(),
        kind = kind.name,
        label = label,
        note = note,
        amount = amount,
        orderId = orderId?.toString(),
        staffId = staffId.toString(),
        createdAt = createdAt.toApi(),
    )

private fun RecordedCashEntry.toDto() = CashEntryResponse(entry.toDto(), shift.toDto())

private fun ClosedShift.toDto() = CloseShiftResponse(shift.toDto(), RecapDto(expected, actual, diff))
