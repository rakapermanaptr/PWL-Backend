package id.primawash.api.report

import id.primawash.api.branch.BranchRecord
import id.primawash.api.branch.BranchService
import id.primawash.api.branch.ShiftSummary
import id.primawash.api.common.WibClock
import id.primawash.api.db.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/** One branch's line on the dashboard (PRD §9.4 `branchRows[]`). */
data class DashboardBranchRow(
    val branch: BranchRecord,
    val revenue: Long,
    val txCount: Int,
    val readyForPickup: Int,
    /** Revenue ÷ the branch's daily target; `null` when no target is set. */
    val targetRatio: Double?,
    val latestShift: ShiftSummary?,
)

data class ChartBranchRevenue(
    val branchId: UUID,
    val revenue: Long,
)

/** One column of the 7-day chart: every branch in scope, plus their total for that day. */
data class ChartDay(
    val date: LocalDate,
    val total: Long,
    val branches: List<ChartBranchRevenue>,
)

data class DashboardPeriod(
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
)

data class Dashboard(
    val date: LocalDate,
    val branchId: UUID?,
    val period: DashboardPeriod,
    val auditRangeDays: Int,
    val revenueToday: Long,
    val txToday: Int,
    val revenueYesterday: Long,
    val readyForPickup: Int,
    val staleReady: Int,
    val optInRate: Double?,
    val deliveryRate: Double?,
    val redemptionRate: Double?,
    val pendingSync: Int,
    val branchRows: List<DashboardBranchRow>,
    val chart: List<ChartDay>,
    val topServices: List<ServiceRevenue>,
    val audit: List<AuditRecord>,
    /** True when the audit window holds more entries than the dashboard carries — see `GET /audit`. */
    val auditTruncated: Boolean,
)

/**
 * The owner's report page (PRD §8.9, computed per §9.4). Owner-only — the route checks the role.
 *
 * Everything is measured over an explicit window: `date` (default today in WIB) for the daily
 * figures, and the last `periodDays` days ending on it for the rates. There is deliberately no
 * "all time" option — computing metrics over all history is trap T11, and a lifetime average
 * hides exactly the recent change the owner opens this page to see.
 */
class ReportService(
    private val tx: TransactionRunner,
    private val repository: ReportRepository,
    private val branches: BranchService,
    private val clock: Clock,
) {
    /**
     * `GET /reports/dashboard` (PRD §8.9). [branchId] null means every branch; the route has already
     * resolved a cashier's scope, and an owner naming a branch that does not exist gets a 404.
     *
     * One read-only `REPEATABLE READ` snapshot, one consistent picture: every query sees the database
     * as of the first one, so the chart cannot disagree with the branch rows because an order landed
     * between two queries.
     */
    suspend fun dashboard(
        branchId: UUID?,
        date: LocalDate?,
        periodDays: Int,
        auditRangeDays: Int,
    ): Dashboard =
        tx.snapshot {
            val on = date ?: WibClock.businessDate(clock.instant())
            val scope = resolveScope(branchId)
            val period = DashboardPeriod(periodDays, on.minusDays((periodDays - 1).toLong()), on)
            build(branchId, on, period, auditRangeDays, scope)
        }

    private fun resolveScope(branchId: UUID?): List<BranchRecord> {
        if (branchId == null) return branches.findAll()
        val branch = branches.find(branchId) ?: throw BranchService.branchNotFound()
        return listOf(branch)
    }

    @Suppress("LongMethod")
    private fun build(
        branchId: UUID?,
        on: LocalDate,
        period: DashboardPeriod,
        auditRangeDays: Int,
        scope: List<BranchRecord>,
    ): Dashboard {
        val ids = scope.map { it.id }
        val chartFrom = on.minusDays((CHART_DAYS - 1).toLong())
        val sales =
            repository
                .dailySales(ids, minOf(chartFrom, on.minusDays(1)), on)
                .associateBy { it.branchId to it.businessDate }
        val ready = repository.readyForPickup(ids, clock.instant().minus(STALE_READY_AFTER))
        val optIn = repository.optIn(ids)
        val delivery =
            repository.statusMessageDelivery(ids, WibClock.startOfDay(period.from), WibClock.endOfDay(period.to))
        val redemption = repository.redemption(ids, period.from, period.to)
        val auditFrom = WibClock.startOfDay(on.minusDays((auditRangeDays - 1).toLong()))
        val audit = repository.audit(ids, auditFrom, WibClock.endOfDay(on), AUDIT_LIMIT)
        val latestShifts = branches.latestShifts()

        val today = ids.map { sales[it to on] }
        val rows =
            scope.map { branch ->
                val day = sales[branch.id to on]
                val revenue = day?.revenue ?: 0L
                DashboardBranchRow(
                    branch = branch,
                    revenue = revenue,
                    txCount = day?.txCount ?: 0,
                    readyForPickup = (ready[branch.id] ?: ReadyCounts.EMPTY).ready,
                    targetRatio = ReportMath.ratio(revenue, branch.dailyTarget),
                    latestShift = latestShifts[branch.id],
                )
            }

        return Dashboard(
            date = on,
            branchId = branchId,
            period = period,
            auditRangeDays = auditRangeDays,
            revenueToday = today.sumOf { it?.revenue ?: 0L },
            txToday = today.sumOf { it?.txCount ?: 0 },
            revenueYesterday = ids.sumOf { sales[it to on.minusDays(1)]?.revenue ?: 0L },
            readyForPickup = ids.sumOf { (ready[it] ?: ReadyCounts.EMPTY).ready },
            staleReady = ids.sumOf { (ready[it] ?: ReadyCounts.EMPTY).stale },
            optInRate = ReportMath.ratio(optIn.matching, optIn.total),
            deliveryRate = ReportMath.ratio(delivery.matching, delivery.total),
            redemptionRate = ReportMath.ratio(redemption.matching, redemption.total),
            pendingSync = repository.pendingSync(ids),
            branchRows = rows,
            chart = chart(ids, chartFrom, sales),
            topServices = ReportMath.topServices(repository.serviceRevenue(ids, period.from, period.to)),
            audit = audit.take(AUDIT_LIMIT),
            auditTruncated = audit.size > AUDIT_LIMIT,
        )
    }

    /**
     * The 7 days ending on the dashboard date, oldest first. Every day and every branch in scope is
     * present even with no sales, so the tablet draws a continuous chart instead of skipping the
     * quiet days.
     */
    private fun chart(
        branchIds: List<UUID>,
        from: LocalDate,
        sales: Map<Pair<UUID, LocalDate>, BranchDaySales>,
    ): List<ChartDay> =
        (0 until CHART_DAYS).map { offset ->
            val day = from.plusDays(offset.toLong())
            val perBranch = branchIds.map { ChartBranchRevenue(it, sales[it to day]?.revenue ?: 0L) }
            ChartDay(day, perBranch.sumOf { it.revenue }, perBranch)
        }

    companion object {
        /** PRD §9.4 `chart[]`: the last 7 days ending on the dashboard date. */
        const val CHART_DAYS = 7

        /** PRD §9.4 `staleReady`: still `SIAP` more than 72 hours after it got there. */
        val STALE_READY_AFTER: Duration = Duration.ofHours(72)

        /**
         * The dashboard carries the newest entries of the window; the full log lives behind
         * `GET /audit`, which filters and pages (PRD §8.9).
         */
        const val AUDIT_LIMIT = 100
    }
}
