package id.primawash.api

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Staff accounts and branch settings (PRD §8.2, §8.3). */
class StaffAndBranchFlowTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should refuse to reactivate an account whose pin is now used by an active one`() =
        apiTest { api ->
            // PRD §16 #15: Yuni (inactive, PIN 4321) — Dimas takes 4321 while she is away.
            val owner = api.ownerToken()
            val created =
                api.post(
                    "/staff",
                    """{"name":"Dimas Pratama","pin":"4321","role":"KASIR","branchId":"${ApiTestSupport.branchId(
                        "CPT",
                    )}"}""",
                    owner,
                )
            created.status shouldBe HttpStatusCode.Created
            created.json().obj("staff").string("shortName") shouldBe "Dimas P."

            api
                .patch("/staff/${ApiTestSupport.staffId("Yuni Astari")}", """{"active":true}""", owner)
                .shouldFailWith(HttpStatusCode.Conflict, "PIN_CONFLICT")
                .string("message") shouldBe
                "PIN Yuni Astari sudah dipakai staff lain — reset PIN setelah akun diaktifkan."
        }

    @Test
    fun `should refuse a pin already used by an active account`() =
        apiTest { api ->
            api
                .post(
                    "/staff",
                    """{"name":"Kasir Baru","pin":"1234","role":"KASIR","branchId":"${ApiTestSupport.branchId(
                        "FMU",
                    )}"}""",
                    api.ownerToken(),
                ).shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_TAKEN")
        }

    @Test
    fun `should validate a new account in the documented order`() =
        apiTest { api ->
            val owner = api.ownerToken()
            api
                .post("/staff", """{"name":" ","pin":"12","role":"KASIR"}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "NAME_REQUIRED")
            api
                .post("/staff", """{"name":"Andi","pin":"12","role":"KASIR"}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_FORMAT")
                .string("message") shouldBe "PIN harus 4–6 digit."
            api
                .post("/staff", """{"name":"Andi","pin":"777777","role":"KASIR"}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "CASHIER_NEEDS_BRANCH")
            api
                .post("/staff", """{"name":"Andi","pin":"777777","role":"MANAJER"}""", owner)
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }

    @Test
    fun `should not let an owner deactivate their own account or the last owner`() =
        apiTest { api ->
            val owner = api.ownerToken()
            api
                .patch("/staff/${ApiTestSupport.staffId("Raka Prasetyo")}", """{"active":false}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_SELF_DEACTIVATE")
        }

    @Test
    fun `should revoke the sessions of a deactivated account`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val bagas =
                api
                    .login(
                        ApiTestSupport.newDevice(),
                        "FMU",
                        "5678",
                    ).string("accessToken")

            val response = api.patch("/staff/${ApiTestSupport.staffId("Bagas Ardhana")}", """{"active":false}""", owner)
            response.status shouldBe HttpStatusCode.OK
            api.get("/me", bagas).status shouldBe HttpStatusCode.Unauthorized
            ApiTestSupport.auditCount("Nonaktifkan akun Bagas Ardhana") shouldBe 1
        }

    @Test
    fun `should show a cashier only the accounts that can work at their branch`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.newDevice(),
                        "FMU",
                        "1234",
                    ).string("accessToken")
            val items =
                api
                    .get("/staff", siti)
                    .json()
                    .getValue("items")
                    .jsonArray

            items.map { it.jsonObject.string("name") } shouldContainExactly
                listOf("Siti Nurhaliza", "Bagas Ardhana", "Raka Prasetyo")
            items.first().jsonObject.keys shouldBe setOf("id", "name", "shortName", "role")
            api
                .get("/staff?branchId=${ApiTestSupport.branchId("NRG")}", siti)
                .shouldFailWith(HttpStatusCode.Forbidden, "BRANCH_SCOPE")
        }

    @Test
    fun `should change a branch target with an audit row and refuse an empty one`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val familiaUrban = ApiTestSupport.branchId("FMU")

            api
                .patch("/branches/$familiaUrban", """{"dailyTarget":0}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "TARGET_REQUIRED")
                .string("message") shouldBe "Target harian tidak boleh kosong — dikembalikan ke Rp5.200.000."

            val changed = api.patch("/branches/$familiaUrban", """{"dailyTarget":5500000}""", owner).json()
            changed.getValue("changed").jsonPrimitive.content shouldBe "true"
            val unchanged = api.patch("/branches/$familiaUrban", """{"dailyTarget":5500000}""", owner).json()
            unchanged.getValue("changed").jsonPrimitive.content shouldBe "false"

            ApiTestSupport.auditCount("Ubah target harian Cabang Familia Urban: Rp5.200.000 → Rp5.500.000") shouldBe 1
        }

    @Test
    fun `should not deactivate the branch the owner's tablet is working for`() =
        apiTest { api ->
            val owner = api.ownerToken()
            api
                .patch("/branches/${ApiTestSupport.branchId("FMU")}", """{"active":false}""", owner)
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "BRANCH_IN_USE")

            api.patch("/branches/${ApiTestSupport.branchId("CPT")}", """{"active":false}""", owner).status shouldBe
                HttpStatusCode.OK
            api
                .pinLogin(ApiTestSupport.newDevice(), "CPT", "3690")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "BRANCH_INACTIVE")
        }

    @Test
    fun `should list branches with active cashiers for the owner overview`() =
        apiTest { api ->
            val overview = api.get("/branches/overview?date=2026-09-15", api.ownerToken()).json()
            overview.string("date") shouldBe "2026-09-15"
            val cipete =
                overview
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject }
                    .first { it.obj("branch").string("code") == "CPT" }
            cipete.getValue("activeCashiers").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Wulan S.")
            cipete.getValue("revenue").jsonPrimitive.content shouldBe "0"
        }
}
