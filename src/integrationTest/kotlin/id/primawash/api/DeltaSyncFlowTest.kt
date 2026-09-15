package id.primawash.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The tablet's Room cache (PRD §8.10): bootstrap, the change feed of V5, and the heartbeat. */
class DeltaSyncFlowTest {
    // 09.00 WIB on 15 September 2026.
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private fun JsonObject.ids(collection: String) = array(collection).objects().map { it.string("id") }

    private suspend fun TestApi.changes(
        till: Till,
        cursor: String,
    ): JsonObject {
        val response = get("/sync/changes?since=$cursor", till.token)
        response.status shouldBe HttpStatusCode.OK
        return response.json()
    }

    @Test
    fun `should start a tablet with everything it caches and never pin data`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val order = api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "2", 10_000))

            val response = api.get("/sync/bootstrap", siti.token)

            response.status shouldBe HttpStatusCode.OK
            val data = response.json().obj("data")
            data.array("branches").size shouldBe 3
            // A cashier caches their own branch's staff plus the owner; no PIN, no login times.
            data.array("staff").objects().map { it.string("name") } shouldContainExactlyInAnyOrder
                listOf("Siti Nurhaliza", "Bagas Ardhana", "Raka Prasetyo")
            data
                .array("staff")
                .objects()
                .first()
                .keys shouldBe
                setOf("id", "name", "shortName", "role", "branchId", "active")
            // Inactive rows too, so the client can hide them.
            data.array("services").size shouldBe 16
            data.array("rewards").size shouldBe 3
            data.array("loyaltyRates").size shouldBe 1
            data.ids("orders") shouldContainExactly listOf(order.string("id"))
            data.array("shifts").size shouldBe 1
        }

    @Test
    fun `should send only what changed since the cursor, in the scope of the session`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val nia = api.till("BTR", "2468")
            val owner = api.ownerToken()
            val ownerTill = Till(owner, "")
            // Logins write staff rows; the cursors are taken after every tablet is signed in.
            val cursor = api.get("/sync/bootstrap", siti.token).json().string("cursor")
            val ownerCursor = api.get("/sync/bootstrap", owner).json().string("cursor")

            val quiet = api.changes(siti, cursor)
            quiet.getValue("hasMore").toString() shouldBe "false"
            listOf("branches", "staff", "services", "rewards", "loyaltyRates", "customers", "orders", "shifts")
                .forEach { quiet.obj("changes").array(it).shouldBeEmpty() }

            val sitiShift = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val niaShift = api.openShift(nia, "Nia Ramadhani", "2468").string("id")
            val tebetOrder = api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "1", 10_000)).string("id")
            val bintaroOrder = api.walkIn(nia, Line(Tx.serviceId("Cuci Setrika"), "1", 10_000)).string("id")
            val dewi = api.registerCustomer(nia, "Dewi Anggraini", "0812-3390-4471", optIn = false)
            val bedCover = Tx.serviceId("Bed Cover")
            api
                .patch("/services/prices", """{"prices":{"$bedCover":40000}}""", owner)
                .status shouldBe HttpStatusCode.OK

            val changes = api.changes(siti, cursor)
            val feed = changes.obj("changes")
            feed.ids("services") shouldContainExactly listOf(bedCover)
            feed
                .array("services")
                .objects()
                .single()
                .long("price") shouldBe 40_000
            feed.ids("customers") shouldContainExactly listOf(dewi)
            // Orders follow the tablet's branch; a cashier's shifts are their branch's only.
            feed.ids("orders") shouldContainExactly listOf(tebetOrder)
            feed.ids("orders") shouldNotContain bintaroOrder
            feed.ids("shifts") shouldContainExactly listOf(sitiShift)

            val ownerFeed = api.changes(ownerTill, ownerCursor).obj("changes")
            ownerFeed.ids("shifts") shouldContainExactlyInAnyOrder listOf(sitiShift, niaShift)

            // The next call from the new cursor no longer repeats what was delivered.
            val next = api.changes(siti, changes.string("nextCursor")).obj("changes")
            next.ids("orders").shouldBeEmpty()
            next.ids("services").shouldBeEmpty()
        }

    @Test
    fun `should deliver a change whose transaction commits after the cursor was handed out`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val cursor = api.get("/sync/bootstrap", siti.token).json().string("cursor")
            val bedCover = Tx.serviceId("Bed Cover")

            PostgresSupport.dataSource.connection.use { slow ->
                slow.autoCommit = false
                slow.createStatement().use { it.execute("UPDATE services SET price = 41000 WHERE id = '$bedCover'") }

                // The feed is read while the owner's price change is still uncommitted: invisible for now.
                val during = api.changes(siti, cursor)
                during.obj("changes").ids("services").shouldBeEmpty()

                slow.commit()

                // An updated_at cursor taken now would be past the change; the transaction-id watermark is not.
                val after = api.changes(siti, during.string("nextCursor")).obj("changes")
                after.ids("services") shouldContain bedCover
            }
        }

    @Test
    fun `should page a large feed and resume exactly after the last row served`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val cursor = api.get("/sync/bootstrap", siti.token).json().string("cursor")
            val tebet = ApiTestSupport.branchId("TBT")
            PostgresSupport.withConnection { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO customers (name, phone, phone_digits, home_branch_id) " +
                            "SELECT 'Customer ' || n, '0812-0000-' || lpad(n::text, 4, '0'), " +
                            "'08120000' || lpad(n::text, 4, '0'), '$tebet' FROM generate_series(1, 620) n",
                    )
                }
            }

            val first = api.changes(siti, cursor)
            first.getValue("hasMore").toString() shouldBe "true"
            val firstIds = first.obj("changes").ids("customers")
            firstIds.size shouldBe 500

            val second = api.changes(siti, first.string("nextCursor"))
            second.getValue("hasMore").toString() shouldBe "false"
            val secondIds = second.obj("changes").ids("customers")
            secondIds.size shouldBe 120
            (firstIds + secondIds).toSet().size shouldBe 620
        }

    @Test
    fun `should refuse a cursor it did not issue`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")

            api
                .get(
                    "/sync/changes?since=bukan-cursor",
                    siti.token,
                ).shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
            api.get("/sync/changes", siti.token).shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }

    @Test
    fun `should record the tablet heartbeat for the owner's pending sync count`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")

            val beat =
                api.post("/devices/heartbeat", """{"pendingCount":3,"appVersion":"1.4.1","online":true}""", siti.token)

            beat.status shouldBe HttpStatusCode.OK
            beat.json().string("serverTime") shouldBe "2026-09-15T02:00:00.000Z"
            scalar("SELECT pending_count || '/' || app_version FROM devices WHERE id = '${siti.deviceId}'") shouldBe
                "3/1.4.1"
            api
                .post("/devices/heartbeat", """{"pendingCount":-1}""", siti.token)
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
            api
                .post("/devices/heartbeat", """{"appVersion":"1.4.1"}""", siti.token)
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }
}
