package id.primawash.api

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Shifts and the cash drawer (PRD §8.7, §9.3) — acceptance scenario #11 of §16. */
class ShiftFlowTest {
    // 09.00 WIB on 15 September 2026.
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private suspend fun TestApi.openWith(
        till: Till,
        body: String,
    ) = post("/shifts", body, till.token, idempotencyKey = Tx.key())

    @Test
    fun `should reconcile the drawer to the rupiah when closing`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val shift = api.openShift(siti, "Siti Nurhaliza", "1234", openingCash = 500_000)
            val id = shift.string("id")
            shift.long("countedCash") shouldBe 500_000
            shift.long("expectedCash") shouldBe 500_000
            shift.isNull("recap") shouldBe true

            clock.advance(Duration.ofMinutes(5))
            api.walkIn(
                siti,
                Line(Tx.serviceId("Cuci Setrika"), "223.5", 10_000),
                Line(Tx.serviceId("Cuci Kering"), "1", 7_000),
            )
            api.walkIn(siti, Line(Tx.serviceId("Bed Cover"), "2", 35_000), payment = "TRANSFER")
            clock.advance(Duration.ofMinutes(5))
            val out =
                api.post(
                    "/shifts/$id/cash-entries",
                    """{"direction":"OUT","label":"beli deterjen","amount":1180000}""",
                    siti.token,
                    idempotencyKey = Tx.key(),
                )
            out.status shouldBe HttpStatusCode.Created
            out.json().obj("entry").string("label") shouldBe "Kas keluar — beli deterjen"
            out.json().obj("entry").long("amount") shouldBe -1_180_000
            out.json().obj("entry").string("note") shouldBe "Diinput Siti Nurhaliza"
            clock.advance(Duration.ofMinutes(5))
            api
                .post(
                    "/shifts/$id/cash-entries",
                    """{"direction":"IN","label":"kembalian titipan","amount":62000}""",
                    siti.token,
                    idempotencyKey = Tx.key(),
                ).json()
                .obj("shift")
                .long("expectedCash") shouldBe 1_624_000

            val draft = api.put("/shifts/$id/counted-cash", """{"countedCash":1600000}""", siti.token)
            draft.status shouldBe HttpStatusCode.OK
            draft.json().obj("shift").long("countedCash") shouldBe 1_600_000

            val current = api.get("/shifts/current", siti.token).json()
            current.obj("shift").long("cashSales") shouldBe 2_242_000
            current.obj("shift").long("transferSales") shouldBe 70_000
            current.obj("shift").long("txCount") shouldBe 2
            current.array("entries").objects().map { it.string("kind") } shouldContainExactly
                listOf("OPENING", "SALE", "CASH_OUT", "CASH_IN")

            val closed =
                api.post("/shifts/$id/close", """{"countedCash":1624000}""", siti.token, idempotencyKey = Tx.key())
            closed.status shouldBe HttpStatusCode.OK
            val recap = closed.json().obj("recap")
            recap.long("expected") shouldBe 1_624_000
            recap.long("actual") shouldBe 1_624_000
            recap.long("diff") shouldBe 0
            closed
                .json()
                .obj("shift")
                .getValue("open")
                .toString() shouldBe "false"
            ApiTestSupport.auditCount("Tutup shift Cabang Tebet · kas cocok") shouldBe 1
            ApiTestSupport.auditCount("Kas keluar Rp1.180.000 — beli deterjen") shouldBe 1
            ApiTestSupport.auditCount("Kas masuk Rp62.000 — kembalian titipan") shouldBe 1
            ApiTestSupport.auditCount("Buka shift Cabang Tebet · modal awal Rp500.000") shouldBe 1

            // Closed means closed: no second close, no more cash, no more counting, no more online orders.
            api
                .post("/shifts/$id/close", """{"countedCash":1624000}""", siti.token, idempotencyKey = Tx.key())
                .shouldFailWith(HttpStatusCode.Conflict, "SHIFT_CLOSED")
                .string("message") shouldBe "Shift Cabang Tebet sudah ditutup."
            api
                .post(
                    "/shifts/$id/cash-entries",
                    """{"direction":"IN","label":"x","amount":1}""",
                    siti.token,
                    idempotencyKey = Tx.key(),
                ).shouldFailWith(HttpStatusCode.UnprocessableEntity, "SHIFT_NOT_OPEN")
                .string("message") shouldBe "Shift Cabang Tebet belum dibuka."
            api
                .put("/shifts/$id/counted-cash", """{"countedCash":1}""", siti.token)
                .shouldFailWith(HttpStatusCode.Conflict, "SHIFT_CLOSED")
        }

    @Test
    fun `should report a short drawer as a difference`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val id = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            api.walkIn(siti, Line(Tx.serviceId("Cuci Setrika"), "2", 10_000))

            val recap =
                api
                    .post("/shifts/$id/close", """{"countedCash":512000}""", siti.token, idempotencyKey = Tx.key())
                    .json()
                    .obj("recap")

            recap.long("expected") shouldBe 520_000
            recap.long("diff") shouldBe -8_000
            ApiTestSupport.auditCount("Tutup shift Cabang Tebet · selisih Rp8.000") shouldBe 1
        }

    @Test
    fun `should check branch, open shift, opening cash and proof in that order`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val proof = api.staffProof(siti, "Siti Nurhaliza", "1234")

            api
                .openWith(siti, """{"openingCash":0,"staffProof":"$proof"}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "OPENING_CASH_REQUIRED")
                .string("message") shouldBe "Modal awal belum diisi."
            api
                .openWith(siti, """{"openingCash":500000,"staffProof":"sp_palsu"}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_PROOF_INVALID")
            api
                .openWith(siti, """{"openingCash":500000}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_PROOF_INVALID")
            // A rejected opening leaves the proof unspent.
            api.openWith(siti, """{"openingCash":500000,"staffProof":"$proof"}""").status shouldBe
                HttpStatusCode.Created

            val again = api.staffProof(siti, "Siti Nurhaliza", "1234")
            api
                .openWith(siti, """{"openingCash":500000,"staffProof":"$again"}""")
                .shouldFailWith(HttpStatusCode.Conflict, "SHIFT_ALREADY_OPEN")
                .string("message") shouldBe "Shift Cabang Tebet masih terbuka — tutup dulu sebelum membuka yang baru."
            ApiTestSupport.count("SELECT count(*) FROM shifts") shouldBe 1

            // A proof is spent once: after closing, the used proof cannot open the next shift.
            val id = scalar("SELECT id FROM shifts")
            api.post("/shifts/$id/close", """{"countedCash":500000}""", siti.token, idempotencyKey = Tx.key())
            api
                .openWith(siti, """{"openingCash":500000,"staffProof":"$proof"}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_PROOF_INVALID")
                .string("message") shouldBe "Verifikasi PIN sudah kedaluwarsa — pilih staff dan masukkan PIN lagi."

            // A proof from another tablet is not valid here.
            val otherTablet = api.till("TBT", "5678")
            val foreign = api.staffProof(otherTablet, "Bagas Ardhana", "5678")
            api
                .openWith(siti, """{"openingCash":500000,"staffProof":"$foreign"}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_PROOF_INVALID")
        }

    @Test
    fun `should refuse a shift in an inactive branch`() =
        apiTest(clock) { api ->
            val wulan = api.till("CPT", "3690")
            val proof = api.staffProof(wulan, "Wulan Sari", "3690")
            val owner = api.ownerToken()
            api.patch("/branches/${ApiTestSupport.branchId("CPT")}", """{"active":false}""", owner)

            api
                .openWith(wulan, """{"openingCash":300000,"staffProof":"$proof"}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "BRANCH_INACTIVE")
                .string("message") shouldBe
                "Cabang Cipete nonaktif — shift baru tidak bisa dibuka sampai owner mengaktifkan cabang."
            ApiTestSupport.count("SELECT count(*) FROM shifts") shouldBe 0
        }

    @Test
    fun `should hand the tablet to the staff member who opens the shift`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val proof = api.staffProof(siti, "Bagas Ardhana", "5678")

            val response = api.openWith(siti, """{"openingCash":400000,"staffProof":"$proof"}""")

            response.status shouldBe HttpStatusCode.Created
            val body = response.json()
            body.obj("shift").string("openedByName") shouldBe "Bagas Ardhana"
            val bagas = body.obj("session").string("accessToken")
            api
                .get("/me", bagas)
                .json()
                .obj("staff")
                .string("name") shouldBe "Bagas Ardhana"
            // Siti's session on this tablet ended with the handover.
            api.get("/me", siti.token).status shouldBe HttpStatusCode.Unauthorized
            ApiTestSupport.auditCount("Buka shift Cabang Tebet · modal awal Rp400.000") shouldBe 1
            scalar("SELECT staff_id FROM cash_entries WHERE kind = 'OPENING'") shouldBe
                ApiTestSupport.staffId("Bagas Ardhana").toString()
        }

    @Test
    fun `should replay a retried close without closing twice`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val id = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")
            val key = Tx.key()

            val first = api.post("/shifts/$id/close", """{"countedCash":500000}""", siti.token, idempotencyKey = key)
            val retry = api.post("/shifts/$id/close", """{"countedCash":500000}""", siti.token, idempotencyKey = key)

            first.status shouldBe HttpStatusCode.OK
            retry.status shouldBe HttpStatusCode.OK
            retry.json() shouldBe first.json()
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'SHIFT_CLOSED'") shouldBe 1
        }

    @Test
    fun `should keep a cashier to the shifts of their own branch`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val nia = api.till("BTR", "2468")
            api.openShift(siti, "Siti Nurhaliza", "1234")
            val bintaro = api.openShift(nia, "Nia Ramadhani", "2468").string("id")
            val bintaroId = ApiTestSupport.branchId("BTR")

            api
                .get("/shifts/current?branchId=$bintaroId", siti.token)
                .shouldFailWith(HttpStatusCode.Forbidden, "BRANCH_SCOPE")
            api
                .post(
                    "/shifts/$bintaro/cash-entries",
                    """{"direction":"OUT","label":"x","amount":1000}""",
                    siti.token,
                    idempotencyKey = Tx.key(),
                ).shouldFailWith(HttpStatusCode.Forbidden, "BRANCH_SCOPE")
            api
                .post("/shifts/$bintaro/close", """{"countedCash":0}""", siti.token, idempotencyKey = Tx.key())
                .shouldFailWith(HttpStatusCode.Forbidden, "BRANCH_SCOPE")
            api.get("/shifts", siti.token).status shouldBe HttpStatusCode.Forbidden

            api
                .get("/shifts/latest", siti.token)
                .json()
                .array("items")
                .size shouldBe 1

            val owner = api.ownerToken()
            api
                .get("/shifts/latest", owner)
                .json()
                .array("items")
                .size shouldBe 2
            api
                .get("/shifts/current?branchId=$bintaroId", owner)
                .json()
                .obj("shift")
                .string("id") shouldBe bintaro
            val history = api.get("/shifts?branchId=$bintaroId&from=2026-09-15&to=2026-09-15", owner).json()
            history.array("items").objects().map { it.string("id") } shouldContainExactly listOf(bintaro)
            api.get("/shifts?limit=1", owner).json().isNull("nextCursor") shouldBe false
        }

    @Test
    fun `should validate the cash entry in the documented order`() =
        apiTest(clock) { api ->
            val siti = api.till("TBT", "1234")
            val id = api.openShift(siti, "Siti Nurhaliza", "1234").string("id")

            suspend fun entry(body: String) =
                api.post("/shifts/$id/cash-entries", body, siti.token, idempotencyKey = Tx.key())

            entry("""{"direction":"OUT","label":"  ","amount":0}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "CASH_LABEL_REQUIRED")
                .string("message") shouldBe "Keterangan wajib diisi untuk audit trail."
            entry("""{"direction":"OUT","label":"parkir","amount":0}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "AMOUNT_REQUIRED")
                .string("message") shouldBe "Jumlah belum diisi."
            entry("""{"direction":"SIDEWAYS","label":"parkir","amount":2000}""")
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
            ApiTestSupport.count("SELECT count(*) FROM cash_entries WHERE kind <> 'OPENING'") shouldBe 0
        }
}
