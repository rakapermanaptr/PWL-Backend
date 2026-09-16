package id.primawash.api

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * `POST /orders/sync` (PRD §11.2) — acceptance scenarios #5, #6 and #7 of §16. These transactions were already
 * paid: get them recorded, flag anything odd, never lose money (trap T5).
 */
class OfflineSyncFlowTest {
    // 09.00 WIB on 15 September 2026.
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private val cuciSetrika get() = Tx.serviceId("Cuci Setrika")

    private fun JsonObject.results() = array("results").objects()

    private fun JsonObject.flags() = array("flags").map { it.jsonPrimitive.content }

    @Test
    fun `should process each transaction on its own and answer a resent one as duplicate`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val first =
                Tx.offline(
                    capturedAt = "2026-09-15T01:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "3", 10_000)),
                    shiftId = shift,
                )
            val unknown =
                Tx.offline(
                    capturedAt = "2026-09-15T01:10:00.000Z",
                    lines = listOf(Line(UUID.randomUUID().toString(), "1", 10_000)),
                    shiftId = shift,
                )
            val third =
                Tx.offline(
                    capturedAt = "2026-09-15T01:20:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1.5", 10_000)),
                    shiftId = shift,
                    payment = "TRANSFER",
                )

            val response = api.sync(siti, Tx.syncBody(first, unknown, third))

            response.status shouldBe HttpStatusCode.OK
            val body = response.json()
            body.results().map { it.string("status") } shouldContainExactly listOf("CREATED", "REJECTED", "CREATED")
            val rejected = body.results()[1]
            rejected.obj("error").string("code") shouldBe "INVALID_ITEM"
            rejected.obj("error").string("message") shouldBe "Layanan yang dipilih sudah tidak tersedia."
            body.results()[0].obj("order").let {
                it.string("number") shouldBe "FMU-0915-001"
                it.string("source") shouldBe "OFFLINE_SYNC"
                it.string("capturedAt") shouldBe "2026-09-15T01:00:00.000Z"
                it.string("shiftId") shouldBe shift
                it.flags() shouldBe emptyList()
            }
            body.obj("summary").let {
                it.long("created") shouldBe 2
                it.long("duplicates") shouldBe 0
                it.long("rejected") shouldBe 1
                it.long("total") shouldBe 45_000
            }
            scalar("SELECT cash_sales || '/' || transfer_sales || '/' || tx_count FROM shifts") shouldBe "30000/15000/2"
            ApiTestSupport.auditCount(
                "Sync 2 transaksi offline Cabang Familia Urban · Rp45.000 · ID FMU-0915-001–FMU-0915-002",
            ) shouldBe 1
            ApiTestSupport.auditCount("Transaksi FMU-0915-001 · Rp30.000 · Tunai · offline") shouldBe 1

            // The tablet keeps the rejected one queued and sends it again with the first one.
            val again = api.sync(siti, Tx.syncBody(first, unknown)).json()
            again.results().map { it.string("status") } shouldContainExactly listOf("DUPLICATE", "REJECTED")
            again.results()[0].obj("order").string("number") shouldBe "FMU-0915-001"
            again.obj("summary").long("duplicates") shouldBe 1
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 2
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action LIKE 'Sync %'") shouldBe 1
        }

    @Test
    fun `should earn points at the rate in force when the transaction was captured`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = false)

            // 12.00 WIB: the owner raises the rate to 150 points per Rp10.000.
            clock.now = java.time.Instant.parse("2026-09-15T05:00:00Z")
            api
                .put("/loyalty/rate", """{"rupiahPerStep":10000,"pointsPerStep":150}""", api.ownerToken())
                .status shouldBe HttpStatusCode.OK

            // 13.00 WIB: the queue from 11.00 WIB arrives.
            clock.now = java.time.Instant.parse("2026-09-15T06:00:00Z")
            val tablet = api.till("FMU", "1234")
            val offline =
                Tx.offline(
                    capturedAt = "2026-09-15T04:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "4.5", 10_000)),
                    shiftId = shift,
                    customerId = dewi,
                )
            val order =
                api
                    .sync(tablet, Tx.syncBody(offline))
                    .json()
                    .results()
                    .single()
                    .obj("order")

            order.long("earnedPoints") shouldBe 400
            scalar("SELECT points_balance FROM customers WHERE id = '$dewi'") shouldBe "400"
            // An order placed now uses the new rate.
            val online =
                api
                    .placeOrder(
                        tablet,
                        Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "4.5", 10_000)), 45_000, customerId = dewi),
                    ).json()
                    .obj("order")
            online.long("earnedPoints") shouldBe 600
        }

    @Test
    fun `should record a transaction that arrives after its shift was closed`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val late =
                Tx.offline(
                    capturedAt = "2026-09-15T01:55:00.000Z",
                    lines = listOf(Line(cuciSetrika, "2", 10_000)),
                    shiftId = shift,
                )

            api
                .post("/shifts/$shift/close", """{"countedCash":500000}""", siti.token, idempotencyKey = Tx.key())
                .status shouldBe HttpStatusCode.OK
            val result =
                api
                    .sync(siti, Tx.syncBody(late))
                    .json()
                    .results()
                    .single()

            result.string("status") shouldBe "CREATED"
            val order = result.obj("order")
            order.string("shiftId") shouldBe shift
            order.flags() shouldContainExactly listOf("LATE_AFTER_SHIFT_CLOSE")
            ApiTestSupport.auditCount(
                "Transaksi ${order.string(
                    "number",
                )} · Rp20.000 · Tunai · offline · ditandai: masuk setelah shift ditutup",
            ) shouldBe 1
            // The frozen recap does not move; the late money is visible on the shift and in daily sales.
            scalar("SELECT recap_expected || '/' || cash_sales FROM shifts WHERE id = '$shift'") shouldBe "500000/20000"
            scalar("SELECT revenue FROM daily_sales") shouldBe "20000"

            // Once a new shift is open, a late transaction of the old shift lands there, unflagged.
            val next = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val later =
                Tx.offline(
                    capturedAt = "2026-09-15T01:56:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                    shiftId = shift,
                )
            val moved =
                api
                    .sync(siti, Tx.syncBody(later))
                    .json()
                    .results()
                    .single()
                    .obj("order")
            moved.string("shiftId") shouldBe next
            moved.flags() shouldBe emptyList()
        }

    // One queue with every kind of oddity, so the per-transaction outcome is visible side by side.
    @Suppress("LongMethod")
    @Test
    fun `should accept odd transactions with flags and reject only what cannot be recorded`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = false)
            Tx.givePoints(dewi, 5_000)
            val bagas = ApiTestSupport.staffId("Bagas Ardhana").toString()
            val nia = ApiTestSupport.staffId("Nia Ramadhani").toString()

            val repriced =
                Tx.offline(
                    capturedAt = "2026-09-15T01:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "2", 9_000)),
                    staffId = bagas,
                )
            val inactive =
                Tx.offline(
                    capturedAt = "2026-09-15T01:01:00.000Z",
                    lines = listOf(Line(Tx.serviceId("Gorden"), "1", 18_000)),
                    staffId = nia,
                )
            val stale =
                Tx.offline(
                    capturedAt = "2026-09-07T03:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                )
            val beforeMidnight =
                Tx.offline(
                    capturedAt = "2026-09-14T16:59:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                )
            val afterMidnight =
                Tx.offline(
                    capturedAt = "2026-09-14T17:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                )
            val redeem =
                Tx.offline(
                    capturedAt = "2026-09-15T01:02:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                    customerId = dewi,
                    rewardId = Tx.rewardId("Diskon Rp10.000"),
                )
            val future =
                Tx.offline(
                    capturedAt = "2026-09-15T02:10:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                )
            val malformed =
                JsonObject(Tx.offline(capturedAt = "x", lines = emptyList()).filterKeys { it != "capturedAt" })
            val unknownCustomer =
                Tx.offline(
                    capturedAt = "2026-09-15T01:03:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                    customerId = UUID.randomUUID().toString(),
                )

            val body =
                api
                    .sync(
                        siti,
                        Tx.syncBody(
                            repriced,
                            inactive,
                            stale,
                            beforeMidnight,
                            afterMidnight,
                            redeem,
                            future,
                            malformed,
                            unknownCustomer,
                        ),
                    ).json()
            val results = body.results()

            results.map { it.string("status") } shouldContainExactly
                listOf(
                    "CREATED",
                    "CREATED",
                    "CREATED",
                    "CREATED",
                    "CREATED",
                    "REJECTED",
                    "REJECTED",
                    "REJECTED",
                    "REJECTED",
                )
            results[0].obj("order").let {
                it.flags() shouldContainExactly listOf("PRICE_MISMATCH")
                it
                    .array("items")
                    .objects()
                    .single()
                    .long("unitPrice") shouldBe 9_000
                it.long("total") shouldBe 18_000
                it.string("staffId") shouldBe bagas
                it.string("shiftId") shouldBe shift
            }
            // Nia works at Narogong: the sale is attributed to the cashier who synced it.
            results[1].obj("order").let {
                it.flags() shouldContainExactly listOf("SERVICE_INACTIVE")
                it.string("staffId") shouldBe ApiTestSupport.staffId("Siti Nurhaliza").toString()
            }
            results[2].obj("order").let {
                it.flags() shouldContainExactly listOf("STALE_CAPTURE")
                it.string("number") shouldBe "FMU-0907-001"
                it.string("businessDate") shouldBe "2026-09-07"
            }
            results[3].obj("order").string("number") shouldBe "FMU-0914-001"
            // 00.00 WIB is already the 15th: third number of that day, after the two above.
            results[4].obj("order").string("number") shouldBe "FMU-0915-003"
            results[5].obj("error").let {
                it.string("code") shouldBe "REDEEM_OFFLINE"
                it.string("message") shouldBe "Redeem poin butuh koneksi — batalkan redemption atau tunggu online."
            }
            results[6].obj("error").string("code") shouldBe "CAPTURED_IN_FUTURE"
            results[7].obj("error").string("code") shouldBe "VALIDATION_ERROR"
            results[8].obj("error").string("code") shouldBe "NOT_FOUND"

            scalar("SELECT revenue FROM daily_sales WHERE business_date = '2026-09-07'") shouldBe "10000"
            scalar("SELECT revenue FROM daily_sales WHERE business_date = '2026-09-14'") shouldBe "10000"
            scalar("SELECT revenue FROM daily_sales WHERE business_date = '2026-09-15'") shouldBe "46000"
            scalar("SELECT points_balance FROM customers WHERE id = '$dewi'") shouldBe "5000"
            ApiTestSupport.auditCount(
                "Transaksi FMU-0915-001 · Rp18.000 · Tunai · offline · ditandai: harga beda dengan price list",
            ) shouldBe 1
            auditActions("Transaksi ").filter { it.contains("ditandai") }.size shouldBe 3
        }

    @Test
    fun `should use the price list of the capture time, not today's`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val captured =
                Tx.offline(
                    capturedAt = "2026-09-15T02:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                )

            clock.advance(Duration.ofMinutes(10))
            api.patch("/services/prices", """{"prices":{"$cuciSetrika":11000}}""", api.ownerToken())
            clock.advance(Duration.ofMinutes(10))
            val order =
                api
                    .sync(siti, Tx.syncBody(captured))
                    .json()
                    .results()
                    .single()
                    .obj("order")

            order.flags() shouldBe emptyList()
            order.long("total") shouldBe 10_000
        }

    @Test
    fun `should upsert customers registered offline by phone and map the tablet id`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = false)
            val rinaId = UUID.randomUUID().toString()
            val dewiOnTablet = UUID.randomUUID().toString()

            val rina =
                Tx.offline(
                    capturedAt = "2026-09-15T01:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "2", 10_000)),
                    newCustomer = Tx.newCustomer(rinaId, "Rina Kusuma", "0838-7712-4409", optIn = true),
                    payment = "TRANSFER",
                )
            val sameAsDewi =
                Tx.offline(
                    capturedAt = "2026-09-15T01:05:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                    newCustomer = Tx.newCustomer(dewiOnTablet, "Dewi A.", "081233904471", optIn = true),
                )
            val badPhone =
                Tx.offline(
                    capturedAt = "2026-09-15T01:06:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                    newCustomer = Tx.newCustomer(UUID.randomUUID().toString(), "Bayu", "0812-339", optIn = false),
                )

            val results = api.sync(siti, Tx.syncBody(rina, sameAsDewi, badPhone)).json().results()

            results[0].obj("customerIdMapping").let {
                it.string("clientId") shouldBe rinaId
                it.string("serverId") shouldBe rinaId
            }
            results[0].obj("order").long("earnedPoints") shouldBe 200
            ApiTestSupport.auditCount("Daftarkan customer Rina Kusuma (0838-7712-4409) · opt-in WA: ya") shouldBe 1
            ApiTestSupport.count(
                "SELECT count(*) FROM wa_messages WHERE customer_id = '$rinaId' AND status = 'QUEUED'",
            ) shouldBe 2

            results[1].obj("customerIdMapping").let {
                it.string("clientId") shouldBe dewiOnTablet
                it.string("serverId") shouldBe dewi
            }
            results[1].obj("order").let {
                it.string("customerId") shouldBe dewi
                it.string("customerName") shouldBe "Dewi Anggraini"
                it.flags() shouldContainExactly listOf("CUSTOMER_PHONE_MATCHED")
            }
            // The existing customer's consent wins: Dewi is not opted in, so nothing is queued for her.
            ApiTestSupport.count("SELECT count(*) FROM wa_messages WHERE customer_id = '$dewi'") shouldBe 0

            results[2].string("status") shouldBe "REJECTED"
            results[2].obj("error").string("code") shouldBe "PHONE_INVALID"
            ApiTestSupport.count("SELECT count(*) FROM customers") shouldBe 2
        }

    @Test
    fun `should replay a retried batch and refuse what has nowhere to go`() =
        apiTest(clock) { api ->
            val siti = api.till("FMU", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val batch =
                Tx.syncBody(
                    Tx.offline(capturedAt = "2026-09-15T01:00:00.000Z", lines = listOf(Line(cuciSetrika, "1", 10_000))),
                )
            val key = Tx.key()

            val first = api.sync(siti, batch, key).json()
            api.sync(siti, batch, key).json() shouldBe first
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 1

            // Narogong never opened a shift: a paid transaction there has no drawer to land in.
            val nia = api.till("NRG", "2468")
            val orphan =
                Tx.offline(
                    capturedAt = "2026-09-15T01:00:00.000Z",
                    lines = listOf(Line(cuciSetrika, "1", 10_000)),
                )
            api
                .sync(nia, Tx.syncBody(orphan))
                .json()
                .results()
                .single()
                .obj("error")
                .string("code") shouldBe
                "SHIFT_NOT_OPEN"

            val tooMany =
                Tx.syncBody(List(51) { Tx.offline(capturedAt = "2026-09-15T01:00:00.000Z", lines = emptyList()) })
            api.sync(siti, tooMany).shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }
}
