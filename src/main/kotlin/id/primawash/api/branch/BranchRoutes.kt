package id.primawash.api.branch

import id.primawash.api.common.Requests
import id.primawash.api.common.ValidationException
import id.primawash.api.common.WibClock
import id.primawash.api.common.toApi
import id.primawash.api.plugins.staffPrincipal
import id.primawash.api.staff.StaffService
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** PRD §8.2. Mounted inside the staff-authenticated `/api/v1` tree. */
fun Route.branchRoutes(
    service: BranchService,
    staff: StaffService,
    clock: Clock,
) {
    route("/branches") {
        get {
            call.staffPrincipal()
            call.respond(BranchListResponse(service.list().map { it.toDto() }))
        }

        get("/overview") {
            call.staffPrincipal().requireOwner()
            val date = parseDate(call.request.queryParameters["date"]) ?: WibClock.businessDate(clock.instant())
            val overview = service.overview(date)
            val cashiers = staff.activeCashierNames()
            call.respond(
                BranchOverviewResponse(
                    date = date.toString(),
                    items =
                        overview.map { row ->
                            BranchOverviewDto(
                                branch = row.branch.toDto(),
                                revenue = row.sales.revenue,
                                txCount = row.sales.txCount,
                                targetRatio = row.sales.revenue.toDouble() / row.branch.dailyTarget,
                                latestShift = row.latestShift?.toDto(),
                                activeCashiers = cashiers[row.branch.id].orEmpty(),
                            )
                        },
                ),
            )
        }

        patch("/{id}") {
            val principal = call.staffPrincipal().also { it.requireOwner() }
            val id = Requests.uuid(call.parameters["id"], "id")
            val body = call.receive<UpdateBranchRequest>()
            val result = service.update(id, body.dailyTarget, body.active, principal.branchId, principal.auditActor)
            call.respond(BranchChangeResponse(result.branch.toDto(), result.changed))
        }
    }
}

private fun parseDate(raw: String?): LocalDate? =
    raw?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Format tanggal harus YYYY-MM-DD.", Requests.invalid("date").details)
        }
    }

fun BranchRecord.toDto() = BranchDto(id.toString(), code, name, address, phone, hours, dailyTarget, active)

fun ShiftSummary.toDto() = ShiftSummaryDto(id.toString(), open, openedByName, openedAt.toApi(), closedAt?.toApi())
