package id.primawash.api

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Orders online (PRD §8.6, §9.1, §9.2) — acceptance scenarios #1, #2, #3, #4 and #8 of §16. */
class OrderFlowTest {
    // 09.00 WIB on 15 September 2026: business date 2026-09-15, order numbers TBT-0915-…
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private val cuciSetrika get() = Tx.serviceId("Cuci Setrika")

    @Test
    fun `should record a paid order and every side effect in one go`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = true)
            Tx.givePoints(dewi, 2_340)
            val reward = Tx.rewardId("Gratis Cuci Kering 2 kg")

            val response =
                api.placeOrder(
                    siti,
                    Tx.orderBody(
                        Tx.key(),
                        listOf(Line(cuciSetrika, "4.5", 10_000)),
                        31_000,
                        customerId = dewi,
                        rewardId = reward,
                        note = "Pisahkan baju putih",
                    ),
                )

            response.status shouldBe HttpStatusCode.Created
            val body = response.json()
            val order = body.obj("order")
            val number = order.string("number")
            number shouldBe "TBT-0915-001"
            order.string("businessDate") shouldBe "2026-09-15"
            order.string("status") shouldBe "DITERIMA"
            order.string("waStatus") shouldBe "MENUNGGU"
            order.string("source") shouldBe "ONLINE"
            order.long("subtotal") shouldBe 45_000
            order.long("discount") shouldBe 14_000
            order.long("total") shouldBe 31_000
            order.long("earnedPoints") shouldBe 300
            order.long("redeemedPoints") shouldBe 1_500
            order.string("customerName") shouldBe "Dewi Anggraini"
            body.obj("customer").long("points") shouldBe 1_140
            body.obj("customer").long("visits") shouldBe 1
            val orderId = order.string("id")

            // 1–2. Order, snapshot items, event.
            ApiTestSupport.count("SELECT count(*) FROM order_items WHERE order_id = '$orderId'") shouldBe 1
            ApiTestSupport.count(
                "SELECT count(*) FROM order_events WHERE order_id = '$orderId' AND type = 'CREATED'",
            ) shouldBe 1
            // 3. Ledger with running balance, cached balance and visit.
            ApiTestSupport.count(
                "SELECT count(*) FROM points_ledger WHERE order_id = '$orderId' AND " +
                    "((type = 'EARN' AND delta = 300 AND balance_after = 2640) OR " +
                    "(type = 'REDEEM' AND delta = -1500 AND balance_after = 1140))",
            ) shouldBe 2
            scalar("SELECT points_balance || '/' || visits FROM customers WHERE id = '$dewi'") shouldBe "1140/1"
            // 4. Reward usage.
            scalar("SELECT used_count FROM rewards WHERE id = '$reward'") shouldBe "1"
            // 5–6. Shift totals and the SALE cash entry.
            scalar(
                "SELECT cash_sales || '/' || transfer_sales || '/' || tx_count || '/' || points_issued " +
                    "FROM shifts WHERE id = '${shift.string("id")}'",
            ) shouldBe "31000/0/1/300"
            ApiTestSupport.count(
                "SELECT count(*) FROM cash_entries WHERE kind = 'SALE' AND amount = 31000 AND " +
                    "label = 'Pembayaran tunai $number' AND note = 'Dewi Anggraini' AND order_id = '$orderId'",
            ) shouldBe 1
            // 7. Daily sales on the business date.
            scalar(
                "SELECT revenue || '/' || tx_count FROM daily_sales WHERE business_date = '2026-09-15'",
            ) shouldBe "31000/1"
            // 8. Audit in the client's wording, plus the redemption row.
            ApiTestSupport.auditCount("Transaksi $number · Rp31.000 · Tunai · redeem 1.500 poin") shouldBe 1
            ApiTestSupport.count(
                "SELECT count(*) FROM audit_log WHERE action_type = 'REWARD_REDEEMED' AND entity_id = '$orderId'",
            ) shouldBe 1
            // 9. Outbox: consent confirmation on the first order, then the receipt — queued, never sent (T6).
            waTemplates(orderId) shouldContainExactly listOf("OPT_IN_CONFIRM:QUEUED", "STRUK_DIGITAL:QUEUED")

            // A second order: the receipt only — consent was confirmed once.
            val second =
                api.placeOrder(
                    siti,
                    Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "1", 10_000)), 10_000, customerId = dewi),
                )
            second.status shouldBe HttpStatusCode.Created
            val secondOrder = second.json().obj("order")
            secondOrder.string("number") shouldBe "TBT-0915-002"
            waTemplates(secondOrder.string("id")) shouldContainExactly listOf("STRUK_DIGITAL:QUEUED")
        }

    @Test
    fun `should reject an order when no shift is open`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val error =
                api
                    .placeOrder(siti, Tx.orderBody(Tx.key(), emptyList(), 0))
                    .shouldFailWith(HttpStatusCode.UnprocessableEntity, "SHIFT_NOT_OPEN")
            error.string("message") shouldBe
                "Shift Cabang Tebet belum dibuka — buka shift dulu sebelum mencatat transaksi."
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 0
        }

    @Test
    fun `should check the cart in the documented order and store nothing on failure`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = true)
            Tx.givePoints(dewi, 3_000)
            val gorden = Tx.serviceId("Gorden")

            suspend fun reject(
                body: String,
                status: HttpStatusCode,
                code: String,
            ) = api.placeOrder(siti, body).shouldFailWith(status, code)

            reject(Tx.orderBody(Tx.key(), emptyList(), 0), HttpStatusCode.UnprocessableEntity, "EMPTY_CART")
                .string("message") shouldBe "Belum ada layanan di order ini."
            // An inactive service beats a wrong price and a wrong total.
            reject(
                Tx.orderBody(Tx.key(), listOf(Line(gorden, "1", 1)), 1),
                HttpStatusCode.UnprocessableEntity,
                "INVALID_ITEM",
            ).string("message") shouldBe "Layanan Gorden sudah tidak tersedia."
            reject(
                Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "4.3", 10_000)), 43_000),
                HttpStatusCode.UnprocessableEntity,
                "INVALID_ITEM",
            ).string("message") shouldBe "Jumlah Cuci Setrika tidak valid."
            reject(
                Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "1000", 10_000)), 10_000_000),
                HttpStatusCode.UnprocessableEntity,
                "INVALID_ITEM",
            )
            // A changed price beats an ineligible reward.
            reject(
                Tx.orderBody(
                    Tx.key(),
                    listOf(Line(cuciSetrika, "1", 9_000)),
                    9_000,
                    rewardId = Tx.rewardId("Diskon Rp10.000"),
                ),
                HttpStatusCode.Conflict,
                "PRICE_CHANGED",
            )
            reject(
                Tx.orderBody(
                    Tx.key(),
                    listOf(Line(cuciSetrika, "5", 10_000)),
                    40_000,
                    rewardId = Tx.rewardId("Diskon Rp10.000"),
                ),
                HttpStatusCode.UnprocessableEntity,
                "INSUFFICIENT_POINTS",
            ).string("message") shouldBe "Saldo poin belum cukup untuk reward ini."
            // T9: the minimum spend is enforced server-side.
            reject(
                Tx.orderBody(
                    Tx.key(),
                    listOf(Line(cuciSetrika, "4.5", 10_000)),
                    20_000,
                    customerId = dewi,
                    rewardId = Tx.rewardId("Diskon Rp25.000"),
                ),
                HttpStatusCode.UnprocessableEntity,
                "REWARD_NOT_ELIGIBLE",
            ).string("message") shouldBe "Reward ini butuh minimum belanja Rp75.000."
            val mismatch =
                reject(
                    Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "4.5", 10_000)), 40_000),
                    HttpStatusCode.Conflict,
                    "TOTAL_MISMATCH",
                )
            mismatch.string("message") shouldBe "Total berubah — muat ulang keranjang."
            mismatch.obj("details").long("total") shouldBe 45_000

            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 0
            ApiTestSupport.count("SELECT count(*) FROM wa_messages") shouldBe 0
            scalar("SELECT tx_count FROM shifts") shouldBe "0"
            scalar("SELECT points_balance FROM customers WHERE id = '$dewi'") shouldBe "3000"
        }

    @Test
    fun `should answer a changed price with the new price list and keep old orders at their price`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val before = api.walkIn(siti, Line(cuciSetrika, "2", 10_000))

            api
                .patch("/services/prices", """{"prices":{"$cuciSetrika":11000}}""", api.ownerToken())
                .status shouldBe HttpStatusCode.OK
            val error =
                api
                    .placeOrder(siti, Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "4.5", 10_000)), 45_000))
                    .shouldFailWith(HttpStatusCode.Conflict, "PRICE_CHANGED")

            error.string("message") shouldBe
                "Harga Cuci Setrika baru saja diubah owner — cek ulang total sebelum bayar."
            val repriced =
                error
                    .obj("details")
                    .array("services")
                    .objects()
                    .single()
            repriced.string("id") shouldBe cuciSetrika
            repriced.long("price") shouldBe 11_000
            val kept = api.get("/orders/${before.string("id")}", siti.token).json().obj("order")
            kept
                .array("items")
                .objects()
                .single()
                .long("unitPrice") shouldBe 10_000
            kept.long("total") shouldBe 20_000
        }

    @Test
    fun `should replay a retried order and never record a client transaction twice`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val clientTxId = Tx.key()
            val body = Tx.orderBody(clientTxId, listOf(Line(cuciSetrika, "3", 10_000)), 30_000)
            val key = Tx.key()

            val first = api.placeOrder(siti, body, key)
            val retry = api.placeOrder(siti, body, key)

            first.status shouldBe HttpStatusCode.Created
            retry.status shouldBe HttpStatusCode.Created
            // Same JSON (the stored copy may order keys differently).
            retry.json() shouldBe first.json()
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 1
            api
                .placeOrder(siti, Tx.orderBody(clientTxId, listOf(Line(cuciSetrika, "4", 10_000)), 40_000), key)
                .shouldFailWith(HttpStatusCode.Conflict, "IDEMPOTENCY_MISMATCH")
            // The same cart under a fresh key (the app lost the key): still one order, and the client learns which.
            val duplicate =
                api.placeOrder(siti, body).shouldFailWith(HttpStatusCode.Conflict, "DUPLICATE_TRANSACTION")
            duplicate.obj("details").obj("order").string("number") shouldBe "TBT-0915-001"
            ApiTestSupport.count("SELECT count(*) FROM orders") shouldBe 1
            scalar("SELECT tx_count FROM shifts") shouldBe "1"
        }

    @Test
    fun `should advance one step at a time and queue the ready message for an opted in customer`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = true)
            val order =
                api
                    .placeOrder(
                        siti,
                        Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "2", 10_000)), 20_000, customerId = dewi),
                    ).json()
                    .obj("order")
            val id = order.string("id")
            val number = order.string("number")

            api
                .advance(siti, id, "DITERIMA")
                .json()
                .obj("order")
                .string("status") shouldBe "PROSES"
            val stale = api.advance(siti, id, "DITERIMA").shouldFailWith(HttpStatusCode.Conflict, "STATUS_CHANGED")
            stale.obj("details").obj("order").string("status") shouldBe "PROSES"
            api
                .advance(siti, id, "SIAP")
                .shouldFailWith(HttpStatusCode.Conflict, "STATUS_CHANGED")

            clock.advance(java.time.Duration.ofMinutes(30))
            val ready = api.advance(siti, id, "PROSES").json()
            ready.string("result") shouldBe "ADVANCED"
            ready.getValue("notificationQueued").toString() shouldBe "true"
            ready.obj("order").string("status") shouldBe "SIAP"
            ready.obj("order").string("waStatus") shouldBe "MENUNGGU"
            ready.obj("order").string("statusChangedAt") shouldBe "2026-09-15T02:30:00.000Z"
            ready.obj("order").long("version") shouldBe 3
            waTemplates(id) shouldContainExactly
                listOf("OPT_IN_CONFIRM:QUEUED", "STRUK_DIGITAL:QUEUED", "STATUS_SIAP_DIAMBIL:QUEUED")
            ApiTestSupport.auditCount("Ubah status $number → Siap diambil · WA dijadwalkan") shouldBe 1

            api
                .advance(siti, id, "SIAP")
                .json()
                .obj("order")
                .string("status") shouldBe "SELESAI"
            val done = api.advance(siti, id, "SELESAI")
            done.status shouldBe HttpStatusCode.OK
            done.json().string("result") shouldBe "ALREADY_COMPLETED"

            val detail = api.get("/orders/$id", siti.token).json()
            detail.array("events").objects().map { "${it.string("type")}:${it["toStatus"]}" } shouldContainExactly
                listOf(
                    "CREATED:\"DITERIMA\"",
                    "STATUS_CHANGED:\"PROSES\"",
                    "STATUS_CHANGED:\"SIAP\"",
                    "STATUS_CHANGED:\"SELESAI\"",
                )
            ApiTestSupport.count("SELECT count(*) FROM wa_messages WHERE status <> 'QUEUED'") shouldBe 0
        }

    @Test
    fun `should read consent when the order becomes ready, not when it was paid`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = true)
            val order =
                api
                    .placeOrder(
                        siti,
                        Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "2", 10_000)), 20_000, customerId = dewi),
                    ).json()
                    .obj("order")
            val id = order.string("id")
            api.patch("/customers/$dewi", """{"optIn":false}""", siti.token).status shouldBe HttpStatusCode.OK

            api.advance(siti, id, "DITERIMA")
            val ready = api.advance(siti, id, "PROSES").json()

            ready.getValue("notificationQueued").toString() shouldBe "false"
            ready.obj("order").string("waStatus") shouldBe "BELUM_OPTIN"
            ApiTestSupport.auditCount("Ubah status ${order.string("number")} → Siap diambil") shouldBe 1
            // Opting out cancelled the queued receipt; nothing new was queued for the ready status.
            waTemplates(id) shouldContainExactly listOf("OPT_IN_CONFIRM:CANCELLED", "STRUK_DIGITAL:CANCELLED")
            ApiTestSupport.count(
                "SELECT count(*) FROM order_events WHERE order_id = '$id' AND type = 'WA_STATUS_CHANGED'",
            ) shouldBe 1

            // A walk-in never gets a row at all.
            val walkIn = api.walkIn(siti, Line(cuciSetrika, "1", 10_000))
            walkIn.string("waStatus") shouldBe "BELUM_OPTIN"
            api.advance(siti, walkIn.string("id"), "DITERIMA")
            api
                .advance(siti, walkIn.string("id"), "PROSES")
                .json()
                .getValue("notificationQueued")
                .toString() shouldBe
                "false"
            waTemplates(walkIn.string("id")) shouldBe emptyList()
        }

    @Test
    fun `should keep a cashier inside the branch of the token`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val nia = api.till("BTR", "2468")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            api.openShift(nia, "Nia Ramadhani", "2468")
            val tebetOrder = api.walkIn(siti, Line(cuciSetrika, "1", 10_000))
            val bintaroOrder = api.walkIn(nia, Line(cuciSetrika, "2", 10_000))
            bintaroOrder.string("number") shouldBe "BTR-0915-001"

            api
                .get(
                    "/orders/${tebetOrder.string("id")}",
                    nia.token,
                ).shouldFailWith(HttpStatusCode.Forbidden, "BRANCH_SCOPE")
            api
                .advance(
                    nia,
                    tebetOrder.string("id"),
                    "DITERIMA",
                ).shouldFailWith(HttpStatusCode.Forbidden, "BRANCH_SCOPE")
            val niaList = api.get("/orders?branchId=${ApiTestSupport.branchId("TBT")}", nia.token).json()
            niaList.array("items").objects().map { it.string("number") } shouldContainExactly listOf("BTR-0915-001")

            val owner = api.ownerToken()
            api
                .get("/orders?scope=all", owner)
                .json()
                .array("items")
                .size shouldBe 2
            api
                .get("/orders?branchId=${ApiTestSupport.branchId("BTR")}", owner)
                .json()
                .array("items")
                .objects()
                .single()
                .string("number") shouldBe "BTR-0915-001"
        }

    @Test
    fun `should list orders newest first with counts, search and pages`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val dewi = api.registerCustomer(siti, "Dewi Anggraini", "0812-3390-4471", optIn = false)
            api.get("/orders/next-number", siti.token).json().string("number") shouldBe "TBT-0915-001"

            val first =
                api
                    .placeOrder(
                        siti,
                        Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "2", 10_000)), 20_000, customerId = dewi),
                    ).json()
                    .obj("order")
            clock.advance(java.time.Duration.ofMinutes(1))
            val second = api.walkIn(siti, Line(cuciSetrika, "1", 10_000))
            clock.advance(java.time.Duration.ofMinutes(1))
            api.walkIn(siti, Line(cuciSetrika, "3", 10_000))
            api.advance(siti, second.string("id"), "DITERIMA")
            api.get("/orders/next-number", siti.token).json().string("number") shouldBe "TBT-0915-004"

            val all = api.get("/orders", siti.token).json()
            all.array("items").objects().map { it.string("number") } shouldContainExactly
                listOf("TBT-0915-003", "TBT-0915-002", "TBT-0915-001")
            all.obj("counts").let {
                it.long("ALL") shouldBe 3
                it.long("DITERIMA") shouldBe 2
                it.long("PROSES") shouldBe 1
            }
            api
                .get("/orders?status=PROSES", siti.token)
                .json()
                .array("items")
                .objects()
                .single()
                .string("id") shouldBe
                second.string("id")
            api
                .get("/orders?q=dewi", siti.token)
                .json()
                .array("items")
                .objects()
                .single()
                .string("id") shouldBe
                first.string("id")
            api
                .get("/orders?q=3390", siti.token)
                .json()
                .array("items")
                .size shouldBe 1
            api
                .get("/orders?q=0915-002", siti.token)
                .json()
                .array("items")
                .size shouldBe 1

            val page = api.get("/orders?limit=2", siti.token).json()
            page.array("items").size shouldBe 2
            val cursor = page.string("nextCursor")
            val rest = api.get("/orders?limit=2&cursor=$cursor", siti.token).json()
            rest
                .array("items")
                .objects()
                .single()
                .string("number") shouldBe "TBT-0915-001"
            rest.isNull("nextCursor") shouldBe true
            api.get("/orders?cursor=rusak", siti.token).shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
            api.get("/orders?from=15-09-2026", siti.token).shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }

    @Test
    fun `should require an idempotency key on a new order`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            api
                .post("/orders", Tx.orderBody(Tx.key(), emptyList(), 0), siti.token)
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
            api
                .placeOrder(siti, """{"clientTxId":"bukan-uuid","items":[],"payment":"TUNAI","expectedTotal":0}""")
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }

    private fun waTemplates(orderId: String): List<String> =
        PostgresSupport.withConnection { connection ->
            connection
                .prepareStatement(
                    "SELECT template || ':' || status FROM wa_messages WHERE order_id = ?::uuid ORDER BY queued_at, " +
                        "CASE template WHEN 'OPT_IN_CONFIRM' THEN 0 WHEN 'STRUK_DIGITAL' THEN 1 ELSE 2 END",
                ).use { statement ->
                    statement.setString(1, orderId)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
        }
}
