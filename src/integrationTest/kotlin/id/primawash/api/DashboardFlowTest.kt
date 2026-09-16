package id.primawash.api

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.double

/**
 * The owner's report page (PRD §8.9, computed per §9.4) against real Postgres: the figures are read
 * back from the same `daily_sales`, `orders` and `audit_log` rows the M2 endpoints wrote.
 */
class DashboardFlowTest {
    // 09.00 WIB on 15 September 2026.
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private suspend fun TestApi.dashboard(
        owner: Till,
        query: String = "",
    ): JsonObject {
        val response = get("/reports/dashboard$query", owner.token)
        response.status shouldBe HttpStatusCode.OK
        return response.json()
    }

    @Test
    fun `should report today's sales, the branch rows and the chart from the orders just taken`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "4.5", 10_000))
            api.walkIn(siti, Line(Tx.serviceId("Cuci Kering"), "3", 7_000), payment = "TRANSFER")
            val owner = api.till("TBT", "9090")

            val dashboard = api.dashboard(owner)

            dashboard.string("date") shouldBe "2026-09-15"
            dashboard.isNull("branchId") shouldBe true
            dashboard.long("revenueToday") shouldBe 66_000
            dashboard.long("txToday") shouldBe 2
            dashboard.long("revenueYesterday") shouldBe 0

            val rows = dashboard.array("branchRows").objects()
            rows.map { it.string("code") } shouldContain "TBT"
            val tebet = rows.first { it.string("code") == "TBT" }
            tebet.long("revenue") shouldBe 66_000
            tebet.long("txCount") shouldBe 2
            tebet.obj("latestShift").string("openedByName") shouldBe "Siti Nurhaliza"

            val chart = dashboard.array("chart").objects()
            chart.size shouldBe 7
            chart.first().string("date") shouldBe "2026-09-09"
            chart.last().string("date") shouldBe "2026-09-15"
            chart.last().long("total") shouldBe 66_000
            chart.first().long("total") shouldBe 0
        }

    @Test
    fun `should default the period to the last 30 days and never report over all time`() =
        apiTest(clock) { api ->
            val owner = api.till("TBT", "9090")

            val dashboard = api.dashboard(owner)

            dashboard.long("periodDays") shouldBe 30
            dashboard.string("periodFrom") shouldBe "2026-08-17"
            dashboard.string("periodTo") shouldBe "2026-09-15"
            dashboard.long("auditRangeDays") shouldBe 1
        }

    @Test
    fun `should count an order synced today but captured yesterday as yesterday's revenue`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val response =
                api.sync(
                    siti,
                    Tx.syncBody(
                        Tx.offline(
                            capturedAt = "2026-09-14T05:00:00.000Z",
                            lines = listOf(Line(Tx.serviceId("Cuci Setrika"), "2", 10_000)),
                        ),
                    ),
                )
            response.status shouldBe HttpStatusCode.OK
            response
                .json()
                .array("results")
                .objects()
                .single()
                .string("status") shouldBe "CREATED"
            val owner = api.till("TBT", "9090")

            val dashboard = api.dashboard(owner)

            dashboard.long("revenueYesterday") shouldBe 20_000
            dashboard.long("revenueToday") shouldBe 0
            dashboard
                .array("chart")
                .objects()
                .first { it.string("date") == "2026-09-14" }
                .long("total") shouldBe 20_000
        }

    @Test
    fun `should count orders waiting to be collected and leave the delivery rate unknown until M5`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Lestari", "0812-3390-4471", optIn = true)
            val order =
                api
                    .placeOrder(
                        siti,
                        Tx.orderBody(
                            Tx.key(),
                            listOf(Line(Tx.serviceId("Cuci Setrika"), "3", 10_000)),
                            expectedTotal = 30_000,
                            customerId = dewi,
                        ),
                    ).json()
                    .obj("order")
                    .string("id")
            api.advance(siti, order, "DITERIMA").status shouldBe HttpStatusCode.OK
            api.advance(siti, order, "PROSES").status shouldBe HttpStatusCode.OK
            val owner = api.till("TBT", "9090")

            val dashboard = api.dashboard(owner)

            dashboard.long("readyForPickup") shouldBe 1
            dashboard.long("staleReady") shouldBe 0
            // The "siap diambil" row is QUEUED and nothing sends it before M5, so there is no rate yet.
            dashboard.isNull("deliveryRate") shouldBe true
            dashboard.number("optInRate") shouldBe 1.0
        }

    @Test
    fun `should share out the period's revenue per service`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "10.5", 10_000))
            api.walkIn(siti, Line(Tx.serviceId("Cuci Kering"), "5", 7_000))
            val owner = api.till("TBT", "9090")

            val services = api.dashboard(owner).array("topServices").objects()

            services.map { it.string("name") } shouldBe listOf("Cuci Setrika", "Cuci Kering")
            services.map { it.long("revenue") } shouldBe listOf(105_000, 35_000)
            services.map { it.number("share") } shouldBe listOf(0.75, 0.25)
        }

    @Test
    fun `should scope every figure to the branch the owner picks`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "4", 10_000))
            val owner = api.till("TBT", "9090")
            val bintaro = ApiTestSupport.branchId("BTR")

            val scoped = api.dashboard(owner, "?branchId=$bintaro")

            scoped.string("branchId") shouldBe bintaro.toString()
            scoped.long("revenueToday") shouldBe 0
            scoped.array("branchRows").objects().map { it.string("code") } shouldBe listOf("BTR")
            scoped.array("chart").objects().all { it.array("branches").objects().size == 1 } shouldBe true
        }

    @Test
    fun `should show the audit trail of the window, newest first`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val owner = api.till("TBT", "9090")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "1", 10_000))

            val dashboard = api.dashboard(owner)
            val audit = dashboard.array("audit").objects()

            audit.first().string("actionType") shouldBe "ORDER_CREATED"
            audit.map { it.string("actionType") } shouldContain "SHIFT_OPENED"
            audit.first().string("actorName") shouldBe "Siti N. (kasir)"
            dashboard.isNull("auditTruncated") shouldBe false
        }

    @Test
    fun `should refuse a cashier and an unknown branch`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val owner = api.till("TBT", "9090")

            api.get("/reports/dashboard", siti.token).status shouldBe HttpStatusCode.Forbidden
            api
                .get("/reports/dashboard?branchId=${java.util.UUID.randomUUID()}", owner.token)
                .status shouldBe HttpStatusCode.NotFound
            api.get("/reports/dashboard?periodDays=400", owner.token).status shouldBe HttpStatusCode.BadRequest
            api.get("/reports/dashboard?auditRangeDays=3", owner.token).status shouldBe HttpStatusCode.BadRequest
        }
}
