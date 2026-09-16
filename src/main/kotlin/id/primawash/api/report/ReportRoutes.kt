package id.primawash.api.report

import id.primawash.api.branch.ShiftSummary
import id.primawash.api.common.Requests
import id.primawash.api.common.ValidationException
import id.primawash.api.common.toApi
import id.primawash.api.plugins.staffPrincipal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** PRD §8.9 — reports and the audit log are owner-only (access matrix §4.1). */
fun Route.reportRoutes(service: ReportService) {
    route("/reports") {
        get("/dashboard") {
            call.staffPrincipal().requireOwner()
            val params = call.request.queryParameters
            val dashboard =
                service.dashboard(
                    branchId = Requests.uuidOrNull(params["branchId"], "branchId"),
                    date = Requests.date(params["date"], "date"),
                    periodDays = periodDays(params["periodDays"]),
                    auditRangeDays = auditRangeDays(params["auditRangeDays"]),
                )
            call.respond(dashboard.toDto())
        }
    }
}

/** Default 30 days (T11): the owner page never reports over all history. */
private fun periodDays(raw: String?): Int {
    if (raw == null) return DEFAULT_PERIOD_DAYS
    return raw.toIntOrNull()?.takeIf { it in 1..MAX_PERIOD_DAYS }
        ?: throw ValidationException("Parameter periodDays harus angka 1–$MAX_PERIOD_DAYS.")
}

/** PRD §9.4 offers three audit windows on the report page: hari ini, 7 hari, 30 hari. */
private fun auditRangeDays(raw: String?): Int {
    if (raw == null) return DEFAULT_AUDIT_RANGE_DAYS
    return raw.toIntOrNull()?.takeIf { it in AUDIT_RANGE_CHOICES }
        ?: throw ValidationException("Parameter auditRangeDays harus 1, 7, atau 30.")
}

private const val DEFAULT_PERIOD_DAYS = 30
private const val MAX_PERIOD_DAYS = 365
private const val AUDIT_RANGE_TODAY = 1
private const val AUDIT_RANGE_WEEK = 7
private const val AUDIT_RANGE_MONTH = 30
private const val DEFAULT_AUDIT_RANGE_DAYS = AUDIT_RANGE_TODAY
private val AUDIT_RANGE_CHOICES = setOf(AUDIT_RANGE_TODAY, AUDIT_RANGE_WEEK, AUDIT_RANGE_MONTH)

fun Dashboard.toDto() =
    DashboardResponse(
        date = date.toString(),
        branchId = branchId?.toString(),
        periodDays = period.days,
        periodFrom = period.from.toString(),
        periodTo = period.to.toString(),
        auditRangeDays = auditRangeDays,
        revenueToday = revenueToday,
        txToday = txToday,
        revenueYesterday = revenueYesterday,
        readyForPickup = readyForPickup,
        staleReady = staleReady,
        optInRate = optInRate,
        deliveryRate = deliveryRate,
        redemptionRate = redemptionRate,
        pendingSync = pendingSync,
        branchRows = branchRows.map { it.toDto() },
        chart = chart.map { it.toDto() },
        topServices = topServices.map { TopServiceDto(it.name, it.revenue, it.share) },
        audit = audit.map { it.toDto() },
        auditTruncated = auditTruncated,
    )

private fun DashboardBranchRow.toDto() =
    DashboardBranchRowDto(
        branchId = branch.id.toString(),
        code = branch.code,
        name = branch.name,
        active = branch.active,
        revenue = revenue,
        txCount = txCount,
        readyForPickup = readyForPickup,
        dailyTarget = branch.dailyTarget,
        targetRatio = targetRatio,
        latestShift = latestShift?.toDto(),
    )

private fun ShiftSummary.toDto() =
    DashboardShiftDto(
        id = id.toString(),
        open = open,
        openedByName = openedByName,
        openedAt = openedAt.toApi(),
        closedAt = closedAt?.toApi(),
    )

private fun ChartDay.toDto() =
    ChartDayDto(
        date = date.toString(),
        total = total,
        branches = branches.map { ChartBranchRevenueDto(it.branchId.toString(), it.revenue) },
    )

fun AuditRecord.toDto() =
    AuditEntryDto(
        id = id,
        branchId = branchId?.toString(),
        staffId = staffId?.toString(),
        actorName = actorName,
        actionType = actionType,
        action = action,
        entityType = entityType,
        entityId = entityId,
        createdAt = createdAt.toApi(),
    )
