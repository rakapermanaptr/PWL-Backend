package id.primawash.api.report

import id.primawash.api.branch.BranchService
import id.primawash.api.common.NotFoundException
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FAMILIA_URBAN
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NAROGONG
import id.primawash.api.support.NOW
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** NOW is 2026-09-15T02:00Z — 09.00 WIB, so "today" in Jakarta is 15 September 2026. */
private val TODAY = LocalDate.parse("2026-09-15")

class ReportServiceTest {
    private val repository = mockk<ReportRepository>()
    private val branches = mockk<BranchService>()
    private val service = ReportService(DirectTransactionRunner, repository, branches, FIXED_CLOCK)

    init {
        every { branches.findAll() } returns listOf(FAMILIA_URBAN, NAROGONG)
        every { branches.find(any()) } returns null
        every { branches.find(FAMILIA_URBAN.id) } returns FAMILIA_URBAN
        every { branches.latestShifts() } returns emptyMap()
        every { repository.dailySales(any(), any(), any()) } returns emptyList()
        every { repository.readyForPickup(any(), any()) } returns emptyMap()
        every { repository.optIn(any()) } returns RatioCounts(0, 0)
        every { repository.statusMessageDelivery(any(), any(), any()) } returns RatioCounts(0, 0)
        every { repository.redemption(any(), any(), any()) } returns RatioCounts(0, 0)
        every { repository.pendingSync(any()) } returns 0
        every { repository.serviceRevenue(any(), any(), any()) } returns emptyMap()
        every { repository.audit(any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `should measure the last 30 days ending today by default`() =
        runBlocking<Unit> {
            val dashboard = service.dashboard(branchId = null, date = null, periodDays = 30, auditRangeDays = 1)

            dashboard.date shouldBe TODAY
            dashboard.period.days shouldBe 30
            dashboard.period.to shouldBe TODAY
            dashboard.period.from shouldBe LocalDate.parse("2026-08-17")
            verify { repository.redemption(any(), LocalDate.parse("2026-08-17"), TODAY) }
        }

    @Test
    fun `should report a rate as null while nothing has been measured yet`() =
        runBlocking<Unit> {
            val dashboard = service.dashboard(null, null, 30, 1)

            dashboard.optInRate shouldBe null
            dashboard.deliveryRate shouldBe null
            dashboard.redemptionRate shouldBe null
        }

    @Test
    fun `should count only the named branch when the owner picks one`() =
        runBlocking<Unit> {
            every { repository.dailySales(listOf(FAMILIA_URBAN.id), any(), any()) } returns
                listOf(BranchDaySales(FAMILIA_URBAN.id, TODAY, 1_250_000, 9))

            val dashboard = service.dashboard(FAMILIA_URBAN.id, null, 30, 1)

            dashboard.branchId shouldBe FAMILIA_URBAN.id
            dashboard.branchRows.map { it.branch.id } shouldBe listOf(FAMILIA_URBAN.id)
            dashboard.revenueToday shouldBe 1_250_000
            dashboard.txToday shouldBe 9
            verify { repository.optIn(listOf(FAMILIA_URBAN.id)) }
        }

    @Test
    fun `should reject a branch that does not exist`() =
        runBlocking<Unit> {
            assertThrows<NotFoundException> { service.dashboard(UUID.randomUUID(), null, 30, 1) }
        }

    @Test
    fun `should add up every branch when none is named`() =
        runBlocking<Unit> {
            every { repository.dailySales(any(), any(), any()) } returns
                listOf(
                    BranchDaySales(FAMILIA_URBAN.id, TODAY, 1_000_000, 8),
                    BranchDaySales(NAROGONG.id, TODAY, 400_000, 3),
                    BranchDaySales(FAMILIA_URBAN.id, TODAY.minusDays(1), 900_000, 7),
                )

            val dashboard = service.dashboard(null, null, 30, 1)

            dashboard.revenueToday shouldBe 1_400_000
            dashboard.txToday shouldBe 11
            dashboard.revenueYesterday shouldBe 900_000
            dashboard.branchRows.map { it.revenue } shouldBe listOf(1_000_000, 400_000)
        }

    @Test
    fun `should draw seven chart days oldest first even with no sales`() =
        runBlocking<Unit> {
            every { repository.dailySales(any(), any(), any()) } returns
                listOf(BranchDaySales(FAMILIA_URBAN.id, TODAY, 1_000_000, 8))

            val chart = service.dashboard(null, null, 30, 1).chart

            chart.size shouldBe 7
            chart.first().date shouldBe LocalDate.parse("2026-09-09")
            chart.last().date shouldBe TODAY
            chart.first().total shouldBe 0
            chart.last().total shouldBe 1_000_000
            chart
                .map {
                    it.branches.map { row ->
                        row.branchId
                    }
                }.all { it == listOf(FAMILIA_URBAN.id, NAROGONG.id) } shouldBe
                true
        }

    @Test
    fun `should compare revenue against each branch's own target`() =
        runBlocking<Unit> {
            every { repository.dailySales(any(), any(), any()) } returns
                listOf(BranchDaySales(FAMILIA_URBAN.id, TODAY, 2_600_000, 20))

            val rows = service.dashboard(null, null, 30, 1).branchRows

            rows.first().targetRatio shouldBe 0.5
            rows.last().targetRatio shouldBe 0.0
        }

    @Test
    fun `should count orders left ready for pickup and the stale ones among them`() =
        runBlocking<Unit> {
            every { repository.readyForPickup(any(), NOW.minus(Duration.ofHours(72))) } returns
                mapOf(FAMILIA_URBAN.id to ReadyCounts(6, 2), NAROGONG.id to ReadyCounts(3, 0))

            val dashboard = service.dashboard(null, null, 30, 1)

            dashboard.readyForPickup shouldBe 9
            dashboard.staleReady shouldBe 2
            dashboard.branchRows.map { it.readyForPickup } shouldBe listOf(6, 3)
        }

    @Test
    fun `should read the audit window from the dashboard date, not from now`() =
        runBlocking<Unit> {
            service.dashboard(null, LocalDate.parse("2026-09-10"), 30, 7)

            verify {
                repository.audit(
                    any(),
                    Instant.parse("2026-09-03T17:00:00Z"),
                    Instant.parse("2026-09-10T17:00:00Z"),
                    ReportService.AUDIT_LIMIT,
                )
            }
        }

    @Test
    fun `should flag a truncated audit list instead of dropping entries silently`() =
        runBlocking<Unit> {
            every { repository.audit(any(), any(), any(), any()) } returns
                List(ReportService.AUDIT_LIMIT + 1) { auditRecord(it.toLong()) }

            val dashboard = service.dashboard(null, null, 30, 1)

            dashboard.audit.size shouldBe ReportService.AUDIT_LIMIT
            dashboard.auditTruncated shouldBe true
        }

    @Test
    fun `should not flag truncation when the window fits`() =
        runBlocking<Unit> {
            every { repository.audit(any(), any(), any(), any()) } returns listOf(auditRecord(1))

            val dashboard = service.dashboard(null, null, 30, 1)

            dashboard.audit.size shouldBe 1
            dashboard.auditTruncated shouldBe false
        }

    private fun auditRecord(id: Long) =
        AuditRecord(
            id = id,
            branchId = FAMILIA_URBAN.id,
            staffId = UUID.randomUUID(),
            actorName = "Siti N. (kasir)",
            actionType = "ORDER_CREATED",
            action = "Buat order FMU-0915-001",
            entityType = "order",
            entityId = UUID.randomUUID().toString(),
            createdAt = NOW,
        )
}
