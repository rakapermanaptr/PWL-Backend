package id.primawash.api

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Price list, loyalty rate, rewards and customers (PRD §8.4, §8.5). */
class CatalogAndCustomerFlowTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private fun serviceId(name: String) = ApiTestSupport.uuidOf("SELECT id FROM services WHERE name = '$name'")

    @Test
    fun `should save changed prices with history and one audit row per change plus a summary`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val body = """{"prices":{"${serviceId("Cuci Setrika")}":11000,"${serviceId("Cuci Kering")}":7000}}"""
            val response = api.patch("/services/prices", body, owner)

            response.status shouldBe HttpStatusCode.OK
            response
                .json()
                .getValue("changedCount")
                .jsonPrimitive.content shouldBe "1"
            ApiTestSupport.count(
                "SELECT count(*) FROM service_price_history WHERE old_price = 10000 AND new_price = 11000",
            ) shouldBe
                1
            ApiTestSupport.auditCount("Ubah harga Cuci Setrika: Rp10.000 → Rp11.000 (semua cabang)") shouldBe 1
            ApiTestSupport.auditCount("Simpan price list — 15 layanan aktif di semua cabang") shouldBe 1
        }

    @Test
    fun `should refuse an empty price and a duplicate service name`() =
        apiTest { api ->
            val owner = api.ownerToken()
            api
                .patch("/services/prices", """{"prices":{"${serviceId("Cuci Setrika")}":0}}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "PRICE_REQUIRED")
            api
                .post("/services", """{"category":"SATUAN","name":"bed cover","price":30000,"unit":"pcs"}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "SERVICE_NAME_TAKEN")
        }

    @Test
    fun `should give a new kiloan service a half kilo step`() =
        apiTest { api ->
            val created =
                api.post(
                    "/services",
                    """{"category":"KILOAN_EXPRESS","name":"Cuci Kering Express","price":12000,"unit":"kg"}""",
                    api.ownerToken(),
                )
            created.status shouldBe HttpStatusCode.Created
            created
                .json()
                .obj("service")
                .getValue("step")
                .jsonPrimitive.content shouldBe "0.5"
        }

    @Test
    fun `should hide inactive services from a cashier even when asked`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            val items =
                api
                    .get("/services?includeInactive=true", siti)
                    .json()
                    .getValue("items")
                    .jsonArray
            items.size shouldBe 15
            items.none { it.jsonObject.string("name") == "Gorden" } shouldBe true
        }

    @Test
    fun `should store a new loyalty rate as history without touching the old row`() =
        apiTest { api ->
            val owner = api.ownerToken()
            api
                .put("/loyalty/rate", """{"rupiahPerStep":500,"pointsPerStep":100}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "RATE_INVALID")
                .string("message") shouldBe "Nominal belanja minimal Rp1.000."

            val saved = api.put("/loyalty/rate", """{"rupiahPerStep":10000,"pointsPerStep":150}""", owner)
            saved.status shouldBe HttpStatusCode.OK
            ApiTestSupport.count("SELECT count(*) FROM loyalty_rates") shouldBe 2
            ApiTestSupport.count("SELECT count(*) FROM loyalty_rates WHERE points_per_step = 100") shouldBe 1
            api
                .get("/loyalty/rate", owner)
                .json()
                .getValue("pointsPerStep")
                .jsonPrimitive.content shouldBe "150"
        }

    @Test
    fun `should add a reward with an enforced minimum spend`() =
        apiTest { api ->
            val created =
                api.post(
                    "/rewards",
                    """{"name":"Diskon Rp50.000","cost":5000,"value":50000,"minSubtotal":150000}""",
                    api.ownerToken(),
                )
            created.status shouldBe HttpStatusCode.Created
            val reward = created.json().obj("reward")
            reward.string("note") shouldBe "Setara Rp50.000"
            reward.getValue("minSubtotal").jsonPrimitive.content shouldBe "150000"
        }

    @Test
    fun `should register a customer once across every branch`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            val nia =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Bintaro 1", "BTR"),
                        "BTR",
                        "2468",
                    ).string("accessToken")

            val created =
                api.post(
                    "/customers",
                    """{"name":"Dewi Anggraini","phone":"+62 812-3390-4471","optIn":true}""",
                    siti,
                )
            created.status shouldBe HttpStatusCode.Created
            val customer = created.json().obj("customer")
            customer.string("phone") shouldBe "0812-3390-4471"
            customer.string("homeBranchId") shouldBe ApiTestSupport.branchId("TBT").toString()

            val duplicate =
                api
                    .post("/customers", """{"name":"Dewi A.","phone":"081233904471","optIn":false}""", nia)
                    .shouldFailWith(HttpStatusCode.Conflict, "PHONE_ALREADY_REGISTERED")
            duplicate.obj("details").obj("customer").string("id") shouldBe customer.string("id")

            api
                .post("/customers", """{"name":"Bayu","phone":"0812-339","optIn":false}""", nia)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "PHONE_INVALID")
            ApiTestSupport.auditCount("Daftarkan customer Dewi Anggraini (0812-3390-4471) · opt-in WA: ya") shouldBe 1
        }

    @Test
    fun `should find customers by name or phone digits and page through them`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            api.post("/customers", """{"name":"Dewi Anggraini","phone":"081233904471","optIn":true}""", siti)
            api.post("/customers", """{"name":"Bayu Prakoso","phone":"085711208834","optIn":false}""", siti)
            api.post("/customers", """{"name":"Rina Kusuma","phone":"083877124409","optIn":false}""", siti)

            val byName =
                api
                    .get("/customers?q=dewi", siti)
                    .json()
                    .getValue("items")
                    .jsonArray
            byName.map { it.jsonObject.string("name") } shouldBe listOf("Dewi Anggraini")
            val byPhone =
                api
                    .get("/customers?q=0857-1120", siti)
                    .json()
                    .getValue("items")
                    .jsonArray
            byPhone.map { it.jsonObject.string("name") } shouldBe listOf("Bayu Prakoso")

            val first = api.get("/customers?limit=2", siti).json()
            first.getValue("items").jsonArray.size shouldBe 2
            val second = api.get("/customers?limit=2&cursor=${first.string("nextCursor")}", siti).json()
            second.getValue("items").jsonArray.size shouldBe 1
            second.getValue("nextCursor").toString() shouldBe "null"
        }

    @Test
    fun `should cancel queued whatsapp messages when a customer opts out`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            val id =
                api
                    .post("/customers", """{"name":"Dewi Anggraini","phone":"081233904471","optIn":true}""", siti)
                    .json()
                    .obj("customer")
                    .string("id")
            PostgresSupport.withConnection { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO wa_messages (branch_id, customer_id, template, to_phone) " +
                            "VALUES ('${ApiTestSupport.branchId(
                                "TBT",
                            )}', '$id', 'STATUS_SIAP_DIAMBIL', '6281233904471')",
                    )
                }
            }

            val updated = api.patch("/customers/$id", """{"optIn":false}""", siti)
            updated.status shouldBe HttpStatusCode.OK
            ApiTestSupport.count("SELECT count(*) FROM wa_messages WHERE status = 'CANCELLED'") shouldBe 1
            ApiTestSupport.count("SELECT count(*) FROM customers WHERE opt_out_at IS NOT NULL") shouldBe 1
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'CUSTOMER_OPTED_OUT'") shouldBe 1
        }
}
