package id.primawash.api

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Acceptance scenarios #3, #6, #9 and #10 of PRD §16 with the requests really in flight together
 * (CLAUDE.md "Testing Conventions"): [Tx.race] releases every call at once and the database decides.
 */
class ConcurrentTransactionsTest {
    // 09.00 WIB on 15 September 2026.
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private val cuciSetrika get() = Tx.serviceId("Cuci Setrika")

    @Test
    fun `should open one shift when two tablets open the branch at the same moment`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val bagas = api.till("TBT", "5678")
            val sitiProof = api.staffProof(siti, "Siti Nurhaliza", "1234")
            val bagasProof = api.staffProof(bagas, "Bagas Ardhana", "5678")

            val responses =
                Tx.race(
                    {
                        api.post(
                            "/shifts",
                            """{"openingCash":500000,"staffProof":"$sitiProof"}""",
                            siti.token,
                            idempotencyKey = Tx.key(),
                        )
                    },
                    {
                        api.post(
                            "/shifts",
                            """{"openingCash":500000,"staffProof":"$bagasProof"}""",
                            bagas.token,
                            idempotencyKey = Tx.key(),
                        )
                    },
                )

            responses.map { it.status } shouldContainExactlyInAnyOrder
                listOf(HttpStatusCode.Created, HttpStatusCode.Conflict)
            responses.first { it.status == HttpStatusCode.Conflict }.shouldFailWith(
                HttpStatusCode.Conflict,
                "SHIFT_ALREADY_OPEN",
            )
            ApiTestSupport.count("SELECT count(*) FROM shifts") shouldBe 1
            ApiTestSupport.count("SELECT count(*) FROM cash_entries WHERE kind = 'OPENING'") shouldBe 1
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'SHIFT_OPENED'") shouldBe 1
        }

    @Test
    fun `should record one order when a retry races the original with the same key`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val body = Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "3", 10_000)), 30_000)
            val key = Tx.key()

            val responses = Tx.race({ api.placeOrder(siti, body, key) }, { api.placeOrder(siti, body, key) })

            responses.map { it.status } shouldBe listOf(HttpStatusCode.Created, HttpStatusCode.Created)
            responses[0].json() shouldBe responses[1].json()
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 1
            scalar("SELECT cash_sales || '/' || tx_count FROM shifts") shouldBe "30000/1"
            scalar("SELECT revenue || '/' || tx_count FROM daily_sales") shouldBe "30000/1"
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'ORDER_CREATED'") shouldBe 1
        }

    @Test
    fun `should give two orders saved together in one branch different numbers`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val bagas = api.till("TBT", "5678")
            api.openShift(siti, "Siti Nurhaliza", "1234")

            val responses =
                Tx.race(
                    { api.placeOrder(siti, Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "1", 10_000)), 10_000)) },
                    { api.placeOrder(bagas, Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "2", 10_000)), 20_000)) },
                )

            responses.map { it.status } shouldBe listOf(HttpStatusCode.Created, HttpStatusCode.Created)
            responses.map { it.json().obj("order").string("number") } shouldContainExactlyInAnyOrder
                listOf("TBT-0915-001", "TBT-0915-002")
            scalar("SELECT cash_sales || '/' || tx_count FROM shifts") shouldBe "30000/2"
        }

    @Test
    fun `should let one of two tablets advance the same order`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val bagas = api.till("TBT", "5678")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = true)
            val id =
                api
                    .placeOrder(
                        siti,
                        Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "2", 10_000)), 20_000, customerId = dewi),
                    ).json()
                    .obj("order")
                    .string("id")
            api.advance(siti, id, "DITERIMA").status shouldBe HttpStatusCode.OK

            val responses = Tx.race({ api.advance(siti, id, "PROSES") }, { api.advance(bagas, id, "PROSES") })

            responses.map { it.status } shouldContainExactlyInAnyOrder
                listOf(HttpStatusCode.OK, HttpStatusCode.Conflict)
            responses
                .first { it.status == HttpStatusCode.Conflict }
                .shouldFailWith(HttpStatusCode.Conflict, "STATUS_CHANGED")
                .obj("details")
                .obj("order")
                .string("status") shouldBe "SIAP"
            scalar("SELECT status || '/' || version FROM orders WHERE id = '$id'") shouldBe "SIAP/3"
            ApiTestSupport.count(
                "SELECT count(*) FROM order_events WHERE order_id = '$id' AND to_status = 'SIAP'",
            ) shouldBe 1
            ApiTestSupport.count(
                "SELECT count(*) FROM wa_messages WHERE order_id = '$id' AND template = 'STATUS_SIAP_DIAMBIL'",
            ) shouldBe 1
        }

    @Test
    fun `should record each offline transaction once when the queue is sent twice at the same time`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val batch =
                Tx.syncBody(
                    Tx.offline(
                        capturedAt = "2026-09-15T01:10:00.000Z",
                        lines = listOf(Line(cuciSetrika, "1", 10_000)),
                        shiftId = shift,
                    ),
                    Tx.offline(
                        capturedAt = "2026-09-15T01:20:00.000Z",
                        lines = listOf(Line(cuciSetrika, "2", 10_000)),
                        shiftId = shift,
                    ),
                    Tx.offline(
                        capturedAt = "2026-09-15T01:30:00.000Z",
                        lines = listOf(Line(cuciSetrika, "3", 10_000)),
                        shiftId = shift,
                    ),
                )

            // Two keys: the app lost track and resent the whole queue, not a transport retry.
            val responses = Tx.race({ api.sync(siti, batch) }, { api.sync(siti, batch) })

            responses.map { it.status } shouldBe listOf(HttpStatusCode.OK, HttpStatusCode.OK)
            val statuses =
                responses.flatMap { response ->
                    response
                        .json()
                        .array("results")
                        .objects()
                        .map { it.string("status") }
                }
            statuses.count { it == "CREATED" } shouldBe 3
            statuses.count { it == "DUPLICATE" } shouldBe 3
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 3
            scalar("SELECT cash_sales || '/' || tx_count FROM shifts") shouldBe "60000/3"
            scalar("SELECT revenue || '/' || tx_count FROM daily_sales") shouldBe "60000/3"
            ApiTestSupport.count("SELECT count(DISTINCT number) FROM orders") shouldBe 3
        }
}
